package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import org.json.JSONArray
import org.json.JSONObject

internal object RemoteExpenseLifecycleValidator {
    suspend fun validate(
        envelope: RemoteEnvelopeV2,
        operationType: OperationType,
        branchId: String,
        sessionBusinessId: String,
        actorUserId: String,
        expenseSnapshot: JSONObject?,
        expenseEvents: JSONArray?
    ): InboundProcessResult? {
        val payload = envelope.payloadJson
        
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
            val expectedDelta = try {
                Math.subtractExact(previousAmount, newAmount)
            } catch (_: ArithmeticException) {
                return InboundProcessResult.Rejected(
                    "Expense correction delta overflows the supported money range"
                )
            }
            if (total != expectedDelta) {
                return InboundProcessResult.Rejected("Expense correction delta does not match signed settlements")
            }
        }
        return null
    }
}
