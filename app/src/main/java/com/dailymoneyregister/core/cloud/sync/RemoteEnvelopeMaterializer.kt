package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.InboundEnvelopeAuditEventEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
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

    suspend fun materializeInboundEnvelope(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        sessionBusinessId: String,
        sessionBranchId: String?
    ): InboundProcessResult {
        val opId = envelope.operationId
        val opTypeStr = envelope.operationType

        val jsonPayload = envelope.payloadJson
        val envelopeDigest = envelope.canonicalSha256
            ?.takeIf { it.isNotBlank() }
            ?: return InboundProcessResult.Rejected("Canonical envelope digest is missing")
        if (!envelopeDigest.equals(CommandFingerprint.sha256(envelope.canonicalText), ignoreCase = true)) {
            return InboundProcessResult.Rejected("Canonical envelope digest does not match canonical text")
        }

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
        if (existingOp != null && (
                existingOp.businessId != envelope.businessId ||
                    existingOp.branchId != branchId ||
                    existingOp.operationType.name != opTypeStr ||
                    existingOp.requestFingerprint != incomingFingerprint
            )
        ) {
            return InboundProcessResult.Rejected(
                "Operation ID collision: existing operation identity does not match inbound envelope"
            )
        }
        val appliedDigest = database.inboundEnvelopeAuditDao().appliedDigest(opId)
        if (appliedDigest != null) {
            if (!appliedDigest.equals(envelopeDigest, ignoreCase = true)) {
                return InboundProcessResult.Rejected(
                    "Operation ID replay conflicts with the previously applied canonical digest"
                )
            }
            if (existingOp?.status != OperationStatus.SUCCEEDED) {
                return InboundProcessResult.Rejected(
                    "Applied digest receipt exists without a succeeded local operation"
                )
            }
            return InboundProcessResult.Skipped
        }
        var replayDetected = existingOp?.status == OperationStatus.SUCCEEDED

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
            return materializeEnvelope(database, jsonPayload, envelope, branchId)
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
            jsonPayload.optString("subject_type").isNotBlank() &&
                jsonPayload.optString("subject_type") != SubjectType.TRANSACTION.name ||
            jsonPayload.optString("subject_id").isNotBlank() &&
                jsonPayload.optString("subject_id") != tJson.getString("id") ||
            jsonPayload.optString("transaction_id").isNotBlank() &&
                jsonPayload.optString("transaction_id") != tJson.getString("id")
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
            val settlementActor = settlement.optString("actor_user_id").ifBlank { actorUserId }
            if (!hasActorIdentity(database, settlementActor, sessionBusinessId, branchId, envelope)) {
                return InboundProcessResult.Rejected(
                    "Referenced settlement user '$settlementActor' is missing from synchronized staff identities"
                )
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
                val refundAmount = financialEvidence.optJSONObject("refund_event")?.optLong("refund_amount_minor", 0L) ?: 0L
                if (refundAmount <= 0L || settlementSum != -refundAmount) {
                    return InboundProcessResult.Rejected("Refund event amount does not match its signed outflow settlements")
                }
            }
            "RECORD_VOID" -> {
                // A void reverses only the currently reversible remainder, not
                // necessarily the original sale total (prior refunds/corrections
                // may already have reduced it). Replays are validated against the
                // persisted event/settlement snapshots inside the atomic writer.
                if (existingOp?.status != OperationStatus.SUCCEEDED) {
                    val originalRecord = database.transactionDao()
                        .getFinancialRecordsByIds(listOf(tJson.getString("id")))
                        .singleOrNull()
                        ?: return InboundProcessResult.Rejected(
                            "Void cannot be applied before its original transaction evidence exists"
                        )
                    val currentView = com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
                        .resolve(originalRecord)
                    val reversibleMinor = Math.subtractExact(
                        currentView.currentAmount.amount,
                        currentView.refundedAmount.amount
                    )
                    if (reversibleMinor <= 0L || settlementSum != Math.negateExact(reversibleMinor)) {
                        return InboundProcessResult.Rejected(
                            "Void reversal settlements do not equal the remaining reversible amount"
                        )
                    }
                }
            }
            "RECORD_AMOUNT_CORRECTION" -> {
                val event = financialEvidence.optJSONArray("amount_correction_events")?.optJSONObject(0)
                    ?: return InboundProcessResult.Rejected("Amount correction event is missing")
                val expectedDelta = event.optLong("corrected_amount_minor", Long.MIN_VALUE) -
                    event.optLong("previous_amount_minor", Long.MAX_VALUE)
                if (expectedDelta == 0L || settlementSum != expectedDelta) {
                    return InboundProcessResult.Rejected("Amount correction delta does not match signed settlements")
                }
            }
            "RECORD_PAYMENT_METHOD_CHANGE" -> if (settlementSum != 0L) {
                return InboundProcessResult.Rejected("Payment-method reclassification settlements must net to zero")
            }
            "RECORD_COUPON_PURCHASE" -> {
                val couponTotal = (financialEvidence.optJSONArray("coupon_ledger_events") ?: org.json.JSONArray())
                    .let { events ->
                        (0 until events.length())
                            .map { events.getJSONObject(it) }
                            .filter { it.optString("type") == "PURCHASE" }
                            .sumOf { it.optLong("amount_signed_minor", 0L) }
                    }
                if (couponTotal != grandTotalMinor) {
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
            val persistedOperation = operationDao.getOperationById(opId)
            if (persistedOperation != null) {
                check(
                    persistedOperation.businessId == envelope.businessId &&
                        persistedOperation.branchId == branchId &&
                        persistedOperation.operationType.name == opTypeStr &&
                        persistedOperation.requestFingerprint == incomingFingerprint
                ) { "Operation ID collision during atomic materialization" }
                replayDetected = persistedOperation.status == OperationStatus.SUCCEEDED
            }
            val occurredAt = java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()

            val txId = tJson.getString("id")

            // Insert directly as SUCCEEDED. The outbox trigger only observes local
            // lifecycle UPDATE transitions, so downloaded evidence cannot be requeued.
            operationDao.insertOperationIfAbsent(
                OperationEntity(
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
                    resultReferenceType = "TRANSACTION",
                    resultReferenceId = txId
                )
            )

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
                if (replayDetected) {
                    check(existingItems.keys == itemsList.map { it.id }.toSet()) {
                        "Replay item identity set differs from the already materialized transaction"
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
            if (replayDetected) {
                check(existingSettlements.keys == settlementsList.map { it.id }.toSet()) {
                    "Replay settlement identity set differs from the already materialized operation"
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

            // If this replay resumed a PENDING operation, finalize it only after
            // all linked transaction, item, settlement, and event evidence is durable.
            val markedSucceeded = operationDao.markSucceededIfPending(
                operationId = opId,
                requestFingerprint = jsonPayload.optString("request_fingerprint", "remote-$opId"),
                resultReferenceType = "TRANSACTION",
                resultReferenceId = txId
            )
            check(markedSucceeded == 1 || operationDao.isOperationSucceeded(opId)) {
                "Inbound sale could not be finalized with the matching request fingerprint"
            }

            val result = if (replayDetected) InboundProcessResult.Skipped else InboundProcessResult.Applied
            recordAppliedReceipt(database, envelope, branchId)
            result
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
                    repaymentJson.optLong("amount_minor", 0L) <= 0L
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
                val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                if (settlements.length() == 0) {
                    return InboundProcessResult.Rejected("Credit repayment has no funding settlement")
                }
                var total = 0L
                for (i in 0 until settlements.length()) {
                    val row = settlements.getJSONObject(i)
                    if (
                        row.optString("operation_id") != envelope.operationId ||
                        row.optString("currency_code") != repaymentJson.optString("currency_code") ||
                        row.optString("entry_purpose") != EntryPurpose.CREDIT_REPAYMENT_COLLECTION.name
                    ) {
                        return InboundProcessResult.Rejected("Credit repayment settlement identity mismatch")
                    }
                    total = Math.addExact(total, row.getLong("amount_signed_minor"))
                }
                if (total != repaymentJson.getLong("amount_minor")) {
                    return InboundProcessResult.Rejected("Credit repayment settlements do not balance to repayment amount")
                }
                val ledgerEvents = evidence.optJSONArray("credit_ledger_events") ?: org.json.JSONArray()
                val repaymentEvents = (0 until ledgerEvents.length()).map { ledgerEvents.getJSONObject(it) }
                    .filter {
                        it.optString("type") == "REPAYMENT" &&
                            it.optString("repayment_id") == repaymentJson.optString("id")
                    }
                if (
                    repaymentEvents.size != 1 ||
                    repaymentEvents.single().optLong("amount_signed_minor", 0L) != -repaymentJson.getLong("amount_minor") ||
                    repaymentEvents.single().optString("customer_id") != customerId ||
                    repaymentEvents.single().optString("currency_code") != repaymentJson.optString("currency_code")
                ) {
                    return InboundProcessResult.Rejected("Credit repayment is missing its matching negative ledger event")
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
                if (operationType == OperationType.OPEN_CASH_SHIFT &&
                    shiftEventJson.optLong("amount_minor", -1L) < 0L
                ) {
                    return InboundProcessResult.Rejected("Cash-shift opening amount cannot be negative")
                }
                if (operationType == OperationType.COUNT_CASH_SHIFT &&
                    shiftEventJson.optLong("amount_minor", -1L) < 0L
                ) {
                    return InboundProcessResult.Rejected("Cash count cannot be negative")
                }
                if (
                    payload.optString("subject_type").isNotBlank() &&
                        payload.optString("subject_type") != SubjectType.CASH_SHIFT.name ||
                    payload.optString("subject_id").isNotBlank() &&
                        payload.optString("subject_id") != shiftJson.optString("id")
                ) {
                    return InboundProcessResult.Rejected("Cash-shift subject metadata mismatch")
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
                if (
                    payload.optString("subject_type").isNotBlank() &&
                        payload.optString("subject_type") != SubjectType.EXPENSE.name ||
                    payload.optString("subject_id").isNotBlank() &&
                        payload.optString("subject_id") != expenseSnapshot.optString("id")
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle subject metadata mismatch")
                }
                val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                if (settlements.length() == 0) {
                    return InboundProcessResult.Rejected("Expense lifecycle operation is missing signed settlement evidence")
                }
                var total = 0L
                for (i in 0 until settlements.length()) {
                    val row = settlements.getJSONObject(i)
                    if (
                        row.optString("operation_id") != envelope.operationId ||
                        row.optString("subject_type") != SubjectType.EXPENSE.name ||
                        row.optString("subject_id") != expenseSnapshot.optString("id") ||
                        row.optString("transaction_id").isNotBlank() ||
                        row.optString("currency_code") != expenseSnapshot.optString("currency_code")
                    ) {
                        return InboundProcessResult.Rejected("Expense lifecycle settlement identity mismatch")
                    }
                    total = Math.addExact(total, row.getLong("amount_signed_minor"))
                }
                val event = operationEvents.single()
                if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    if (total != expenseSnapshot.optLong("amount_minor", 0L)) {
                        return InboundProcessResult.Rejected("Expense void settlement does not reverse the original outflow")
                    }
                } else if (
                    event.has("previous_amount_minor") &&
                    !event.isNull("previous_amount_minor") &&
                    event.has("new_amount_minor") &&
                    !event.isNull("new_amount_minor")
                ) {
                    val expectedDelta = Math.subtractExact(
                        event.getLong("previous_amount_minor"),
                        event.getLong("new_amount_minor")
                    )
                    if (total != expectedDelta) {
                        return InboundProcessResult.Rejected("Expense correction delta does not match signed settlements")
                    }
                } else if (event.optString("new_payment_category").isNotBlank() && total != 0L) {
                    return InboundProcessResult.Rejected("Expense payment reclassification settlements must net to zero")
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
                val cashShiftId = payload.optString("cash_shift_id").ifBlank {
                    shiftJson?.optString("id")?.ifBlank { null }
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
                    status = OperationStatus.PENDING
                )
                val existing = operationDao.getOperationById(envelope.operationId)
                val replayDetected = existing?.status == OperationStatus.SUCCEEDED
                if (existing == null) {
                    operationDao.insertOperationIfAbsent(operation)
                } else {
                    check(
                        existing.businessId == operation.businessId &&
                            existing.branchId == operation.branchId &&
                            existing.operationType == operation.operationType &&
                            existing.requestFingerprint == operation.requestFingerprint
                    ) { "Inbound operation identity collision" }
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

                val finalized = operationDao.markSucceededIfPending(
                    operationId = envelope.operationId,
                    requestFingerprint = fingerprint,
                    resultReferenceType = subjectType.name,
                    resultReferenceId = subjectId
                )
                check(finalized == 1 || operationDao.isOperationSucceeded(envelope.operationId)) {
                    "Inbound non-transaction operation could not be finalized"
                }
                val result = if (replayDetected) InboundProcessResult.Skipped else InboundProcessResult.Applied
                recordAppliedReceipt(database, envelope, branchId)
                result
            }
        } catch (error: Exception) {
            InboundProcessResult.Rejected(
                "Unable to materialize ${envelope.operationType}: ${error.message ?: "integrity validation failed"}"
            )
        }
    }

    private suspend fun materializeEnvelope(
        database: DmrDatabase,
        jsonPayload: JSONObject,
        envelope: RemoteEnvelopeV2,
        branchId: String
    ): InboundProcessResult {
        val opId = jsonPayload.getString("operation_id")
        val businessId = jsonPayload.getString("business_id")
        val branchId = jsonPayload.optString("branch_id").ifBlank { null }
        val actorUserId = jsonPayload.getString("actor_user_id")
        val deviceId = jsonPayload.getString("device_id")
        val opTypeStr = jsonPayload.getString("operation_type")
        val occurredAt = jsonPayload.getLong("occurred_at")
        val schemaVer = jsonPayload.optInt("command_schema_version", 1)
        val requestFingerprint = jsonPayload.optString("request_fingerprint", "remote-$opId")
        val intentPayload = jsonPayload.optString("intent_payload", "{}")

        val opType = runCatching { OperationType.valueOf(opTypeStr) }.getOrElse {
            return InboundProcessResult.UnsupportedYet(opTypeStr)
        }

        var replayDetected = false
        return database.withTransaction {
            CustomerIdentityReconciler.reconcileEvidenceCustomers(
                database, businessId,
                jsonPayload.optJSONObject("financial_evidence") ?: JSONObject()
            )
            val operationDao = database.operationDao()
            val transactionDao = database.transactionDao()
            val expenseDao = database.expenseDao()

            val existingOp = operationDao.getOperationById(opId)
            if (existingOp != null && (
                    existingOp.businessId != businessId ||
                        existingOp.branchId != branchId ||
                        existingOp.operationType != opType ||
                        existingOp.requestFingerprint != requestFingerprint
                )
            ) {
                return@withTransaction InboundProcessResult.Rejected(
                    "Operation ID collision: existing expense/operation identity differs from the envelope"
                )
            }
            replayDetected = existingOp?.status == OperationStatus.SUCCEEDED

            var txId: String? = null
            var txType = "TRANSACTION"

            // 1. Transaction (if present)
            if (jsonPayload.has("transaction") && !jsonPayload.isNull("transaction")) {
                val tJson = jsonPayload.getJSONObject("transaction")
                txId = tJson.getString("id")
                val tEntity = TransactionEntity(
                    id = txId,
                    branchId = tJson.getString("branch_id"),
                    type = TransactionType.valueOf(tJson.getString("type")),
                    customerId = tJson.optString("customer_id").ifBlank { null }?.let {
                    CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(database, businessId, it)
                },
                    subtotalMinor = tJson.getLong("subtotal_minor"),
                    taxRateBps = tJson.getInt("tax_rate_bps"),
                    taxableBaseMinor = tJson.getLong("taxable_base_minor"),
                    taxAmountMinor = tJson.getLong("tax_amount_minor"),
                    txShippingMinor = tJson.optLong("tx_shipping_minor", 0L),
                    txPackingMinor = tJson.optLong("tx_packing_minor", 0L),
                    grandTotalMinor = tJson.getLong("grand_total_minor"),
                    currencyCode = tJson.getString("currency_code"),
                    currencyDecimalPlaces = tJson.getInt("currency_decimal_places"),
                    createdByUserId = tJson.getString("created_by_user_id"),
                    deviceId = tJson.getString("device_id"),
                    createdAt = tJson.getLong("created_at"),
                    description = tJson.optString("description").ifBlank { null },
                    displayRef = tJson.getString("display_ref")
                )
                val existingTransaction = transactionDao.getTransactionById(txId)
                if (existingTransaction == null) {
                    transactionDao.insertTransaction(tEntity)
                } else {
                    check(existingTransaction == tEntity) {
                        "Existing immutable transaction differs from replayed envelope"
                    }
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
                if (itemsList.isNotEmpty()) {
                    check(itemsList.map { it.id }.distinct().size == itemsList.size) {
                        "Transaction contains duplicate item IDs"
                    }
                    val existingItems = txId?.let {
                        transactionDao.getItemsForTransaction(it).associateBy { item -> item.id }
                    } ?: emptyMap()
                    for (incoming in itemsList) {
                        val existing = existingItems[incoming.id]
                        if (existing != null) {
                            check(existing == incoming) {
                                "Existing immutable transaction item differs from replayed envelope"
                            }
                        }
                    }
                    val newItems = itemsList.filter { it.id !in existingItems }
                    if (newItems.isNotEmpty()) {
                        transactionDao.insertTransactionItems(newItems)
                    }
                }
            }

            // 3. Expense
            if (jsonPayload.has("expense") && !jsonPayload.isNull("expense")) {
                val eJson = jsonPayload.getJSONObject("expense")
                val eId = eJson.getString("id")
                txId = eId
                txType = "EXPENSE"
                val eEntity = ExpenseEntity(
                    id = eId,
                    operationId = eJson.getString("operation_id"),
                    businessId = eJson.getString("business_id"),
                    branchId = eJson.getString("branch_id"),
                    cashShiftId = eJson.optString("cash_shift_id").ifBlank { null },
                    actorUserId = eJson.getString("actor_user_id"),
                    payeeId = eJson.optString("payee_id").ifBlank { null },
                    payeeNameSnapshot = eJson.getString("payee_name_snapshot"),
                    payeePhoneSnapshot = eJson.optString("payee_phone_snapshot").ifBlank { null },
                    payeeAccountSnapshot = eJson.optString("payee_account_snapshot").ifBlank { null },
                    category = eJson.getString("category"),
                    amountMinor = eJson.getLong("amount_minor"),
                    paymentCategory = eJson.getString("payment_category"),
                    currencyCode = eJson.getString("currency_code"),
                    reference = eJson.optString("reference").ifBlank { null },
                    note = eJson.optString("note").ifBlank { null },
                    recurrenceId = eJson.optString("recurrence_id").ifBlank { null },
                    occurredAt = eJson.getLong("occurred_at"),
                    createdAt = eJson.getLong("created_at")
                )
                val existingExpense = expenseDao.getExpenseById(eId)
                if (existingExpense == null) {
                    expenseDao.insertExpense(eEntity)
                } else {
                    check(existingExpense == eEntity) {
                        "Existing immutable expense differs from replayed envelope"
                    }
                }
            }

            // 4. Operation Entity
            val opEntity = OperationEntity(
                id = opId,
                businessId = businessId,
                branchId = branchId,
                operationType = opType,
                subjectType = if (txId != null) SubjectType.valueOf(txType) else SubjectType.TRANSACTION,
                subjectId = txId ?: opId,
                transactionId = if (txType == "EXPENSE") null else txId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                occurredAt = occurredAt,
                requestFingerprint = requestFingerprint,
                commandSchemaVersion = schemaVer,
                intentPayload = intentPayload,
                status = OperationStatus.SUCCEEDED,
                resultReferenceType = txType,
                resultReferenceId = txId ?: opId
            )

            // Insert downloaded evidence as already succeeded. Avoid the local
            // PENDING -> SUCCEEDED transition that can enqueue it for upload.
            operationDao.insertOperationIfAbsent(opEntity)

            // 5. Settlements (insert after owning operation; settlement_entries has an operation foreign key)
            if (jsonPayload.has("settlements") && !jsonPayload.isNull("settlements")) {
                val settlementsArray = jsonPayload.getJSONArray("settlements")
                val settlementsList = mutableListOf<SettlementEntryEntity>()
                for (i in 0 until settlementsArray.length()) {
                    val sJson = settlementsArray.getJSONObject(i)
                    settlementsList.add(
                        SettlementEntryEntity(
                            id = sJson.getString("id"),
                            operationId = sJson.getString("operation_id"),
                            subjectType = SubjectType.valueOf(sJson.getString("subject_type")),
                            subjectId = sJson.getString("subject_id"),
                            transactionId = sJson.optString("transaction_id").ifBlank { null },
                            entryPurpose = EntryPurpose.valueOf(sJson.getString("entry_purpose")),
                            category = PaymentCategory.valueOf(sJson.getString("category")),
                            channel = PaymentChannel.valueOf(sJson.getString("channel")),
                            amountSignedMinor = sJson.getLong("amount_signed_minor"),
                            currencyCode = sJson.getString("currency_code"),
                            confirmationType = ConfirmationType.valueOf(sJson.getString("confirmation_type")),
                            qrProfileIdSnapshot = sJson.optString("qr_profile_id_snapshot").ifBlank { null },
                            occurredAt = sJson.getLong("occurred_at"),
                            actorUserId = sJson.getString("actor_user_id"),
                            providerRef = sJson.optString("provider_ref").ifBlank { null }
                        )
                    )
                }
                check(settlementsList.map { it.id }.distinct().size == settlementsList.size) {
                    "Expense envelope contains duplicate settlement IDs"
                }
                val existingSettlements = transactionDao.getSettlementsForOperation(opId).associateBy { it.id }
                for (incoming in settlementsList) {
                    val existing = existingSettlements[incoming.id]
                    if (existing != null) {
                        check(existing == incoming) {
                            "Existing immutable settlement differs from replayed envelope"
                        }
                    }
                }
                val newSettlements = settlementsList.filter { it.id !in existingSettlements }
                if (newSettlements.isNotEmpty()) {
                    transactionDao.insertSettlements(newSettlements)
                }
            }

            RemoteEnvelopeEvidenceApplier.apply(
                database = database,
                evidence = jsonPayload.optJSONObject("financial_evidence") ?: JSONObject(),
                operationId = opId,
                businessId = businessId,
                branchId = branchId ?: error("Expense operation branch is missing"),
                actorUserId = actorUserId,
                transactionId = null
            )
            check(operationDao.isOperationSucceeded(opId)) {
                "Inbound operation was not persisted as succeeded"
            }

            val result = if (replayDetected) InboundProcessResult.Skipped else InboundProcessResult.Applied
            recordAppliedReceipt(database, envelope, branchId)
            result
        }
    }

    private suspend fun recordAppliedReceipt(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        branchId: String
    ) {
        val digest = envelope.canonicalSha256
            ?.takeIf { it.isNotBlank() }
            ?: error("Cannot persist an applied receipt without a canonical digest")
        val dao = database.inboundEnvelopeAuditDao()
        val existingDigest = dao.appliedDigest(envelope.operationId)
        if (existingDigest != null) {
            check(existingDigest.equals(digest, ignoreCase = true)) {
                "Applied receipt digest conflicts for operation ${envelope.operationId}"
            }
            return
        }
        dao.appendAppliedIfAbsent(
            InboundEnvelopeAuditEventEntity(
                id = "inbound-applied:${envelope.operationId}",
                operationId = envelope.operationId,
                businessId = envelope.businessId,
                branchId = branchId,
                operationType = envelope.operationType,
                receivedAtIso = envelope.receivedAtIso,
                canonicalSha256 = digest,
                eventType = "APPLIED",
                reason = "Canonical digest verified; operation evidence committed atomically",
                occurredAt = System.currentTimeMillis()
            )
        )
        check(dao.appliedDigest(envelope.operationId)?.equals(digest, ignoreCase = true) == true) {
            "Applied receipt could not be durably verified for operation ${envelope.operationId}"
        }
    }
}
