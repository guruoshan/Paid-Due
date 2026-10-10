package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.*
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Collects immutable operation-linked evidence for the outbound envelope.
 *
 * This is a transport projection only: it does not calculate balances or write
 * financial state. All values come from the authoritative Room rows.
 */
internal object FinancialEnvelopeEvidenceLoader {

    suspend fun load(
        database: DmrDatabase,
        operation: OperationEntity,
        settlements: List<SettlementEntryEntity> = emptyList()
    ): JSONObject {
        val evidence = JSONObject()
        val type = operation.operationType
        val transaction = operation.transactionId
            ?.let { database.transactionDao().getTransactionById(it) }
        val expense = when {
            type == OperationType.RECORD_EXPENSE ->
                database.expenseDao().getExpenseByOperationId(operation.id)
            operation.subjectType.name == "EXPENSE" ->
                database.expenseDao().getExpenseById(operation.subjectId)
            else -> null
        }

        val needsCreditEvidence = type == OperationType.RECORD_CREDIT_REPAYMENT ||
            settlements.any { it.category == PaymentCategory.CREDIT }
        val needsCouponEvidence = type == OperationType.RECORD_COUPON_PURCHASE ||
            settlements.any { it.category == PaymentCategory.COUPON }
        val repaymentRows = if (type == OperationType.RECORD_CREDIT_REPAYMENT) {
            database.ledgerDao().getCreditRepaymentsForOperation(operation.id)
        } else emptyList()
        val repayment = repaymentRows.singleOrNull()
        val couponEvents = if (needsCouponEvidence) {
            database.ledgerDao().getCouponEventsForOperation(operation.id)
        } else emptyList()
        val walletIds = couponEvents.map { it.walletId }.distinct()
        val wallets = walletIds.mapNotNull { database.ledgerDao().getCouponWalletById(it) }
        val allWalletUsers = wallets.flatMap {
            database.ledgerDao().getAuthorizedUsersForWallet(it.id)
        }.distinctBy { it.id }
        val authorizedUserEvents = allWalletUsers.flatMap {
            database.ledgerDao().getAuthorizedUserEvents(it.id)
        }.filter { it.operationId == operation.id }.distinctBy { it.id }
        // Only send user rows changed/created by this operation. Sending a later
        // current-state snapshot with an older envelope could otherwise collide
        // during out-of-order replay.
        val eventUserIds = authorizedUserEvents.map { it.authorizedUserId }.toSet()
        val authorizedUsers = allWalletUsers.filter { it.id in eventUserIds }

        val customerIds = buildList {
            transaction?.customerId?.let(::add)
            repayment?.customerId?.let(::add)
            wallets.forEach { add(it.customerId) }
            expense?.payeeId?.let(::add)
        }.distinct()
        val customerSnapshots = customerIds.mapNotNull { database.customerDao().getCustomerById(it) }
        if (customerSnapshots.isNotEmpty()) {
            evidence.put("customers", JSONArray(customerSnapshots.map { it.toJson() }))
            evidence.put("customer", customerSnapshots.first().toJson())
        }

        if (repayment != null) {
            evidence.put("credit_repayment", repayment.toJson())
            evidence.put(
                "credit_repayment_allocations",
                JSONArray(database.ledgerDao().getCreditRepaymentAllocations(repayment.id)
                    .map { it.toJson() })
            )
        }
        val creditEvents = if (needsCreditEvidence) {
            database.ledgerDao().getCreditEventsForOperations(listOf(operation.id))
        } else emptyList()
        if (creditEvents.isNotEmpty()) {
            evidence.put("credit_ledger_events", JSONArray(creditEvents.map { it.toJson() }))
        }

        if (couponEvents.isNotEmpty()) {
            evidence.put("coupon_ledger_events", JSONArray(couponEvents.map { it.toJson() }))
        }
        if (wallets.isNotEmpty()) {
            evidence.put("coupon_wallets", JSONArray(wallets.map { it.toJson() }))
        }
        if (authorizedUsers.isNotEmpty()) {
            evidence.put("coupon_authorized_users", JSONArray(authorizedUsers.map { it.toJson() }))
        }
        if (authorizedUserEvents.isNotEmpty()) {
            evidence.put(
                "coupon_authorized_user_events",
                JSONArray(authorizedUserEvents.map { it.toJson() })
            )
        }

        val txDao = database.transactionDao()
        val transactionId = operation.transactionId ?: transaction?.id
        if (transactionId != null && type == OperationType.RECORD_AMOUNT_CORRECTION) {
            val amountEvents = txDao.getAmountCorrectionsByOperationId(operation.id)
            if (amountEvents.isNotEmpty()) {
                evidence.put("amount_correction_events", JSONArray(amountEvents.map { it.toJson() }))
            }
        }
        if (transactionId != null && type == OperationType.RECORD_PAYMENT_METHOD_CHANGE) {
            val paymentEvents = txDao.getPaymentMethodChanges(transactionId)
                .filter { it.operationId == operation.id }
            if (paymentEvents.isNotEmpty()) {
                evidence.put("payment_method_change_events", JSONArray(paymentEvents.map { it.toJson() }))
            }
        }
        if (type == OperationType.RECORD_VOID) {
            txDao.getVoidEventByOperationId(operation.id)?.let {
                evidence.put("void_event", it.toJson())
            }
        }
        if (type == OperationType.RECORD_REFUND) {
            txDao.getRefundEventByOperationId(operation.id)?.let {
                evidence.put("refund_event", it.toJson())
            }
        }

        if (type in setOf(
                OperationType.OPEN_CASH_SHIFT,
                OperationType.COUNT_CASH_SHIFT,
                OperationType.CLOSE_CASH_SHIFT
            )
        ) {
            val shiftEvent = database.cashShiftDao().getEventByOperationId(operation.id)
            if (shiftEvent != null) {
                evidence.put("cash_shift_event", shiftEvent.toJson())
                database.cashShiftDao().getShiftById(shiftEvent.shiftId)?.let {
                    evidence.put("cash_shift", it.toJson())
                }
            } else if (operation.subjectType.name == "CASH_SHIFT") {
                database.cashShiftDao().getShiftById(operation.subjectId)?.let {
                    evidence.put("cash_shift", it.toJson())
                }
            }
        }

        if (expense != null) {
            evidence.put("expense_snapshot", expense.toJson())
            val expenseEvents = database.expenseDao().getEventsForExpense(expense.id)
                .filter { it.operationId == operation.id }
            if (expenseEvents.isNotEmpty()) {
                evidence.put("expense_events", JSONArray(expenseEvents.map { it.toJson() }))
            }
        }

        return evidence
    }

