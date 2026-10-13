package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.*
import com.dailymoneyregister.core.model.enums.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists immutable rows carried in financial_evidence.
 *
 * Call only inside the caller's Room transaction and after the owning
 * Operation and any referenced original transaction/expense have been checked.
 */
internal object RemoteEnvelopeEvidenceApplier {

    suspend fun apply(
        database: DmrDatabase,
        evidence: JSONObject,
        operationId: String,
        businessId: String,
        branchId: String,
        actorUserId: String,
        transactionId: String? = null
    ) {
        CustomerIdentityReconciler.reconcileEvidenceCustomers(database, businessId, evidence)

        val creditRepaymentJson = evidence.optJSONObject("credit_repayment")
        if (creditRepaymentJson != null) {
            val repayment = CreditRepaymentEntity(
                id = creditRepaymentJson.getString("id"),
                operationId = creditRepaymentJson.getString("operation_id"),
                customerId = CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(\n                    database, businessId, creditRepaymentJson.getString("customer_id")\n                ),
                amountMinor = creditRepaymentJson.getLong("amount_minor"),
                currencyCode = creditRepaymentJson.getString("currency_code"),
                paymentCategory = PaymentCategory.valueOf(creditRepaymentJson.getString("payment_category")),
                occurredAt = creditRepaymentJson.getLong("occurred_at"),
                actorUserId = creditRepaymentJson.getString("actor_user_id")
            )
            require(repayment.operationId == operationId && repayment.actorUserId == actorUserId) {
                "Credit repayment operation/actor mismatch"
            }
            require(repayment.amountMinor > 0L) { "Credit repayment amount must be positive" }
            require(database.customerDao().getCustomerById(repayment.customerId)?.businessId == businessId) {
                "Credit repayment customer is missing or belongs to another business"
            }
            val existingRepayment = database.ledgerDao().getCreditRepaymentById(repayment.id)
            if (existingRepayment == null) {
                require(database.ledgerDao().getCreditRepaymentsForOperation(operationId).isEmpty()) {
                    "Multiple credit repayment rows exist for one operation"
                }
                database.ledgerDao().insertCreditRepayment(repayment)
            } else {
                require(existingRepayment == repayment) { "Credit repayment identity collision" }
            }

            val allocations = evidence.optJSONArray("credit_repayment_allocations")
                ?: JSONArray()
            val parsedAllocations = (0 until allocations.length()).map { i ->
                val item = allocations.getJSONObject(i)
                CreditRepaymentAllocationEntity(
                    id = item.getString("id"),
                    repaymentId = item.getString("repayment_id"),
                    originalTransactionId = item.getString("original_transaction_id"),
                    allocatedAmountMinor = item.getLong("allocated_amount_minor"),
                    occurredAt = item.getLong("occurred_at")
                )
            }
            require(parsedAllocations.all { it.repaymentId == repayment.id && it.allocatedAmountMinor > 0L }) {
                "Credit repayment allocation identity/amount is invalid"
            }
            val existingAllocations = database.ledgerDao().getCreditRepaymentAllocations(repayment.id)
            if (existingAllocations.isEmpty() && parsedAllocations.isNotEmpty()) {
                database.ledgerDao().insertCreditRepaymentAllocations(parsedAllocations)
            } else {
                require(existingAllocations.toSet() == parsedAllocations.toSet()) {
                    "Credit repayment allocation evidence conflicts with local history"
                }
            }
        }

        val creditEvents = evidence.optJSONArray("credit_ledger_events")
        if (creditEvents != null) {
            val parsed = (0 until creditEvents.length()).map { i ->
                val item = creditEvents.getJSONObject(i)
                CreditLedgerEventEntity(
                    id = item.getString("id"),
                    operationId = item.getString("operation_id"),
                    customerId = CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(\n                        database, businessId, item.getString("customer_id")\n                    ),
                    type = CreditLedgerType.valueOf(item.getString("type")),
                    amountSignedMinor = item.getLong("amount_signed_minor"),
                    currencyCode = item.getString("currency_code"),
                    transactionId = item.optString("transaction_id").ifBlank { null },
                    repaymentId = item.optString("repayment_id").ifBlank { null },
                    occurredAt = item.getLong("occurred_at")
                )
            }
            require(parsed.all { it.operationId == operationId }) { "Credit ledger operation mismatch" }
            val existing = database.ledgerDao().getCreditEventsForOperations(listOf(operationId))
            if (existing.isEmpty() && parsed.isNotEmpty()) {
                parsed.forEach { database.ledgerDao().insertCreditEvent(it) }
            } else {
                require(existing.toSet() == parsed.toSet()) { "Credit ledger evidence conflicts with local history" }
            }
        }

        val walletsArray = evidence.optJSONArray("coupon_wallets") ?: JSONArray()
        val wallets = (0 until walletsArray.length()).map { i ->
            val item = walletsArray.getJSONObject(i)
            CouponWalletEntity(
                id = item.getString("id"),
                customerId = CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(
                    database, businessId, item.getString("customer_id")
                ),
                currencyCode = item.getString("currency_code"),
                isActive = item.optBoolean("is_active", true),
                createdAt = item.getLong("created_at")
            )
        }
        wallets.forEach { wallet ->
            require(database.customerDao().getCustomerById(wallet.customerId)?.businessId == businessId) {
                "Coupon wallet customer is missing or belongs to another business"
            }
            val existing = database.ledgerDao().getCouponWalletById(wallet.id)
            if (existing == null) database.ledgerDao().insertCouponWallet(wallet)
            else require(existing == wallet) { "Coupon wallet identity collision" }
        }

        val usersArray = evidence.optJSONArray("coupon_authorized_users") ?: JSONArray()
        val users = (0 until usersArray.length()).map { i ->
            val item = usersArray.getJSONObject(i)
            CouponAuthorizedUserEntity(
                id = item.getString("id"),
                walletId = item.getString("wallet_id"),
                isPurchaser = item.optBoolean("is_purchaser", false),
                isActive = item.optBoolean("is_active", true),
                createdAt = item.getLong("created_at")
            )
        }
        users.forEach { user ->
            require(wallets.any { it.id == user.walletId } ||
                database.ledgerDao().getCouponWalletById(user.walletId) != null) {
                "Coupon authorized user references a missing wallet"
            }
            val existing = database.ledgerDao().getAuthorizedUsersForWallet(user.walletId)
                .firstOrNull { it.id == user.id }
            if (existing == null) database.ledgerDao().insertAuthorizedUser(user)
            else require(existing == user) { "Coupon authorized-user identity collision" }
        }

        val userEventsArray = evidence.optJSONArray("coupon_authorized_user_events") ?: JSONArray()
        val userEvents = (0 until userEventsArray.length()).map { i ->
            val item = userEventsArray.getJSONObject(i)
            CouponAuthorizedUserEventEntity(
                id = item.getString("id"),
                authorizedUserId = item.getString("authorized_user_id"),
                operationId = item.getString("operation_id"),
                eventType = AuthorizedUserEventType.valueOf(item.getString("event_type")),
                nameValue = item.getString("name_value"),
                occurredAt = item.getLong("occurred_at"),
                actorUserId = item.getString("actor_user_id")
            )
        }
        userEvents.forEach { event ->
            require(event.operationId == operationId && event.actorUserId == actorUserId) {
                "Coupon authorized-user event operation/actor mismatch"
            }
            require(
                users.any { it.id == event.authorizedUserId } ||
                    database.ledgerDao().getAuthorizedUserEvents(event.authorizedUserId).isNotEmpty()
            ) { "Coupon authorized-user event references a missing user" }
            val existing = database.ledgerDao().getAuthorizedUserEvents(event.authorizedUserId)
                .firstOrNull { it.id == event.id }
            if (existing == null) database.ledgerDao().insertAuthorizedUserEvent(event)
            else require(existing == event) { "Coupon authorized-user event identity collision" }
        }

        val couponEventsArray = evidence.optJSONArray("coupon_ledger_events") ?: JSONArray()
        val couponEvents = (0 until couponEventsArray.length()).map { i ->
            val item = couponEventsArray.getJSONObject(i)
            CouponLedgerEventEntity(
                id = item.getString("id"),
                operationId = item.getString("operation_id"),
                walletId = item.getString("wallet_id"),
                type = CouponLedgerType.valueOf(item.getString("type")),
                amountSignedMinor = item.getLong("amount_signed_minor"),
                currencyCode = item.getString("currency_code"),
                transactionId = item.optString("transaction_id").ifBlank { null },
                authorizedUserId = item.optString("authorized_user_id").ifBlank { null },
                authorizedUserNameSnapshot = item.optString("authorized_user_name_snapshot").ifBlank { null },
                occurredAt = item.getLong("occurred_at")
            )
        }
        require(couponEvents.all { it.operationId == operationId }) { "Coupon ledger operation mismatch" }
        if (couponEvents.isNotEmpty()) {
            val existingCouponEvents = database.ledgerDao().getCouponEventsForOperation(operationId)
            if (existingCouponEvents.isEmpty()) {
                couponEvents.forEach { event ->
                    require(
                        wallets.any { it.id == event.walletId } ||
                            database.ledgerDao().getCouponWalletById(event.walletId) != null
                    ) { "Coupon ledger event references a missing wallet" }
                    database.ledgerDao().insertCouponEvent(event)
                }
            } else {
                require(existingCouponEvents.toSet() == couponEvents.toSet()) {
                    "Coupon ledger evidence conflicts with local history"
                }
            }
        }

        evidence.optJSONObject("amount_correction_events")?.let { /* handled by array below */ }
        val amountArray = evidence.optJSONArray("amount_correction_events") ?: JSONArray()
        for (i in 0 until amountArray.length()) {
            val item = amountArray.getJSONObject(i)
            val event = AmountCorrectionEventEntity(
                id = item.getString("id"), transactionId = item.getString("transaction_id"),
                operationId = item.getString("operation_id"),
                previousAmountMinor = item.getLong("previous_amount_minor"),
                correctedAmountMinor = item.getLong("corrected_amount_minor"),
                reason = item.optString("reason").ifBlank { null },
                actorUserId = item.getString("actor_user_id"), occurredAt = item.getLong("occurred_at")
            )
            require(event.operationId == operationId && event.transactionId == transactionId && event.actorUserId == actorUserId) {
                "Amount correction evidence identity mismatch"
            }
            val existing = database.transactionDao().getAmountCorrectionsByOperationId(operationId)
            if (existing.isEmpty()) database.transactionDao().insertAmountCorrection(event)
            else require(existing == listOf(event)) { "Amount correction evidence conflicts with local history" }
        }

        val paymentArray = evidence.optJSONArray("payment_method_change_events") ?: JSONArray()
        for (i in 0 until paymentArray.length()) {
            val item = paymentArray.getJSONObject(i)
            val event = PaymentMethodChangeEventEntity(
                id = item.getString("id"), transactionId = item.getString("transaction_id"),
                operationId = item.getString("operation_id"),
                previousMethod = PaymentCategory.valueOf(item.getString("previous_method")),
                previousChannel = item.optString("previous_channel").ifBlank { null }?.let(PaymentChannel::valueOf),
                newMethod = PaymentCategory.valueOf(item.getString("new_method")),
                newChannel = item.optString("new_channel").ifBlank { null }?.let(PaymentChannel::valueOf),
                reason = item.optString("reason").ifBlank { null },
                actorUserId = item.getString("actor_user_id"), occurredAt = item.getLong("occurred_at")
            )
            require(event.operationId == operationId && event.transactionId == transactionId && event.actorUserId == actorUserId) {
                "Payment-method change evidence identity mismatch"
            }
            val existing = database.transactionDao().getPaymentMethodChanges(event.transactionId)
                .filter { it.operationId == operationId }
            if (existing.isEmpty()) database.transactionDao().insertPaymentMethodChange(event)
            else require(existing == listOf(event)) { "Payment-method change evidence conflicts with local history" }
        }

        evidence.optJSONObject("void_event")?.let { item ->
            val event = VoidEventEntity(
                id = item.getString("id"), transactionId = item.getString("transaction_id"),
                operationId = item.getString("operation_id"),
                reason = item.optString("reason").ifBlank { null },
                actorUserId = item.getString("actor_user_id"), occurredAt = item.getLong("occurred_at")
            )
            require(event.operationId == operationId && event.transactionId == transactionId && event.actorUserId == actorUserId) {
                "Void evidence identity mismatch"
            }
            val existing = database.transactionDao().getVoidEventByOperationId(operationId)
            if (existing == null) database.transactionDao().insertVoid(event)
            else require(existing == event) { "Void evidence conflicts with local history" }
        }

        evidence.optJSONObject("refund_event")?.let { item ->
            val event = RefundEventEntity(
                id = item.getString("id"), transactionId = item.getString("transaction_id"),
                operationId = item.getString("operation_id"),
                refundAmountMinor = item.getLong("refund_amount_minor"),
                reason = item.optString("reason").ifBlank { null },
                actorUserId = item.getString("actor_user_id"), occurredAt = item.getLong("occurred_at")
            )
            require(event.operationId == operationId && event.transactionId == transactionId && event.actorUserId == actorUserId) {
                "Refund evidence identity mismatch"
            }
            val existing = database.transactionDao().getRefundEventByOperationId(operationId)
            if (existing == null) database.transactionDao().insertRefund(event)
            else require(existing == event) { "Refund evidence conflicts with local history" }
        }

        evidence.optJSONObject("cash_shift")?.let { item ->
            val shift = CashShiftEntity(
                id = item.getString("id"), branchId = item.getString("branch_id"),
                userId = item.getString("user_id"), createdAt = item.getLong("created_at")
            )
            require(shift.branchId == branchId) { "Cash shift belongs to another branch" }
            require(database.userDao().getUserByIdInBusiness(shift.userId, businessId) != null) {
                "Cash shift user is missing or belongs to another business"
            }
            val existing = database.cashShiftDao().getShiftById(shift.id)
            if (existing == null) database.cashShiftDao().insertShift(shift)
            else require(existing == shift) { "Cash shift identity collision" }
        }

        evidence.optJSONObject("cash_shift_event")?.let { item ->
            val event = CashShiftEventEntity(
                id = item.getString("id"), shiftId = item.getString("shift_id"),
                operationId = item.getString("operation_id"),
                type = CashShiftEventType.valueOf(item.getString("type")),
                amountMinor = item.getLong("amount_minor"), occurredAt = item.getLong("occurred_at"),
                actorUserId = item.getString("actor_user_id")
            )
            require(event.operationId == operationId && event.actorUserId == actorUserId) {
                "Cash-shift event operation/actor mismatch"
            }
            require(database.cashShiftDao().getShiftById(event.shiftId) != null) {
                "Cash-shift event references a missing shift"
            }
            val existing = database.cashShiftDao().getEventByOperationId(operationId)
            if (existing == null) database.cashShiftDao().insertShiftEvent(event)
            else require(existing == event) { "Cash-shift event conflicts with local history" }
        }

        val expenseEventsArray = evidence.optJSONArray("expense_events") ?: JSONArray()
        for (i in 0 until expenseEventsArray.length()) {
            val item = expenseEventsArray.getJSONObject(i)
            val event = ExpenseEventEntity(
                id = item.getString("id"), expenseId = item.getString("expense_id"),
                operationId = item.getString("operation_id"), eventType = item.getString("event_type"),
                actorUserId = item.getString("actor_user_id"),
                previousAmountMinor = item.opt("previous_amount_minor")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull(),
                newAmountMinor = item.opt("new_amount_minor")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull(),
                previousPaymentCategory = item.optString("previous_payment_category").ifBlank { null },
                newPaymentCategory = item.optString("new_payment_category").ifBlank { null },
                previousCategory = item.optString("previous_category").ifBlank { null },
                newCategory = item.optString("new_category").ifBlank { null },
                previousPayeeId = item.optString("previous_payee_id").ifBlank { null },
                newPayeeId = item.optString("new_payee_id").ifBlank { null },
                reason = item.optString("reason").ifBlank { null }, occurredAt = item.getLong("occurred_at")
            )
            require(event.operationId == operationId && event.actorUserId == actorUserId) {
                "Expense event operation/actor mismatch"
            }
            val expense = database.expenseDao().getExpenseById(event.expenseId)
                ?: error("Expense event references a missing expense")
            require(expense.businessId == businessId && expense.branchId == branchId) {
                "Expense event belongs to another tenant or branch"
            }
            val existing = database.expenseDao().getEventsForExpense(event.expenseId)
                .firstOrNull { it.operationId == operationId }
            if (existing != null) {
                require(existing == event) { "Expense event conflicts with local history" }
                continue
            }

            event.previousAmountMinor?.let { previous ->
                require(previous == expense.amountMinor) {
                    "Expense correction previous amount does not match the current authoritative snapshot"
                }
            }
            event.previousPaymentCategory?.let { previous ->
                require(previous == expense.paymentCategory) {
                    "Expense correction previous payment category does not match the current snapshot"
                }
            }
            event.previousCategory?.let { previous ->
                require(previous == expense.category) {
                    "Expense correction previous category does not match the current snapshot"
                }
            }
            event.newAmountMinor?.let { next ->
                require(next > 0L) { "Corrected expense amount must be positive; use a void event to cancel an expense" }
            }
            event.newPaymentCategory?.let { next ->
                require(next == PaymentCategory.CASH.name || next == PaymentCategory.ONLINE.name) {
                    "Expense supports Cash or Online only"
                }
            }

            database.expenseDao().insertExpenseEvent(event)
            if (!event.eventType.uppercase().contains("VOID")) {
                val payeeChanged = event.previousPayeeId != event.newPayeeId
                val newPayee = if (payeeChanged) {
                    event.newPayeeId?.let { database.customerDao().getCustomerById(it) }
                } else null
                if (payeeChanged && event.newPayeeId != null) {
                    require(newPayee?.businessId == businessId) {
                        "Corrected expense payee is missing or belongs to another business"
                    }
                }
                val updatedExpense = expense.copy(
                    payeeId = if (payeeChanged) event.newPayeeId else expense.payeeId,
                    payeeNameSnapshot = if (payeeChanged) newPayee?.name.orEmpty() else expense.payeeNameSnapshot,
                    payeePhoneSnapshot = if (payeeChanged) newPayee?.canonicalPhone else expense.payeePhoneSnapshot,
                    category = event.newCategory ?: expense.category,
                    amountMinor = event.newAmountMinor ?: expense.amountMinor,
                    paymentCategory = event.newPaymentCategory ?: expense.paymentCategory
                )
                if (updatedExpense != expense) {
                    check(database.expenseDao().updateExpense(updatedExpense) == 1) {
                        "Corrected expense snapshot could not be updated"
                    }
                }
            }
        }
    }
}
