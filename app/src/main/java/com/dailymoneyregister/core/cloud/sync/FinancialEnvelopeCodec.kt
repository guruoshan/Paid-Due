package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal Financial Envelope Codec.
 *
 * Constructs canonical JSON strings and digests for outbound synchronization
 * and parses inbound envelope payloads for cross-device materialization.
 */
object FinancialEnvelopeCodec {

    data class EncodedEnvelope(
        val operationId: String,
        val businessId: String,
        val branchId: String,
        val actorUserId: String,
        val deviceId: String,
        val operationType: String,
        val commandSchemaVersion: Int,
        val occurredAt: Long,
        val canonicalText: String,
        val canonicalSha256: String,
        val jsonPayload: JSONObject
    )

    fun encode(
        operation: OperationEntity,
        transaction: TransactionEntity? = null,
        items: List<TransactionItemEntity> = emptyList(),
        settlements: List<SettlementEntryEntity> = emptyList(),
        expense: ExpenseEntity? = null,
        financialEvidence: JSONObject = JSONObject()
    ): EncodedEnvelope {
        val root = JSONObject()
        root.put("operation_id", operation.id)
        root.put("business_id", operation.businessId)
        root.put("branch_id", operation.branchId.orEmpty())
        root.put("actor_user_id", operation.actorUserId)
        root.put("device_id", operation.deviceId)
        root.put("operation_type", operation.operationType.name)
        root.put("occurred_at", operation.occurredAt)
        root.put("command_schema_version", operation.commandSchemaVersion)
        root.put("request_fingerprint", operation.requestFingerprint)
        root.put("intent_payload", operation.intentPayload)
        root.put("subject_type", operation.subjectType.name)
        root.put("subject_id", operation.subjectId)
        root.put("transaction_id", operation.transactionId.orEmpty())
        root.put("cash_shift_id", operation.cashShiftId.orEmpty())
        if (financialEvidence.length() > 0) {
            root.put("financial_evidence", financialEvidence)
        }

        if (transaction != null) {
            val txJson = JSONObject()
            txJson.put("id", transaction.id)
            txJson.put("branch_id", transaction.branchId)
            txJson.put("type", transaction.type.name)
            txJson.put("customer_id", transaction.customerId.orEmpty())
            txJson.put("subtotal_minor", transaction.subtotalMinor)
            txJson.put("tax_rate_bps", transaction.taxRateBps)
            txJson.put("taxable_base_minor", transaction.taxableBaseMinor)
            txJson.put("tax_amount_minor", transaction.taxAmountMinor)
            txJson.put("tx_shipping_minor", transaction.txShippingMinor)
            txJson.put("tx_packing_minor", transaction.txPackingMinor)
            txJson.put("grand_total_minor", transaction.grandTotalMinor)
            txJson.put("currency_code", transaction.currencyCode)
            txJson.put("currency_decimal_places", transaction.currencyDecimalPlaces)
            txJson.put("created_by_user_id", transaction.createdByUserId)
            txJson.put("device_id", transaction.deviceId)
            txJson.put("created_at", transaction.createdAt)
            txJson.put("description", transaction.description.orEmpty())
            txJson.put("display_ref", transaction.displayRef)
            root.put("transaction", txJson)
        }

        if (items.isNotEmpty()) {
            val itemsArray = JSONArray()
            for (item in items) {
                val itemJson = JSONObject()
                itemJson.put("id", item.id)
                itemJson.put("transaction_id", item.transactionId)
                itemJson.put("product_id", item.productId.orEmpty())
                itemJson.put("product_name_snapshot", item.productNameSnapshot)
                itemJson.put("description_snapshot", item.descriptionSnapshot.orEmpty())
                itemJson.put("quantity", item.quantity)
                itemJson.put("unit_price_minor", item.unitPriceMinor)
                itemJson.put("line_total_minor", item.lineTotalMinor)
                itemJson.put("barcode_snapshot", item.barcodeSnapshot.orEmpty())
                itemJson.put("media_id", item.mediaId.orEmpty())
                itemJson.put("item_packing_charge_minor", item.itemPackingChargeMinor)
                itemJson.put("item_shipping_charge_minor", item.itemShippingChargeMinor)
                itemsArray.put(itemJson)
            }
            root.put("items", itemsArray)
        }

        if (settlements.isNotEmpty()) {
            val settlementsArray = JSONArray()
            for (s in settlements) {
                val sJson = JSONObject()
                sJson.put("id", s.id)
                sJson.put("operation_id", s.operationId)
                sJson.put("subject_type", s.subjectType.name)
                sJson.put("subject_id", s.subjectId)
                sJson.put("transaction_id", s.transactionId.orEmpty())
                sJson.put("entry_purpose", s.entryPurpose.name)
                sJson.put("category", s.category.name)
                sJson.put("channel", s.channel.name)
                sJson.put("amount_signed_minor", s.amountSignedMinor)
                sJson.put("currency_code", s.currencyCode)
                sJson.put("confirmation_type", s.confirmationType.name)
                sJson.put("qr_profile_id_snapshot", s.qrProfileIdSnapshot.orEmpty())
                sJson.put("occurred_at", s.occurredAt)
                sJson.put("actor_user_id", s.actorUserId)
                sJson.put("provider_ref", s.providerRef.orEmpty())
                settlementsArray.put(sJson)
            }
            root.put("settlements", settlementsArray)
        }

        if (expense != null) {
            val eJson = JSONObject()
            eJson.put("id", expense.id)
            eJson.put("operation_id", expense.operationId)
            eJson.put("business_id", expense.businessId)
            eJson.put("branch_id", expense.branchId)
            eJson.put("cash_shift_id", expense.cashShiftId.orEmpty())
            eJson.put("actor_user_id", expense.actorUserId)
            eJson.put("payee_id", expense.payeeId.orEmpty())
            eJson.put("payee_name_snapshot", expense.payeeNameSnapshot)
            eJson.put("payee_phone_snapshot", expense.payeePhoneSnapshot.orEmpty())
            eJson.put("payee_account_snapshot", expense.payeeAccountSnapshot.orEmpty())
            eJson.put("category", expense.category)
            eJson.put("amount_minor", expense.amountMinor)
            eJson.put("payment_category", expense.paymentCategory)
            eJson.put("currency_code", expense.currencyCode)
            eJson.put("reference", expense.reference.orEmpty())
            eJson.put("note", expense.note.orEmpty())
            eJson.put("recurrence_id", expense.recurrenceId.orEmpty())
            eJson.put("occurred_at", expense.occurredAt)
            eJson.put("created_at", expense.createdAt)
            root.put("expense", eJson)
        }

        val canonicalText = root.toString()
        val canonicalSha256 = CommandFingerprint.sha256(canonicalText)

        return EncodedEnvelope(
            operationId = operation.id,
            businessId = operation.businessId,
            branchId = operation.branchId.orEmpty(),
            actorUserId = operation.actorUserId,
            deviceId = operation.deviceId,
            operationType = operation.operationType.name,
            commandSchemaVersion = operation.commandSchemaVersion,
            occurredAt = operation.occurredAt,
            canonicalText = canonicalText,
            canonicalSha256 = canonicalSha256,
            jsonPayload = root
        )
    }
}
