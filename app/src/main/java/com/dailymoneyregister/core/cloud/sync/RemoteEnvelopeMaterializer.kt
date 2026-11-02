package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.OperationStatus
import org.json.JSONObject
import java.math.BigDecimal

sealed class InboundProcessResult {
    data object Applied : InboundProcessResult()
    data object Skipped : InboundProcessResult()
    data class UnsupportedYet(val operationType: String) : InboundProcessResult()
    data class Rejected(val reason: String) : InboundProcessResult()
}

/**
 * Universal Remote Envelope Materializer.
 *
 * Materializes remote financial envelopes received from cloud synchronization into the local Room database
 * atomically and idempotently.
 */
object RemoteEnvelopeMaterializer {

    suspend fun materializeInboundEnvelope(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        sessionBusinessId: String,
        sessionBranchId: String?
    ): InboundProcessResult {
        val opId = envelope.operationId
        val opTypeStr = envelope.operationType

        val jsonPayload = envelope.payloadJson

        // Verify the canonical text and separately returned JSONB payload are
        // semantically identical (object key order is irrelevant). The current
        // backend fetch RPC does not expose canonical_sha256, so this is a
        // consistency check, not a substitute for server-side digest verification.
        val canonicalPayload = runCatching { JSONObject(envelope.canonicalText) }.getOrNull()
        if (canonicalPayload == null || normalizeJson(canonicalPayload) != normalizeJson(jsonPayload)) {
            return InboundProcessResult.Rejected("Canonical text and payload JSON disagree")
        }

        // The transport row and canonical payload are two representations of the
        // same immutable envelope. Reject disagreement before any duplicate check
        // or database write, rather than trusting either representation alone.
        val payloadBranchId = jsonPayload.optString("branch_id").ifBlank { null }
        val payloadOccurredAt = jsonPayload.optLong("occurred_at", Long.MIN_VALUE)
        val envelopeOccurredAt = runCatching {
            java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()
        }.getOrNull()
        if (
            jsonPayload.optString("operation_id") != opId ||
            jsonPayload.optString("business_id") != envelope.businessId ||
            payloadBranchId != envelope.branchId ||
            jsonPayload.optString("operation_type") != opTypeStr ||
            envelopeOccurredAt == null ||
            payloadOccurredAt != envelopeOccurredAt ||
            jsonPayload.optString("request_fingerprint").isBlank() ||
            jsonPayload.optInt("command_schema_version", 0) < 1
        ) {
            return InboundProcessResult.Rejected(
                "Envelope metadata does not match its canonical payload"
            )
        }

        // Tenant and branch checks must run before operation dispatch so no handler
        // can materialize an envelope outside the active Admin scope.
        if (envelope.businessId != sessionBusinessId) {
            return InboundProcessResult.Rejected("Business mismatch: envelope business='${envelope.businessId}' vs session business='$sessionBusinessId'")
        }
        if (sessionBranchId != null && envelope.branchId != null && envelope.branchId != sessionBranchId) {
            return InboundProcessResult.Rejected("Branch mismatch: envelope branch='${envelope.branchId}' vs session branch='$sessionBranchId'")
        }

        val branchId = envelope.branchId ?: sessionBranchId
            ?: return InboundProcessResult.Rejected("Branch ID missing in envelope and admin session")

        val payloadActorUserId = jsonPayload.optString("actor_user_id")
        if (
            payloadActorUserId.isBlank() ||
            !RemoteEnvelopeIdentity.hasActorIdentity(database, payloadActorUserId, sessionBusinessId, branchId, envelope)
        ) {
            return InboundProcessResult.Rejected("Referenced operation actor is missing from synchronized staff identities")
        }

        // A duplicate operation ID is only a replay when its immutable identity
        // matches the active tenant, branch, type, and request fingerprint. Scope
        // checks intentionally precede duplicate handling.
        val incomingFingerprint = jsonPayload.optString("request_fingerprint")
        val existingOp = database.operationDao().getOperationById(opId)
        if (existingOp != null) {
            if (
                existingOp.businessId != envelope.businessId ||
                existingOp.branchId != branchId ||
                existingOp.operationType.name != opTypeStr ||
                existingOp.requestFingerprint != incomingFingerprint
            ) {
                return InboundProcessResult.Rejected(
                    "Operation ID collision: existing operation identity does not match inbound envelope"
                )
            }
            if (existingOp.status == OperationStatus.SUCCEEDED) {
                return InboundProcessResult.Skipped
            }
        }

        // Expense creation already has a canonical expense snapshot and a Room
        // entity. Route only RECORD_EXPENSE through the generic atomic writer after
        // validating its tenant/branch/actor links. Corrections/voids require their
        // dedicated immutable event rows and are deliberately not treated as creates.
        if (opTypeStr == "RECORD_EXPENSE") {
            val expense = jsonPayload.optJSONObject("expense")
                ?: return InboundProcessResult.Rejected("Missing expense object for RECORD_EXPENSE")
            if (expense.optString("operation_id") != opId ||
                expense.optString("business_id") != sessionBusinessId ||
                expense.optString("branch_id") != branchId
            ) {
                return InboundProcessResult.Rejected("Expense references do not match the envelope tenant, branch, or operation")
            }
            val actorUserId = expense.optString("actor_user_id").ifBlank {
                jsonPayload.optString("actor_user_id")
            }
            if (actorUserId.isBlank() ||
                !RemoteEnvelopeIdentity.hasActorIdentity(database, actorUserId, sessionBusinessId, branchId, envelope)
            ) {
                return InboundProcessResult.Rejected("Referenced expense actor is missing from synchronized staff identities")
            }
            val branch = database.businessBranchDao().getBranchById(branchId)
                ?: return InboundProcessResult.Rejected("Referenced branch '$branchId' is missing locally")
            if (branch.businessId != sessionBusinessId ||
                expense.optString("currency_code") != branch.currencyCode
            ) {
                return InboundProcessResult.Rejected("Expense branch or currency does not match the active business")
            }
            return RemoteExpenseEnvelopeMaterializer.materialize(database, jsonPayload)
        }

        // Transaction-linked operations share the immutable original transaction
        // snapshot, but each must also carry its own typed event/ledger evidence.
        val transactionOperationTypes = setOf(
            "RECORD_SALE",
            "RECORD_COUPON_PURCHASE",
            "RECORD_AMOUNT_CORRECTION",
            "RECORD_PAYMENT_METHOD_CHANGE",
            "RECORD_VOID",
            "RECORD_REFUND"
        )
        if (opTypeStr !in transactionOperationTypes) {
            return RemoteNonTransactionEnvelopeMaterializer.materialize(
                database = database,
                envelope = envelope,
                branchId = branchId,
                sessionBusinessId = sessionBusinessId,
                actorUserId = jsonPayload.optString("actor_user_id")
            )
        }

        return RemoteTransactionEnvelopeMaterializer.materialize(
            database = database,
            envelope = envelope,
            sessionBusinessId = sessionBusinessId,
            branchId = branchId,
            actorUserId = jsonPayload.optString("actor_user_id")
        )
    }

    private fun normalizeJson(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.associateWith { key -> normalizeJson(value.get(key)) }
        }
        is org.json.JSONArray -> (0 until value.length()).map { index -> normalizeJson(value.get(index)) }
        is Number -> runCatching { BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }
            .getOrElse { value.toString() }
        JSONObject.NULL -> null
        else -> value
    }
}