    private fun CustomerEntity.toJson() = JSONObject()
        .put("id", id).put("business_id", businessId)
        .put("canonical_phone", canonicalPhone).put("name", name)
        .put("created_at", createdAt).put("updated_at", updatedAt)

    private fun CreditRepaymentEntity.toJson() = JSONObject()
        .put("id", id).put("operation_id", operationId).put("customer_id", customerId)
        .put("amount_minor", amountMinor).put("currency_code", currencyCode)
        .put("payment_category", paymentCategory.name).put("occurred_at", occurredAt)
        .put("actor_user_id", actorUserId)

    private fun CreditRepaymentAllocationEntity.toJson() = JSONObject()
        .put("id", id).put("repayment_id", repaymentId)
        .put("original_transaction_id", originalTransactionId)
        .put("allocated_amount_minor", allocatedAmountMinor).put("occurred_at", occurredAt)

    private fun CreditLedgerEventEntity.toJson() = JSONObject()
        .put("id", id).put("operation_id", operationId).put("customer_id", customerId)
        .put("type", type.name).put("amount_signed_minor", amountSignedMinor)
        .put("currency_code", currencyCode).put("transaction_id", transactionId.orEmpty())
        .put("repayment_id", repaymentId.orEmpty()).put("occurred_at", occurredAt)

    private fun CouponWalletEntity.toJson() = JSONObject()
        .put("id", id).put("customer_id", customerId).put("currency_code", currencyCode)
        .put("is_active", isActive).put("created_at", createdAt)

    private fun CouponAuthorizedUserEntity.toJson() = JSONObject()
        .put("id", id).put("wallet_id", walletId).put("is_purchaser", isPurchaser)
        .put("is_active", isActive).put("created_at", createdAt)

    private fun CouponAuthorizedUserEventEntity.toJson() = JSONObject()
        .put("id", id).put("authorized_user_id", authorizedUserId)
        .put("operation_id", operationId).put("event_type", eventType.name)
        .put("name_value", nameValue).put("occurred_at", occurredAt)
        .put("actor_user_id", actorUserId)

