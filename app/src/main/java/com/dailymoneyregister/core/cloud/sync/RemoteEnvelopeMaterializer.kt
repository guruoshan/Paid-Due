package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
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

    private fun resultReferenceFor(
        operationType: String,
        financialEvidence: JSONObject,
        transactionId: String
    ): Pair<String, String> {
        val (referenceType, referenceId) = when (operationType) {
            "RECORD_REFUND" -> "REFUND_EVENT" to
                financialEvidence.getJSONObject("refund_event").getString("id")
            "RECORD_VOID" -> "VOID_EVENT" to
                financialEvidence.getJSONObject("void_event").getString("id")
            "RECORD_AMOUNT_CORRECTION" -> "AMOUNT_CORRECTION_EVENT" to
                financialEvidence.getJSONArray("amount_correction_events").getJSONObject(0).getString("id")
            "RECORD_PAYMENT_METHOD_CHANGE" -> "PAYMENT_METHOD_CHANGE_EVENT" to
                financialEvidence.getJSONArray("payment_method_change_events").getJSONObject(0).getString("id")
            else -> "TRANSACTION" to transactionId
        }
        require(referenceId.isNotBlank()) {
            "Inbound $operationType result reference ID is missing"
        }
        return referenceType to referenceId
    }

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
            !hasActorIdentity(database, payloadActorUserId, sessionBusinessId, branchId, envelope)
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
                !hasActorIdentity(database, actorUserId, sessionBusinessId, branchId, envelope)
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
            return if (RemoteExpenseEnvelopeMaterializer.materialize(database, jsonPayload)) {
                InboundProcessResult.Applied
            } else {
                InboundProcessResult.Rejected("Expense envelope could not be materialized")
            }
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
            return materializeNonTransactionOperation(
                database = database,
                envelope = envelope,
                branchId = branchId,
                sessionBusinessId = sessionBusinessId,
                actorUserId = jsonPayload.optString("actor_user_id")
            )
        }

        // Check transaction payload
        if (!jsonPayload.has("transaction") || jsonPayload.isNull("transaction")) {
            return InboundProcessResult.Rejected("Missing transaction object for RECORD_SALE")
        }
        val tJson = jsonPayload.getJSONObject("transaction")
        val txType = tJson.optString("type", "")
        val validTransactionType = when (opTypeStr) {
            "RECORD_SALE" -> txType == "SALE"
            "RECORD_COUPON_PURCHASE" -> txType == "COUPON_PURCHASE"
            else -> txType == "SALE" || txType == "COUPON_PURCHASE"
        }
        if (!validTransactionType) {
            return InboundProcessResult.Rejected(
                "Transaction type '$txType' is incompatible with operation '$opTypeStr'"
            )
        }

        val grandTotalMinor = tJson.getLong("grand_total_minor")
        val txCurrencyCode = tJson.getString("currency_code")

        // Sale and Coupon purchase funding must balance to the immutable total.
        // Lifecycle operations carry only their own signed compensation/reclassification
        // settlements, so their settlement sum is not compared with the original sale.
        val settlementsArray = jsonPayload.optJSONArray("settlements") ?: org.json.JSONArray()
        val requiresFullFunding = opTypeStr == "RECORD_SALE" || opTypeStr == "RECORD_COUPON_PURCHASE"
        if (requiresFullFunding && settlementsArray.length() == 0) {
            return InboundProcessResult.Rejected("Missing funding settlements for $opTypeStr")
        }

        var settlementSum = 0L
        for (i in 0 until settlementsArray.length()) {
            val sJson = settlementsArray.getJSONObject(i)
            settlementSum = Math.addExact(settlementSum, sJson.getLong("amount_signed_minor"))
        }

        if (requiresFullFunding && grandTotalMinor != settlementSum) {
            return InboundProcessResult.Rejected(
                "grand_total_minor ($grandTotalMinor) does not equal settlement sum ($settlementSum)"
            )
        }

        // c) currency matches the branch
        val branchEntity = database.businessBranchDao().getBranchById(branchId)
            ?: return InboundProcessResult.Rejected("Referenced branch '$branchId' is missing locally")
        if (branchEntity.businessId != sessionBusinessId) {
            return InboundProcessResult.Rejected("Referenced branch '$branchId' belongs to another business")
        }
        if (branchEntity.currencyCode != txCurrencyCode) {
            return InboundProcessResult.Rejected("Transaction currency '$txCurrencyCode' does not match branch currency '${branchEntity.currencyCode}'")
        }

        val actorUserId = jsonPayload.optString("actor_user_id")
        if (actorUserId.isBlank()) {
            return InboundProcessResult.Rejected("Actor user ID is missing")
        }
        if (!hasActorIdentity(database, actorUserId, sessionBusinessId, branchId, envelope)) {
            return InboundProcessResult.Rejected(
                "Referenced actor user '$actorUserId' is missing from synchronized staff identities"
            )
        }
        val createdByUserId = tJson.optString("created_by_user_id").ifBlank { actorUserId }
        if (!hasActorIdentity(database, createdByUserId, sessionBusinessId, branchId, envelope)) {
            return InboundProcessResult.Rejected(
                "Referenced transaction user '$createdByUserId' is missing from synchronized staff identities"
            )
        }
        if (tJson.getString("branch_id") != branchId) {
            return InboundProcessResult.Rejected("Transaction branch does not match envelope branch")
        }
        val branch = database.businessBranchDao().getBranchById(branchId)
            ?: return InboundProcessResult.Rejected("Referenced transaction branch is missing locally")
        if (branch.businessId != sessionBusinessId || branch.currencyCode != txCurrencyCode) {
            return InboundProcessResult.Rejected("Transaction business/currency does not match the branch")
        }
        if (
            tJson.optString("id").isBlank() ||
            jsonPayload.optString("subject_type") != SubjectType.TRANSACTION.name ||
            jsonPayload.optString("subject_id") != tJson.optString("id") ||
            jsonPayload.optString("transaction_id") != tJson.optString("id")
        ) {
            return InboundProcessResult.Rejected("Transaction subject metadata does not match the transaction snapshot")
        }
        for (i in 0 until settlementsArray.length()) {
            val settlement = settlementsArray.getJSONObject(i)
            if (
                settlement.getString("operation_id") != opId ||
                settlement.optString("transaction_id") != tJson.getString("id") ||
                settlement.optString("subject_type") != SubjectType.TRANSACTION.name ||
                settlement.optString("subject_id") != tJson.getString("id")
            ) {
                return InboundProcessResult.Rejected("Settlement references do not match the envelope")
            }
            if (settlement.getString("currency_code") != txCurrencyCode) {
                return InboundProcessResult.Rejected("Settlement currency does not match transaction currency")
            }
            // Payment methods are data, not a reason to reject a valid sale. Parse
            // both enum fields so cash, online/card, and split-tender sales are
            // supported while malformed/unknown tender values fail closed.
            val category = runCatching {
                PaymentCategory.valueOf(settlement.getString("category"))
            }.getOrElse {
                return InboundProcessResult.Rejected("Unknown settlement category")
            }
            val channel = runCatching {
                PaymentChannel.valueOf(settlement.getString("channel"))
            }.getOrElse {
                return InboundProcessResult.Rejected("Unknown settlement channel")
            }
            val validPaymentPair = when (category) {
                PaymentCategory.CASH -> channel == PaymentChannel.CASH_TENDER
                PaymentCategory.ONLINE -> channel == PaymentChannel.MANUAL_QR || channel == PaymentChannel.SOFTPOS
                PaymentCategory.CREDIT -> channel == PaymentChannel.CREDIT_ACCOUNT
                PaymentCategory.COUPON -> channel == PaymentChannel.COUPON_WALLET
            }
            if (!validPaymentPair) {
                return InboundProcessResult.Rejected("Settlement payment category/channel combination is invalid")
            }
            val purpose = runCatching {
                EntryPurpose.valueOf(settlement.getString("entry_purpose"))
            }.getOrElse {
                return InboundProcessResult.Rejected("Unknown settlement entry purpose")
            }
            val expectedPurpose = when (opTypeStr) {
                "RECORD_SALE" -> EntryPurpose.SALE_FUNDING
                "RECORD_COUPON_PURCHASE" -> EntryPurpose.COUPON_PURCHASE_FUNDING
                "RECORD_REFUND" -> EntryPurpose.REFUND_OUTFLOW
                "RECORD_VOID" -> EntryPurpose.VOID_REVERSAL
                "RECORD_AMOUNT_CORRECTION" -> EntryPurpose.FUNDING_CORRECTION
                "RECORD_PAYMENT_METHOD_CHANGE" -> EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION
                else -> null
            }
            if (expectedPurpose != null && purpose != expectedPurpose) {
                return InboundProcessResult.Rejected("Settlement purpose is incompatible with $opTypeStr")
            }
            val signedAmount = settlement.optLong("amount_signed_minor", 0L)
            if ((opTypeStr == "RECORD_SALE" || opTypeStr == "RECORD_COUPON_PURCHASE") && signedAmount <= 0L) {
                return InboundProcessResult.Rejected("Sale funding settlement must be positive")
            }
            if ((opTypeStr == "RECORD_REFUND" || opTypeStr == "RECORD_VOID") && signedAmount >= 0L) {
                return InboundProcessResult.Rejected("Refund/void reversal settlement must be negative")
            }
            if ((opTypeStr == "RECORD_AMOUNT_CORRECTION" || opTypeStr == "RECORD_PAYMENT_METHOD_CHANGE") && signedAmount == 0L) {
                return InboundProcessResult.Rejected("Correction/reclassification settlement cannot be zero")
            }
            if (settlement.optString("actor_user_id") != actorUserId) {
                return InboundProcessResult.Rejected("Settlement actor does not match the owning operation actor")
            }
        }

        val financialEvidence = jsonPayload.optJSONObject("financial_evidence") ?: JSONObject()
        val hasCreditEvents = (financialEvidence.optJSONArray("credit_ledger_events")?.length() ?: 0) > 0
        val hasCouponEvents = (financialEvidence.optJSONArray("coupon_ledger_events")?.length() ?: 0) > 0
        when (opTypeStr) {
            "RECORD_COUPON_PURCHASE" -> if (
                (financialEvidence.optJSONArray("coupon_wallets")?.length() ?: 0) == 0 || !hasCouponEvents
            ) return InboundProcessResult.Rejected("Coupon purchase is missing wallet/ledger evidence")
            "RECORD_AMOUNT_CORRECTION" -> if (
                (financialEvidence.optJSONArray("amount_correction_events")?.length() ?: 0) != 1
            ) return InboundProcessResult.Rejected("Amount correction requires exactly one immutable correction event")
            "RECORD_PAYMENT_METHOD_CHANGE" -> if (
                (financialEvidence.optJSONArray("payment_method_change_events")?.length() ?: 0) != 1
            ) return InboundProcessResult.Rejected("Payment-method change requires exactly one immutable event")
            "RECORD_VOID" -> if (financialEvidence.optJSONObject("void_event") == null) {
                return InboundProcessResult.Rejected("Void is missing its immutable void event")
            }
            "RECORD_REFUND" -> if (financialEvidence.optJSONObject("refund_event") == null) {
                return InboundProcessResult.Rejected("Refund is missing its immutable refund event")
            }
        }
        if ((0 until settlementsArray.length()).any {
                settlementsArray.getJSONObject(it).optString("category") == PaymentCategory.CREDIT.name
            } && !hasCreditEvents
        ) {
            return InboundProcessResult.Rejected("Credit funding/reversal is missing credit-ledger evidence")
        }
        if ((0 until settlementsArray.length()).any {
                settlementsArray.getJSONObject(it).optString("category") == PaymentCategory.COUPON.name
            } && !hasCouponEvents
        ) {
            return InboundProcessResult.Rejected("Coupon funding/reversal is missing coupon-ledger evidence")
        }

        when (opTypeStr) {
            "RECORD_REFUND" -> {
                val refundEvent = financialEvidence.optJSONObject("refund_event")
                    ?: return InboundProcessResult.Rejected("Refund is missing its immutable refund event")
                val refundAmount = refundEvent.optLong("refund_amount_minor", 0L)
                val refundTransactionId = refundEvent.optString("transaction_id")
                val transactionId = tJson.optString("id")
                if (refundAmount <= 0L || refundTransactionId != transactionId) {
                    return InboundProcessResult.Rejected("Refund event amount or transaction reference is invalid")
                }

                val transactionDao = database.transactionDao()
                val priorVoid = transactionDao.getVoidEvent(transactionId)
                if (priorVoid != null) {
                    return InboundProcessResult.Rejected("A voided transaction cannot accept another refund")
                }
                val priorRefunds = transactionDao.getRefundEvents(transactionId)
                val corrections = transactionDao.getAmountCorrections(transactionId)
                val correctedTotal = corrections.fold(grandTotalMinor) { total, correction ->
                    Math.addExact(
                        total,
                        Math.subtractExact(correction.correctedAmountMinor, correction.previousAmountMinor)
                    )
                }
                val priorRefundTotal = priorRefunds.fold(0L) { total, refund ->
                    Math.addExact(total, refund.refundAmountMinor)
                }
                if (correctedTotal <= 0L || Math.addExact(priorRefundTotal, refundAmount) > correctedTotal) {
                    return InboundProcessResult.Rejected("Refund exceeds the remaining refundable transaction amount")
                }

                if (settlementSum == Math.negateExact(refundAmount)) {
                    // Ordinary refund and partially repaid credit-refund flows
                    // must be fully represented by their signed outflow settlements.
                } else {
                    // An entirely unpaid credit sale has no external funds to pay
                    // out. Its refund is valid only as an exact, immutable credit
                    // obligation reduction. Never fabricate cash/coupon settlements.
                    val customerId = tJson.optString("customer_id")
                    val originalSettlements = transactionDao.getSettlementsForTransaction(transactionId)
                    val wasCreditFunded = originalSettlements.any {
                        it.entryPurpose == EntryPurpose.SALE_FUNDING &&
                            it.category == PaymentCategory.CREDIT &&
                            it.amountSignedMinor > 0L
                    }
                    val creditEvents = financialEvidence.optJSONArray("credit_ledger_events")
                        ?: org.json.JSONArray()
                    val matchingAdjustments = (0 until creditEvents.length())
                        .map { creditEvents.getJSONObject(it) }
                        .filter {
                            it.optString("operation_id") == opId &&
                                it.optString("type") == "ADJUSTMENT" &&
                                it.optString("transaction_id") == transactionId &&
                                it.optString("customer_id") == customerId &&
                                it.optLong("amount_signed_minor", 0L) < 0L
                        }
                    val creditAdjustmentTotal = matchingAdjustments.fold(0L) { total, event ->
                        Math.addExact(total, event.optLong("amount_signed_minor", 0L))
                    }
                    val couponEvents = financialEvidence.optJSONArray("coupon_ledger_events")
                    val hasCouponCredit = couponEvents != null && (0 until couponEvents.length()).any {
                        couponEvents.getJSONObject(it).optLong("amount_signed_minor", 0L) > 0L
                    }
                    if (
                        settlementSum != 0L ||
                        !wasCreditFunded ||
                        customerId.isBlank() ||
                        creditEvents.length() != 1 ||
                        matchingAdjustments.size != 1 ||
                        creditAdjustmentTotal != Math.negateExact(refundAmount) ||
                        hasCouponCredit
                    ) {
                        return InboundProcessResult.Rejected(
                            "Refund event amount does not match settlements or a verified unpaid-credit reduction"
                        )
                    }
                }
            }
            "RECORD_VOID" -> {
                val voidEvent = financialEvidence.optJSONObject("void_event")
                    ?: return InboundProcessResult.Rejected("Void is missing its immutable void event")
                val transactionId = tJson.optString("id")
                if (voidEvent.optString("transaction_id") != transactionId) {
                    return InboundProcessResult.Rejected("Void event references a different transaction")
                }
                val transactionDao = database.transactionDao()
                if (transactionDao.getVoidEvent(transactionId) != null) {
                    return InboundProcessResult.Rejected("Transaction already has a void event")
                }
                val priorRefunds = transactionDao.getRefundEvents(transactionId)
                val corrections = transactionDao.getAmountCorrections(transactionId)
                val correctedTotal = corrections.fold(grandTotalMinor) { total, correction ->
                    Math.addExact(
                        total,
                        Math.subtractExact(correction.correctedAmountMinor, correction.previousAmountMinor)
                    )
                }
                val priorRefundTotal = priorRefunds.fold(0L) { total, refund ->
                    Math.addExact(total, refund.refundAmountMinor)
                }
                val remainingReversible = Math.subtractExact(correctedTotal, priorRefundTotal)
                if (remainingReversible <= 0L || settlementSum != Math.negateExact(remainingReversible)) {
                    return InboundProcessResult.Rejected(
                        "Void reversal settlements do not match the remaining amount after prior refunds and corrections"
                    )
                }
            }
            "RECORD_AMOUNT_CORRECTION" -> {
                val event = financialEvidence.optJSONArray("amount_correction_events")?.optJSONObject(0)
                    ?: return InboundProcessResult.Rejected("Amount correction event is missing")
                val expectedDelta = try {
                    Math.subtractExact(
                        event.getLong("corrected_amount_minor"),
                        event.getLong("previous_amount_minor")
                    )
                } catch (_: ArithmeticException) {
                    return InboundProcessResult.Rejected("Amount correction delta overflows the supported money range")
                }
                if (expectedDelta == 0L || settlementSum != expectedDelta) {
                    return InboundProcessResult.Rejected("Amount correction delta does not match signed settlements")
                }
            }
            "RECORD_PAYMENT_METHOD_CHANGE" -> {
                val event = financialEvidence.optJSONArray("payment_method_change_events")
                    ?.optJSONObject(0)
                    ?: return InboundProcessResult.Rejected("Payment-method change event is missing")
                val previousMethod = runCatching {
                    PaymentCategory.valueOf(event.getString("previous_method"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Payment-method change previous category is invalid")
                }
                val previousChannel = runCatching {
                    PaymentChannel.valueOf(event.getString("previous_channel"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Payment-method change previous channel is invalid")
                }
                val newMethod = runCatching {
                    PaymentCategory.valueOf(event.getString("new_method"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Payment-method change new category is invalid")
                }
                val newChannel = runCatching {
                    PaymentChannel.valueOf(event.getString("new_channel"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Payment-method change new channel is invalid")
                }
                if (
                    event.optString("operation_id") != opId ||
                    event.optString("transaction_id") != tJson.optString("id") ||
                    event.optString("actor_user_id") != actorUserId ||
                    (previousMethod == newMethod && previousChannel == newChannel)
                ) {
                    return InboundProcessResult.Rejected("Payment-method change event identity or classification is invalid")
                }
                val oldRows = (0 until settlementsArray.length())
                    .map { settlementsArray.getJSONObject(it) }
                    .filter {
                        it.optString("category") == previousMethod.name &&
                            it.optString("channel") == previousChannel.name &&
                            it.optLong("amount_signed_minor", 0L) < 0L &&
                            it.optString("actor_user_id") == actorUserId
                    }
                val newRows = (0 until settlementsArray.length())
                    .map { settlementsArray.getJSONObject(it) }
                    .filter {
                        it.optString("category") == newMethod.name &&
                            it.optString("channel") == newChannel.name &&
                            it.optLong("amount_signed_minor", 0L) > 0L &&
                            it.optString("actor_user_id") == actorUserId
                    }
                if (settlementsArray.length() != 2 || oldRows.size != 1 || newRows.size != 1) {
                    return InboundProcessResult.Rejected(
                        "Payment-method change requires one old-channel reversal and one new-channel funding entry"
                    )
                }
                val reversedAmount = try {
                    Math.negateExact(oldRows.single().getLong("amount_signed_minor"))
                } catch (_: ArithmeticException) {
                    return InboundProcessResult.Rejected("Payment-method change amount overflows the supported money range")
                }
                if (reversedAmount <= 0L || newRows.single().getLong("amount_signed_minor") != reversedAmount) {
                    return InboundProcessResult.Rejected(
                        "Payment-method change reversal and replacement funding amounts do not match"
                    )
                }
            }
            "RECORD_COUPON_PURCHASE" -> {
                val wallets = financialEvidence.optJSONArray("coupon_wallets") ?: org.json.JSONArray()
                val couponEvents = financialEvidence.optJSONArray("coupon_ledger_events") ?: org.json.JSONArray()
                val purchaseEvents = (0 until couponEvents.length())
                    .map { couponEvents.getJSONObject(it) }
                    .filter { it.optString("type") == "PURCHASE" }
                if (wallets.length() != 1 || purchaseEvents.size != 1) {
                    return InboundProcessResult.Rejected(
                        "Coupon purchase requires one wallet snapshot and one purchase ledger event"
                    )
                }
                val wallet = wallets.getJSONObject(0)
                val purchase = purchaseEvents.single()
                val customerId = tJson.optString("customer_id")
                val purchaseAmount = purchase.optLong("amount_signed_minor", 0L)
                if (
                    customerId.isBlank() ||
                    wallet.optString("id").isBlank() ||
                    wallet.optString("customer_id") != customerId ||
                    wallet.optString("currency_code") != txCurrencyCode ||
                    !wallet.has("is_active") || wallet.isNull("is_active") || !wallet.optBoolean("is_active", false) ||
                    purchase.optString("id").isBlank() ||
                    purchase.optString("operation_id") != opId ||
                    purchase.optString("wallet_id") != wallet.optString("id") ||
                    purchase.optString("transaction_id") != tJson.optString("id") ||
                    purchase.optString("currency_code") != txCurrencyCode ||
                    purchaseAmount <= 0L ||
                    purchaseAmount != grandTotalMinor ||
                    purchase.optLong("occurred_at", Long.MIN_VALUE) != jsonPayload.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected(
                        "Coupon purchase wallet and purchase ledger evidence do not match the transaction"
                    )
                }
                if (purchaseAmount != grandTotalMinor) {
                    return InboundProcessResult.Rejected("Coupon purchase ledger amount does not match purchase total")
                }
            }
        }

        // d) actor user and branch rows exist locally or are created in the same transaction
        return database.withTransaction {
            CustomerIdentityReconciler.reconcileEvidenceCustomers(
                database, sessionBusinessId,
                jsonPayload.optJSONObject("financial_evidence") ?: JSONObject()
            )
            val operationDao = database.operationDao()
            val transactionDao = database.transactionDao()
            val occurredAt = java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()

            val txId = tJson.getString("id")
            val resultReference = resultReferenceFor(opTypeStr, financialEvidence, txId)

            // Insert downloaded operations as SUCCEEDED without firing the local
            // outbox trigger. A pre-existing PENDING operation is finalized later
            // through the shared acknowledged-outbox protocol.
            val operation = OperationEntity(
                id = opId,
                businessId = sessionBusinessId,
                branchId = branchId,
                operationType = OperationType.valueOf(opTypeStr),
                subjectType = jsonPayload.optString("subject_type").ifBlank { SubjectType.TRANSACTION.name }
                    .let { SubjectType.valueOf(it) },
                subjectId = jsonPayload.optString("subject_id").ifBlank { txId },
                transactionId = txId,
                cashShiftId = jsonPayload.optString("cash_shift_id").ifBlank { null },
                actorUserId = actorUserId,
                deviceId = envelope.payloadJson.optString("device_id", "remote"),
                occurredAt = occurredAt,
                requestFingerprint = envelope.payloadJson.optString("request_fingerprint", "remote-$opId"),
                commandSchemaVersion = envelope.payloadJson.optInt("command_schema_version", 1),
                intentPayload = envelope.payloadJson.optString("intent_payload", "{}"),
                status = OperationStatus.SUCCEEDED,
                resultReferenceType = resultReference.first,
                resultReferenceId = resultReference.second
            )
            val admission = InboundOperationCompletion.ensure(database, operation)
            if (admission == InboundOperationAdmission.ALREADY_SUCCEEDED) {
                return@withTransaction InboundProcessResult.Skipped
            }

            // 1. Transaction
            val tEntity = TransactionEntity(
                id = txId,
                branchId = branchId,
                type = TransactionType.valueOf(txType),
                customerId = tJson.optString("customer_id").ifBlank { null }?.let {
                    CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(database, sessionBusinessId, it)
                },
                subtotalMinor = tJson.getLong("subtotal_minor"),
                taxRateBps = tJson.getInt("tax_rate_bps"),
                taxableBaseMinor = tJson.getLong("taxable_base_minor"),
                taxAmountMinor = tJson.getLong("tax_amount_minor"),
                txShippingMinor = tJson.optLong("tx_shipping_minor", 0L),
                txPackingMinor = tJson.optLong("tx_packing_minor", 0L),
                grandTotalMinor = grandTotalMinor,
                currencyCode = txCurrencyCode,
                currencyDecimalPlaces = tJson.getInt("currency_decimal_places"),
                createdByUserId = createdByUserId,
                deviceId = tJson.optString("device_id").ifBlank { "remote" },
                createdAt = tJson.optLong("created_at", occurredAt),
                description = tJson.optString("description").ifBlank { null },
                displayRef = tJson.getString("display_ref")
            )
            val existingTransaction = transactionDao.getTransactionById(txId)
            if (existingTransaction == null) {
                transactionDao.insertTransaction(tEntity)
            } else {
                check(existingTransaction == tEntity) {
                    "Original transaction snapshot conflicts with the existing immutable transaction"
                }
            }

            // 2. Transaction Items
            if (jsonPayload.has("items") && !jsonPayload.isNull("items")) {
                val itemsArray = jsonPayload.getJSONArray("items")
                val itemsList = mutableListOf<TransactionItemEntity>()
                for (i in 0 until itemsArray.length()) {
                    val itemJson = itemsArray.getJSONObject(i)
                    itemsList.add(
                        TransactionItemEntity(
                            id = itemJson.getString("id"),
                            transactionId = itemJson.getString("transaction_id"),
                            productId = itemJson.optString("product_id").ifBlank { null },
                            productNameSnapshot = itemJson.getString("product_name_snapshot"),
                            descriptionSnapshot = itemJson.optString("description_snapshot").ifBlank { null },
                            quantity = itemJson.getInt("quantity"),
                            unitPriceMinor = itemJson.getLong("unit_price_minor"),
                            lineTotalMinor = itemJson.getLong("line_total_minor"),
                            barcodeSnapshot = itemJson.optString("barcode_snapshot").ifBlank { null },
                            mediaId = itemJson.optString("media_id").ifBlank { null },
                            itemPackingChargeMinor = itemJson.optLong("item_packing_charge_minor", 0L),
                            itemShippingChargeMinor = itemJson.optLong("item_shipping_charge_minor", 0L)
                        )
                    )
                }
                check(itemsList.all { it.transactionId == txId && it.quantity > 0 && it.lineTotalMinor >= 0L }) {
                    "Transaction item references or amounts are invalid"
                }
                check(itemsList.map { it.id }.distinct().size == itemsList.size) {
                    "Transaction contains duplicate item IDs"
                }
                val existingItems = transactionDao.getItemsForTransaction(txId).associateBy { it.id }
                for (incoming in itemsList) {
                    val existing = existingItems[incoming.id]
                    if (existing != null) {
                        check(existing == incoming) {
                            "Transaction item snapshot conflicts with existing immutable item evidence"
                        }
                    }
                }
                val newItems = itemsList.filter { it.id !in existingItems }
                if (newItems.isNotEmpty()) {
                    transactionDao.insertTransactionItems(newItems)
                }
            }

            // 3. Settlements
            val settlementsList = mutableListOf<SettlementEntryEntity>()
            for (i in 0 until settlementsArray.length()) {
                val sJson = settlementsArray.getJSONObject(i)
                settlementsList.add(
                    SettlementEntryEntity(
                        id = sJson.getString("id"),
                        operationId = sJson.getString("operation_id"),
                        subjectType = SubjectType.valueOf(sJson.getString("subject_type")),
                        subjectId = sJson.getString("subject_id"),
                        transactionId = sJson.optString("transaction_id").ifBlank { txId },
                        entryPurpose = EntryPurpose.valueOf(sJson.getString("entry_purpose")),
                        category = PaymentCategory.valueOf(sJson.getString("category")),
                        channel = PaymentChannel.valueOf(sJson.getString("channel")),
                        amountSignedMinor = sJson.getLong("amount_signed_minor"),
                        currencyCode = sJson.getString("currency_code"),
                        confirmationType = ConfirmationType.valueOf(sJson.getString("confirmation_type")),
                        qrProfileIdSnapshot = sJson.optString("qr_profile_id_snapshot").ifBlank { null },
                        occurredAt = sJson.optLong("occurred_at", occurredAt),
                        actorUserId = sJson.optString("actor_user_id").ifBlank { actorUserId },
                        providerRef = sJson.optString("provider_ref").ifBlank { null }
                    )
                )
            }
            check(settlementsList.map { it.id }.distinct().size == settlementsList.size) {
                "Operation contains duplicate settlement IDs"
            }
            val existingSettlements = transactionDao.getSettlementsForOperation(opId).associateBy { it.id }
            for (incoming in settlementsList) {
                val existing = existingSettlements[incoming.id]
                if (existing != null) {
                    check(existing == incoming) {
                        "Settlement evidence conflicts with existing immutable settlement"
                    }
                }
            }
            val newSettlements = settlementsList.filter { it.id !in existingSettlements }
            if (newSettlements.isNotEmpty()) {
                transactionDao.insertSettlements(newSettlements)
            }

            // Apply operation-specific immutable event/ledger evidence in this
            // same Room transaction. No linked financial row is a second source of truth.
            RemoteEnvelopeEvidenceApplier.apply(
                database = database,
                evidence = jsonPayload.optJSONObject("financial_evidence") ?: JSONObject(),
                operationId = opId,
                businessId = sessionBusinessId,
                branchId = branchId,
                actorUserId = actorUserId,
                transactionId = txId
            )

            // A downloaded PENDING operation must not be re-enqueued by the
            // local success trigger. Acknowledge it before the status transition.
            if (admission == InboundOperationAdmission.PENDING) {
                InboundOperationCompletion.completePending(database, operation)
            } else {
                check(operationDao.isOperationSucceeded(opId)) {
                    "Inbound transaction operation was not persisted as succeeded"
                }
            }

            InboundProcessResult.Applied
        }
    }

    private suspend fun hasActorIdentity(
        database: DmrDatabase,
        actorUserId: String,
        businessId: String,
        branchId: String,
        envelope: RemoteEnvelopeV2? = null
    ): Boolean {
        // The download RPC returns actor membership metadata from the immutable
        // server envelope row, joined to a membership in this exact business/branch.
        // Ingest validated its local_user_id against canonical actor_user_id. This
        // permits historical Admin-authored sales on Cashier devices without creating
        // a second local Admin login, while still rejecting absent/mismatched metadata.
        if (
            envelope != null &&
            !envelope.actorMembershipId.isNullOrBlank() &&
            envelope.actorLocalUserId == actorUserId &&
            (envelope.actorRole == "ADMIN" || envelope.actorRole == "CASHIER") &&
            envelope.businessId == businessId &&
            envelope.branchId == branchId
        ) return true

        // A synchronized cloud identity is authoritative when present, including
        // inactive/removed tombstones. Never let a stale local login row override revocation.
        val cloudIdentity = database.cloudStaffIdentityDao()
            .findIdentity(actorUserId, businessId, branchId)
        if (cloudIdentity != null) {
            return cloudIdentity.isActive && !cloudIdentity.isRemoved &&
                cloudIdentity.role == "CASHIER"
        }

        val localActor = database.userDao().getUserByIdInBusiness(actorUserId, businessId)
        if (localActor == null || !localActor.isActive) return false
        if (localActor.role.name == "ADMIN") return true
        return database.userDao().getMembership(actorUserId, branchId) != null
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

    private suspend fun materializeNonTransactionOperation(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        branchId: String,
        sessionBusinessId: String,
        actorUserId: String
    ): InboundProcessResult {
        val payload = envelope.payloadJson
        val operationType = runCatching { OperationType.valueOf(envelope.operationType) }
            .getOrElse { return InboundProcessResult.UnsupportedYet(envelope.operationType) }
        val evidence = payload.optJSONObject("financial_evidence") ?: JSONObject()
        val repaymentJson = evidence.optJSONObject("credit_repayment")
        val shiftJson = evidence.optJSONObject("cash_shift")
        val shiftEventJson = evidence.optJSONObject("cash_shift_event")
        val expenseSnapshot = evidence.optJSONObject("expense_snapshot")
        val expenseEvents = evidence.optJSONArray("expense_events")

        when (operationType) {
            OperationType.RECORD_CREDIT_REPAYMENT -> {
                if (repaymentJson == null) {
                    return InboundProcessResult.Rejected("Missing credit repayment evidence")
                }
                if (
                    repaymentJson.optString("operation_id") != envelope.operationId ||
                    repaymentJson.optString("actor_user_id") != actorUserId ||
                    repaymentJson.optString("customer_id").isBlank() ||
                    repaymentJson.optLong("amount_minor", 0L) <= 0L ||
                    repaymentJson.optLong("occurred_at", Long.MIN_VALUE) != payload.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Credit repayment identity or amount is invalid")
                }
                val customerId = repaymentJson.optString("customer_id")
                val customer = database.customerDao().getCustomerById(customerId)
                    ?: evidence.optJSONObject("customer")?.let { null }
                val branch = database.businessBranchDao().getBranchById(branchId)
                    ?: return InboundProcessResult.Rejected("Referenced branch is missing locally")
                if (branch.businessId != sessionBusinessId ||
                    repaymentJson.optString("currency_code") != branch.currencyCode
                ) {
                    return InboundProcessResult.Rejected("Credit repayment business, branch, or currency mismatch")
                }
                if (customer != null && customer.businessId != sessionBusinessId) {
                    return InboundProcessResult.Rejected("Credit repayment customer belongs to another business")
                }
                val repaymentId = repaymentJson.optString("id")
                val repaymentAmount = repaymentJson.optLong("amount_minor", 0L)
                if (
                    repaymentId.isBlank() ||
                    payload.optString("subject_type") != SubjectType.REPAYMENT.name ||
                    payload.optString("subject_id") != repaymentId
                ) {
                    return InboundProcessResult.Rejected("Credit repayment subject does not match its repayment evidence")
                }
                val paymentCategory = runCatching {
                    PaymentCategory.valueOf(repaymentJson.getString("payment_category"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Credit repayment payment category is invalid")
                }
                val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                if (settlements.length() != 1) {
                    return InboundProcessResult.Rejected("Credit repayment requires exactly one funding settlement")
                }
                val settlement = settlements.getJSONObject(0)
                val paymentChannel = runCatching {
                    PaymentChannel.valueOf(settlement.getString("channel"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Credit repayment settlement channel is invalid")
                }
                val validPaymentPair = when (paymentCategory) {
                    PaymentCategory.CASH -> paymentChannel == PaymentChannel.CASH_TENDER
                    PaymentCategory.ONLINE -> paymentChannel == PaymentChannel.MANUAL_QR ||
                        paymentChannel == PaymentChannel.SOFTPOS
                    PaymentCategory.COUPON -> paymentChannel == PaymentChannel.COUPON_WALLET
                    else -> false
                }
                if (
                    !validPaymentPair ||
                    settlement.optString("operation_id") != envelope.operationId ||
                    settlement.optString("subject_type") != SubjectType.REPAYMENT.name ||
                    settlement.optString("subject_id") != repaymentId ||
                    settlement.optString("transaction_id").isNotBlank() ||
                    settlement.optString("currency_code") != repaymentJson.optString("currency_code") ||
                    settlement.optString("entry_purpose") != EntryPurpose.CREDIT_REPAYMENT_COLLECTION.name ||
                    settlement.optString("category") != paymentCategory.name ||
                    settlement.optString("actor_user_id") != actorUserId ||
                    settlement.optString("confirmation_type") != ConfirmationType.CASHIER_CONFIRMED.name ||
                    settlement.optLong("amount_signed_minor", 0L) != repaymentAmount ||
                    settlement.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Credit repayment settlement does not match the repayment record")
                }
                val ledgerEvents = evidence.optJSONArray("credit_ledger_events") ?: org.json.JSONArray()
                if (ledgerEvents.length() != 1) {
                    return InboundProcessResult.Rejected("Credit repayment requires exactly one matching Credit ledger event")
                }
                val ledgerEvent = ledgerEvents.getJSONObject(0)
                val expectedNegativeAmount = try {
                    Math.negateExact(repaymentAmount)
                } catch (_: ArithmeticException) {
                    return InboundProcessResult.Rejected("Credit repayment amount overflows the supported money range")
                }
                if (
                    ledgerEvent.optString("operation_id") != envelope.operationId ||
                    ledgerEvent.optString("type") != "REPAYMENT" ||
                    ledgerEvent.optString("repayment_id") != repaymentId ||
                    ledgerEvent.optString("transaction_id").isNotBlank() ||
                    ledgerEvent.optString("customer_id") != customerId ||
                    ledgerEvent.optString("currency_code") != repaymentJson.optString("currency_code") ||
                    ledgerEvent.optLong("amount_signed_minor", 0L) != expectedNegativeAmount ||
                    ledgerEvent.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Credit repayment is missing its exact negative ledger event")
                }
                val allocations = evidence.optJSONArray("credit_repayment_allocations") ?: org.json.JSONArray()
                if (allocations.length() == 0) {
                    return InboundProcessResult.Rejected("Credit repayment is missing FIFO allocation evidence")
                }
                var allocatedTotal = 0L
                val allocationIds = mutableSetOf<String>()
                val allocatedTransactions = mutableSetOf<String>()
                for (i in 0 until allocations.length()) {
                    val allocation = allocations.getJSONObject(i)
                    val allocationId = allocation.optString("id")
                    val originalTransactionId = allocation.optString("original_transaction_id")
                    val allocatedAmount = allocation.optLong("allocated_amount_minor", 0L)
                    if (
                        allocation.optString("repayment_id") != repaymentId ||
                        allocationId.isBlank() ||
                        originalTransactionId.isBlank() ||
                        allocatedAmount <= 0L ||
                        allocation.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE) ||
                        !allocationIds.add(allocationId) ||
                        !allocatedTransactions.add(originalTransactionId)
                    ) {
                        return InboundProcessResult.Rejected("Credit repayment FIFO allocation identity or amount is invalid")
                    }
                    allocatedTotal = Math.addExact(allocatedTotal, allocatedAmount)
                }
                if (allocatedTotal != repaymentAmount) {
                    return InboundProcessResult.Rejected("Credit repayment FIFO allocations do not equal the repayment amount")
                }
                val couponEvents = evidence.optJSONArray("coupon_ledger_events") ?: org.json.JSONArray()
                if (paymentCategory == PaymentCategory.COUPON) {
                    val wallets = evidence.optJSONArray("coupon_wallets") ?: org.json.JSONArray()
                    if (wallets.length() != 1 || couponEvents.length() != 1) {
                        return InboundProcessResult.Rejected(
                            "Coupon-funded Credit repayment requires one wallet and one redemption event"
                        )
                    }
                    val wallet = wallets.getJSONObject(0)
                    val couponEvent = couponEvents.getJSONObject(0)
                    if (
                        wallet.optString("id").isBlank() ||
                        wallet.optString("customer_id") != customerId ||
                        wallet.optString("currency_code") != repaymentJson.optString("currency_code") ||
                        !wallet.has("is_active") || wallet.isNull("is_active") || !wallet.optBoolean("is_active", false) ||
                        couponEvent.optString("id").isBlank() ||
                        couponEvent.optString("operation_id") != envelope.operationId ||
                        couponEvent.optString("wallet_id") != wallet.optString("id") ||
                        couponEvent.optString("type") != "REDEMPTION" ||
                        couponEvent.optLong("amount_signed_minor", 0L) != expectedNegativeAmount ||
                        couponEvent.optString("currency_code") != repaymentJson.optString("currency_code") ||
                        couponEvent.optString("transaction_id").isNotBlank() ||
                        couponEvent.optString("authorized_user_id").isBlank() ||
                        couponEvent.optString("authorized_user_name_snapshot").isBlank() ||
                        couponEvent.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
                    ) {
                        return InboundProcessResult.Rejected(
                            "Coupon-funded Credit repayment is missing its exact wallet redemption evidence"
                        )
                    }
                } else if (couponEvents.length() != 0) {
                    return InboundProcessResult.Rejected(
                        "Cash/Online Credit repayment cannot carry Coupon redemption evidence"
                    )
                }
            }
            OperationType.OPEN_CASH_SHIFT,
            OperationType.COUNT_CASH_SHIFT,
            OperationType.CLOSE_CASH_SHIFT -> {
                if (shiftJson == null || shiftEventJson == null) {
                    return InboundProcessResult.Rejected("Missing cash-shift identity/event evidence")
                }
                val expectedEvent = when (operationType) {
                    OperationType.OPEN_CASH_SHIFT -> CashShiftEventType.OPENED
                    OperationType.COUNT_CASH_SHIFT -> CashShiftEventType.COUNTED
                    else -> CashShiftEventType.CLOSED
                }
                if (
                    shiftJson.optString("branch_id") != branchId ||
                    shiftEventJson.optString("operation_id") != envelope.operationId ||
                    shiftEventJson.optString("type") != expectedEvent.name ||
                    shiftEventJson.optString("actor_user_id") != actorUserId
                ) {
                    return InboundProcessResult.Rejected("Cash-shift operation/event identity mismatch")
                }
                val expectedShiftAmount = shiftEventJson.optLong("amount_minor", Long.MIN_VALUE)
                val expectedShiftOccurredAt = payload.optLong("occurred_at", Long.MAX_VALUE)
                if (
                    shiftEventJson.optString("id") != envelope.operationId + ":" + expectedEvent.name ||
                    shiftEventJson.optLong("occurred_at", Long.MIN_VALUE) != expectedShiftOccurredAt ||
                    expectedShiftAmount < 0L ||
                    (operationType == OperationType.OPEN_CASH_SHIFT && expectedShiftAmount != 0L) ||
                    (operationType == OperationType.CLOSE_CASH_SHIFT && expectedShiftAmount != 0L) ||
                    (operationType == OperationType.OPEN_CASH_SHIFT &&
                        shiftJson.optString("user_id") != actorUserId)
                ) {
                    return InboundProcessResult.Rejected("Cash-shift lifecycle amount, actor, or event identity is invalid")
                }
                val shiftId = shiftJson.optString("id")
                if (
                    shiftId.isBlank() ||
                    payload.optString("subject_type") != SubjectType.CASH_SHIFT.name ||
                    payload.optString("subject_id") != shiftId ||
                    shiftEventJson.optString("shift_id") != shiftId
                ) {
                    return InboundProcessResult.Rejected(
                        "Cash-shift event identity must match the authoritative envelope subject"
                    )
                }
                if ((payload.optJSONArray("settlements")?.length() ?: 0) != 0) {
                    return InboundProcessResult.Rejected("Cash-shift lifecycle operations cannot carry settlements")
                }
            }
            OperationType.RECORD_EXPENSE_CORRECTION,
            OperationType.RECORD_EXPENSE_VOID -> {
                if (expenseSnapshot == null || expenseEvents == null || expenseEvents.length() == 0) {
                    return InboundProcessResult.Rejected("Missing linked expense event evidence")
                }
                if (
                    expenseSnapshot.optString("business_id") != sessionBusinessId ||
                    expenseSnapshot.optString("branch_id") != branchId
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle evidence belongs to another tenant or branch")
                }
                val operationEvents = (0 until expenseEvents.length())
                    .map { expenseEvents.getJSONObject(it) }
                    .filter { it.optString("operation_id") == envelope.operationId }
                if (operationEvents.size != 1) {
                    return InboundProcessResult.Rejected("Expense lifecycle operation must have exactly one event")
                }
                val expenseId = expenseSnapshot.optString("id")
                if (
                    expenseId.isBlank() ||
                    payload.optString("subject_type") != SubjectType.EXPENSE.name ||
                    payload.optString("subject_id") != expenseId
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle subject metadata mismatch")
                }
                val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                if (settlements.length() != 1) {
                    return InboundProcessResult.Rejected(
                        "Expense lifecycle operation requires exactly one signed settlement"
                    )
                }
                val expectedCategory = runCatching {
                    PaymentCategory.valueOf(expenseSnapshot.optString("payment_category"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Expense lifecycle payment category is invalid")
                }
                val row = settlements.getJSONObject(0)
                val rowChannel = runCatching {
                    PaymentChannel.valueOf(row.optString("channel"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Expense lifecycle settlement channel is invalid")
                }
                val validChannel = when (expectedCategory) {
                    PaymentCategory.CASH -> rowChannel == PaymentChannel.CASH_TENDER
                    PaymentCategory.ONLINE -> rowChannel == PaymentChannel.MANUAL_QR ||
                        rowChannel == PaymentChannel.SOFTPOS
                    else -> false
                }
                val expectedPurpose = if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    EntryPurpose.VOID_REVERSAL.name
                } else {
                    EntryPurpose.EXPENSE_CORRECTION.name
                }
                if (
                    !validChannel ||
                    row.optString("operation_id") != envelope.operationId ||
                    row.optString("subject_type") != SubjectType.EXPENSE.name ||
                    row.optString("subject_id") != expenseId ||
                    row.optString("transaction_id").isNotBlank() ||
                    row.optString("currency_code") != expenseSnapshot.optString("currency_code") ||
                    row.optString("actor_user_id") != actorUserId ||
                    row.optString("entry_purpose") != expectedPurpose ||
                    row.optString("category") != expectedCategory.name ||
                    row.optString("confirmation_type") != ConfirmationType.CASHIER_CONFIRMED.name ||
                    row.optLong("occurred_at", Long.MIN_VALUE) != envelope.payloadJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle settlement identity mismatch")
                }
                val total = row.getLong("amount_signed_minor")
                val event = operationEvents.single()
                val expectedEventType = if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    "VOIDED"
                } else {
                    "CORRECTED"
                }
                if (
                    event.optString("id").isBlank() ||
                    event.optString("expense_id") != expenseId ||
                    event.optString("operation_id") != envelope.operationId ||
                    event.optString("actor_user_id") != actorUserId ||
                    event.optString("event_type") != expectedEventType ||
                    event.optLong("occurred_at", Long.MIN_VALUE) != envelope.payloadJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected(
                        "Expense lifecycle event must match the exact expense, operation, actor, and event type"
                    )
                }
                if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    val amountBeingVoided = event.optLong("previous_amount_minor", 0L)
                    if (amountBeingVoided <= 0L || total != amountBeingVoided) {
                        return InboundProcessResult.Rejected("Expense void settlement does not reverse the effective expense outflow")
                    }
                } else {
                    if (
                        !event.has("previous_amount_minor") || event.isNull("previous_amount_minor") ||
                        !event.has("new_amount_minor") || event.isNull("new_amount_minor")
                    ) {
                        return InboundProcessResult.Rejected("Expense correction is missing its before/after amount evidence")
                    }
                    val previousAmount = event.getLong("previous_amount_minor")
                    val newAmount = event.getLong("new_amount_minor")
                    if (previousAmount <= 0L || newAmount <= 0L || previousAmount == newAmount) {
                        return InboundProcessResult.Rejected("Expense correction before/after amounts are invalid")
                    }
                    val expectedDelta = Math.subtractExact(previousAmount, newAmount)
                    if (total != expectedDelta) {
                        return InboundProcessResult.Rejected("Expense correction delta does not match signed settlements")
                    }
                }
            }
            else -> return InboundProcessResult.UnsupportedYet(envelope.operationType)
        }

        return try {
            database.withTransaction {
                val operationDao = database.operationDao()
                val subjectType = payload.optString("subject_type").ifBlank {
                    when (operationType) {
                        OperationType.RECORD_CREDIT_REPAYMENT -> SubjectType.REPAYMENT.name
                        OperationType.OPEN_CASH_SHIFT,
                        OperationType.COUNT_CASH_SHIFT,
                        OperationType.CLOSE_CASH_SHIFT -> SubjectType.CASH_SHIFT.name
                        else -> SubjectType.EXPENSE.name
                    }
                }.let { SubjectType.valueOf(it) }
                val subjectId = payload.optString("subject_id").ifBlank {
                    repaymentJson?.optString("id")
                        ?: shiftJson?.optString("id")
                        ?: expenseSnapshot?.optString("id")
                        ?: envelope.operationId
                }
                val fingerprint = payload.optString("request_fingerprint")
                val occurredAt = java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()
                val isCashShiftLifecycle = operationType == OperationType.OPEN_CASH_SHIFT ||
                    operationType == OperationType.COUNT_CASH_SHIFT ||
                    operationType == OperationType.CLOSE_CASH_SHIFT
                val cashShiftId = if (isCashShiftLifecycle) {
                    null
                } else {
                    payload.optString("cash_shift_id").ifBlank { null }
                }
                val operation = OperationEntity(
                    id = envelope.operationId,
                    businessId = sessionBusinessId,
                    branchId = branchId,
                    operationType = operationType,
                    subjectType = subjectType,
                    subjectId = subjectId,
                    transactionId = null,
                    cashShiftId = cashShiftId,
                    actorUserId = actorUserId,
                    deviceId = payload.optString("device_id").ifBlank { "remote" },
                    occurredAt = occurredAt,
                    requestFingerprint = fingerprint,
                    commandSchemaVersion = payload.optInt("command_schema_version", 1),
                    intentPayload = payload.optString("intent_payload", "{}"),
                    status = OperationStatus.SUCCEEDED,
                    resultReferenceType = subjectType.name,
                    resultReferenceId = subjectId
                )
                val admission = InboundOperationCompletion.ensure(database, operation)
                if (admission == InboundOperationAdmission.ALREADY_SUCCEEDED) {
                    return@withTransaction InboundProcessResult.Skipped
                }

                if (shiftJson != null && shiftEventJson != null) {
                    val shiftId = shiftJson.getString("id")
                    val priorEvents = database.cashShiftDao().getEventsForShift(shiftId)
                        .filter { it.operationId != envelope.operationId }
                    val existingShiftEvent = database.cashShiftDao().getEventByOperationId(envelope.operationId)
                    if (existingShiftEvent != null && (
                            existingShiftEvent.id != shiftEventJson.optString("id") ||
                                existingShiftEvent.shiftId != shiftId ||
                                existingShiftEvent.type.name != shiftEventJson.optString("type") ||
                                existingShiftEvent.amountMinor != shiftEventJson.optLong("amount_minor") ||
                                existingShiftEvent.occurredAt != payload.optLong("occurred_at", Long.MAX_VALUE) ||
                                existingShiftEvent.actorUserId != actorUserId
                        )
                    ) {
                        error("Existing cash-shift event conflicts with the inbound lifecycle intent")
                    }
                    val hasOpened = priorEvents.any { it.type == CashShiftEventType.OPENED }
                    val hasCounted = priorEvents.any { it.type == CashShiftEventType.COUNTED }
                    val hasClosed = priorEvents.any { it.type == CashShiftEventType.CLOSED }
                    when (operationType) {
                        OperationType.OPEN_CASH_SHIFT -> check(!hasOpened && !hasClosed) {
                            "Cash shift already has a conflicting lifecycle history"
                        }
                        OperationType.COUNT_CASH_SHIFT -> check(hasOpened && !hasClosed) {
                            "Cash shift must be open and not closed before counting"
                        }
                        OperationType.CLOSE_CASH_SHIFT -> check(hasOpened && hasCounted && !hasClosed) {
                            "Cash shift must be opened and counted before closing"
                        }
                        else -> Unit
                    }
                }

                val settlementsArray = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                val settlementRows = (0 until settlementsArray.length()).map { i ->
                    val row = settlementsArray.getJSONObject(i)
                    SettlementEntryEntity(
                        id = row.getString("id"),
                        operationId = row.getString("operation_id"),
                        subjectType = SubjectType.valueOf(row.getString("subject_type")),
                        subjectId = row.getString("subject_id"),
                        transactionId = row.optString("transaction_id").ifBlank { null },
                        entryPurpose = EntryPurpose.valueOf(row.getString("entry_purpose")),
                        category = PaymentCategory.valueOf(row.getString("category")),
                        channel = PaymentChannel.valueOf(row.getString("channel")),
                        amountSignedMinor = row.getLong("amount_signed_minor"),
                        currencyCode = row.getString("currency_code"),
                        confirmationType = ConfirmationType.valueOf(row.getString("confirmation_type")),
                        qrProfileIdSnapshot = row.optString("qr_profile_id_snapshot").ifBlank { null },
                        occurredAt = row.optLong("occurred_at", occurredAt),
                        actorUserId = row.optString("actor_user_id").ifBlank { actorUserId },
                        providerRef = row.optString("provider_ref").ifBlank { null }
                    )
                }
                val existingSettlements = database.transactionDao()
                    .getSettlementsForOperation(envelope.operationId)
                if (existingSettlements.isEmpty() && settlementRows.isNotEmpty()) {
                    database.transactionDao().insertSettlements(settlementRows)
                } else if (settlementRows.isNotEmpty()) {
                    check(existingSettlements.toSet() == settlementRows.toSet()) {
                        "Inbound settlement evidence conflicts with local history"
                    }
                }

                RemoteEnvelopeEvidenceApplier.apply(
                    database = database,
                    evidence = evidence,
                    operationId = envelope.operationId,
                    businessId = sessionBusinessId,
                    branchId = branchId,
                    actorUserId = actorUserId,
                    transactionId = null
                )

                if (admission == InboundOperationAdmission.PENDING) {
                    InboundOperationCompletion.completePending(database, operation)
                } else {
                    check(operationDao.isOperationSucceeded(envelope.operationId)) {
                        "Inbound non-transaction operation was not persisted as succeeded"
                    }
                }
                InboundProcessResult.Applied
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            InboundProcessResult.Rejected(
                "Unable to materialize ${envelope.operationType}: ${error.message ?: "integrity validation failed"}"
            )
        }
    }


}
