package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.json.JSONObject

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

        // Requirement 3: Skip envelopes whose operation already exists locally as SUCCEEDED
        val existingOp = database.operationDao().getOperationById(opId)
        if (existingOp != null && existingOp.status == OperationStatus.SUCCEEDED) {
            return InboundProcessResult.Skipped
        }

        val jsonPayload = envelope.payloadJson

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
                database.userDao().getUserByIdInBusiness(actorUserId, sessionBusinessId) == null
            ) {
                return InboundProcessResult.Rejected("Referenced expense actor is missing locally")
            }
            val branch = database.businessBranchDao().getBranchById(branchId)
                ?: return InboundProcessResult.Rejected("Referenced branch '$branchId' is missing locally")
            if (branch.businessId != sessionBusinessId ||
                expense.optString("currency_code") != branch.currencyCode
            ) {
                return InboundProcessResult.Rejected("Expense branch or currency does not match the active business")
            }
            return if (materializeEnvelope(database, jsonPayload)) {
                InboundProcessResult.Applied
            } else {
                InboundProcessResult.Rejected("Expense envelope could not be materialized")
            }
        }

        // Operation-specific correction/refund/void/credit/coupon/cash-shift
        // handlers must persist their own immutable linked events. Never fall back
        // to a generic sale/expense insert for those operations.
        if (opTypeStr != "RECORD_SALE") {
            return InboundProcessResult.UnsupportedYet(opTypeStr)
        }

        // Check transaction payload
        if (!jsonPayload.has("transaction") || jsonPayload.isNull("transaction")) {
            return InboundProcessResult.Rejected("Missing transaction object for RECORD_SALE")
        }
        val tJson = jsonPayload.getJSONObject("transaction")
        val txType = tJson.optString("type", "")
        if (txType != "SALE") {
            return InboundProcessResult.Rejected("Transaction type '$txType' is not SALE for RECORD_SALE")
        }

        val grandTotalMinor = tJson.getLong("grand_total_minor")
        val txCurrencyCode = tJson.getString("currency_code")

        // b) grand_total_minor equals the settlement sum
        if (!jsonPayload.has("settlements") || jsonPayload.isNull("settlements")) {
            return InboundProcessResult.Rejected("Missing settlements payload for RECORD_SALE")
        }
        val settlementsArray = jsonPayload.getJSONArray("settlements")
        if (settlementsArray.length() == 0) {
            return InboundProcessResult.Rejected("Empty settlements array for RECORD_SALE")
        }

        var settlementSum = 0L
        for (i in 0 until settlementsArray.length()) {
            val sJson = settlementsArray.getJSONObject(i)
            settlementSum = Math.addExact(
                settlementSum,
                sJson.getLong("amount_signed_minor")
            )
        }

        if (grandTotalMinor != settlementSum) {
            return InboundProcessResult.Rejected("grand_total_minor ($grandTotalMinor) does not equal settlement sum ($settlementSum)")
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
        val actor = database.userDao().getUserByIdInBusiness(actorUserId, sessionBusinessId)
            ?: return InboundProcessResult.Rejected("Referenced actor user '$actorUserId' is missing locally")
        val createdByUserId = tJson.optString("created_by_user_id").ifBlank { actorUserId }
        if (database.userDao().getUserByIdInBusiness(createdByUserId, sessionBusinessId) == null) {
            return InboundProcessResult.Rejected("Referenced transaction user '$createdByUserId' is missing locally")
        }
        if (tJson.getString("branch_id") != branchId) {
            return InboundProcessResult.Rejected("Transaction branch does not match envelope branch")
        }
        for (i in 0 until settlementsArray.length()) {
            val settlement = settlementsArray.getJSONObject(i)
            if (
                settlement.getString("operation_id") != opId ||
                settlement.optString("transaction_id").ifBlank { tJson.getString("id") } != tJson.getString("id")
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
            if (category.name.isBlank() || channel.name.isBlank()) {
                return InboundProcessResult.Rejected("Invalid settlement payment method")
            }
            val settlementActor = settlement.optString("actor_user_id").ifBlank { actorUserId }
            if (database.userDao().getUserByIdInBusiness(settlementActor, sessionBusinessId) == null) {
                return InboundProcessResult.Rejected("Referenced settlement user '$settlementActor' is missing locally")
            }
        }

        // d) actor user and branch rows exist locally or are created in the same transaction
        return database.withTransaction {
            val operationDao = database.operationDao()
            val transactionDao = database.transactionDao()
            val occurredAt = java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()

            val txId = tJson.getString("id")

            // Insert directly as SUCCEEDED. The outbox trigger only observes local
            // lifecycle UPDATE transitions, so downloaded evidence cannot be requeued.
            operationDao.insertOperationIfAbsent(
                OperationEntity(
                    id = opId,
                    businessId = sessionBusinessId,
                    branchId = branchId,
                    operationType = OperationType.RECORD_SALE,
                    subjectType = SubjectType.TRANSACTION,
                    subjectId = txId,
                    transactionId = txId,
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
                type = TransactionType.SALE,
                customerId = tJson.optString("customer_id").ifBlank { null },
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
            if (transactionDao.getTransactionById(txId) == null) {
                transactionDao.insertTransaction(tEntity)
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
                val existingItemIds = transactionDao.getItemsForTransaction(txId).map { it.id }.toSet()
                val newItems = itemsList.filter { it.id !in existingItemIds }
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
            val existingSettlements = transactionDao.getSettlementsForOperation(opId).map { it.id }.toSet()
            val newSettlements = settlementsList.filter { it.id !in existingSettlements }
            if (newSettlements.isNotEmpty()) {
                transactionDao.insertSettlements(newSettlements)
            }

            InboundProcessResult.Applied
        }
    }

    private suspend fun materializeEnvelope(
        database: DmrDatabase,
        jsonPayload: JSONObject
    ): Boolean {
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
            return false
        }

        return database.withTransaction {
            val operationDao = database.operationDao()
            val transactionDao = database.transactionDao()
            val expenseDao = database.expenseDao()

            val existingOp = operationDao.getOperationById(opId)
            if (existingOp != null && existingOp.status == OperationStatus.SUCCEEDED) {
                return@withTransaction true // Already materialized locally
            }

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
                    customerId = tJson.optString("customer_id").ifBlank { null },
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
                if (transactionDao.getTransactionById(txId) == null) {
                    transactionDao.insertTransaction(tEntity)
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
                    val existingItemIds = txId?.let { transactionDao.getItemsForTransaction(it).map { it.id }.toSet() } ?: emptySet()
                    val newItems = itemsList.filter { it.id !in existingItemIds }
                    if (newItems.isNotEmpty()) {
                        transactionDao.insertTransactionItems(newItems)
                    }
                }
            }

            // 3. Settlements
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
                if (settlementsList.isNotEmpty()) {
                    val existingSettlements = transactionDao.getSettlementsForOperation(opId).map { it.id }.toSet()
                    val newSettlements = settlementsList.filter { it.id !in existingSettlements }
                    if (newSettlements.isNotEmpty()) {
                        transactionDao.insertSettlements(newSettlements)
                    }
                }
            }

            // 4. Expense
            if (jsonPayload.has("expense") && !jsonPayload.isNull("expense")) {
                val eJson = jsonPayload.getJSONObject("expense")
                val eId = eJson.getString("id")
                txId = eId
                txType = "EXPENSE"
                if (expenseDao.getExpenseById(eId) == null) {
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
                    expenseDao.insertExpense(eEntity)
                }
            }

            // 5. Operation Entity
            val opEntity = OperationEntity(
                id = opId,
                businessId = businessId,
                branchId = branchId,
                operationType = opType,
                subjectType = if (txId != null) SubjectType.valueOf(txType) else SubjectType.TRANSACTION,
                subjectId = txId ?: opId,
                transactionId = txId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                occurredAt = occurredAt,
                requestFingerprint = requestFingerprint,
                commandSchemaVersion = schemaVer,
                intentPayload = intentPayload,
                status = OperationStatus.PENDING
            )

            operationDao.insertOperationIfAbsent(opEntity)
            operationDao.markSucceededIfPending(
                operationId = opId,
                requestFingerprint = requestFingerprint,
                resultReferenceType = txType,
                resultReferenceId = txId ?: opId
            )

            true
        }
    }
}