    private fun CouponLedgerEventEntity.toJson() = JSONObject()
        .put("id", id).put("operation_id", operationId).put("wallet_id", walletId)
        .put("type", type.name).put("amount_signed_minor", amountSignedMinor)
        .put("currency_code", currencyCode).put("transaction_id", transactionId.orEmpty())
        .put("authorized_user_id", authorizedUserId.orEmpty())
        .put("authorized_user_name_snapshot", authorizedUserNameSnapshot.orEmpty())
        .put("occurred_at", occurredAt)

    private fun AmountCorrectionEventEntity.toJson() = JSONObject()
        .put("id", id).put("transaction_id", transactionId).put("operation_id", operationId)
        .put("previous_amount_minor", previousAmountMinor)
        .put("corrected_amount_minor", correctedAmountMinor)
        .put("reason", reason.orEmpty()).put("actor_user_id", actorUserId)
        .put("occurred_at", occurredAt)

    private fun PaymentMethodChangeEventEntity.toJson() = JSONObject()
        .put("id", id).put("transaction_id", transactionId).put("operation_id", operationId)
        .put("previous_method", previousMethod.name).put("previous_channel", previousChannel?.name.orEmpty())
        .put("new_method", newMethod.name).put("new_channel", newChannel?.name.orEmpty())
        .put("reason", reason.orEmpty()).put("actor_user_id", actorUserId)
        .put("occurred_at", occurredAt)

    private fun VoidEventEntity.toJson() = JSONObject()
        .put("id", id).put("transaction_id", transactionId).put("operation_id", operationId)
        .put("reason", reason.orEmpty()).put("actor_user_id", actorUserId)
        .put("occurred_at", occurredAt)

    private fun RefundEventEntity.toJson() = JSONObject()
        .put("id", id).put("transaction_id", transactionId).put("operation_id", operationId)
        .put("refund_amount_minor", refundAmountMinor).put("reason", reason.orEmpty())
        .put("actor_user_id", actorUserId).put("occurred_at", occurredAt)

    private fun CashShiftEntity.toJson() = JSONObject()
        .put("id", id).put("branch_id", branchId).put("user_id", userId)
        .put("created_at", createdAt)

    private fun CashShiftEventEntity.toJson() = JSONObject()
        .put("id", id).put("shift_id", shiftId).put("operation_id", operationId)
        .put("type", type.name).put("amount_minor", amountMinor)
        .put("occurred_at", occurredAt).put("actor_user_id", actorUserId)

    private fun ExpenseEntity.toJson() = JSONObject()
        .put("id", id).put("operation_id", operationId).put("business_id", businessId)
        .put("branch_id", branchId).put("cash_shift_id", cashShiftId.orEmpty())
        .put("actor_user_id", actorUserId).put("payee_id", payeeId.orEmpty())
        .put("payee_name_snapshot", payeeNameSnapshot).put("payee_phone_snapshot", payeePhoneSnapshot.orEmpty())
        .put("payee_account_snapshot", payeeAccountSnapshot.orEmpty()).put("category", category)
        .put("amount_minor", amountMinor).put("payment_category", paymentCategory)
        .put("currency_code", currencyCode).put("reference", reference.orEmpty())
        .put("note", note.orEmpty()).put("recurrence_id", recurrenceId.orEmpty())
        .put("occurred_at", occurredAt).put("created_at", createdAt)

    private fun ExpenseEventEntity.toJson() = JSONObject()
        .put("id", id).put("expense_id", expenseId).put("operation_id", operationId)
        .put("event_type", eventType).put("actor_user_id", actorUserId)
        .put("previous_amount_minor", previousAmountMinor ?: JSONObject.NULL)
        .put("new_amount_minor", newAmountMinor ?: JSONObject.NULL)
        .put("previous_payment_category", previousPaymentCategory.orEmpty())
        .put("new_payment_category", newPaymentCategory.orEmpty())
        .put("previous_category", previousCategory.orEmpty()).put("new_category", newCategory.orEmpty())
        .put("previous_payee_id", previousPayeeId.orEmpty()).put("new_payee_id", newPayeeId.orEmpty())
        .put("reason", reason.orEmpty()).put("occurred_at", occurredAt)
}
