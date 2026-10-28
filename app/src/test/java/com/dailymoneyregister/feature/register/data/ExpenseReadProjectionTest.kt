package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseReadProjectionTest {

    @Test
    fun cashAndOnlineExpensesPreserveAuthoritativeMethodAndNegativeSign() {
        val cash = resolve(
            PaymentCategory.CASH,
            PaymentChannel.CASH_TENDER
        )
        val online = resolve(
            PaymentCategory.ONLINE,
            PaymentChannel.MANUAL_QR
        )

        assertEquals(-4_500L, cash.signedEffect.amount)
        assertEquals(PaymentCategory.CASH, cash.fundingBreakdown.single().category)
        assertEquals(PaymentChannel.CASH_TENDER, cash.fundingBreakdown.single().channel)
        assertEquals(-4_500L, online.signedEffect.amount)
        assertEquals(PaymentCategory.ONLINE, online.fundingBreakdown.single().category)
        assertEquals(PaymentChannel.MANUAL_QR, online.fundingBreakdown.single().channel)
        assertEquals("expense-op", online.operationId)
        assertEquals(5_000L, online.occurredAt)
    }

    private fun resolve(
        category: PaymentCategory,
        channel: PaymentChannel
    ): ExpenseReadProjection {
        val expense = ExpenseEntity(
            id = "expense-1",
            operationId = "expense-op",
            businessId = "business-1",
            branchId = "branch-1",
            cashShiftId = "shift-1",
            actorUserId = "user-1",
            payeeId = "customer-1",
            payeeNameSnapshot = "Supplier",
            payeePhoneSnapshot = null,
            payeeAccountSnapshot = null,
            category = "OTHER",
            amountMinor = 4_500L,
            paymentCategory = category.name,
            currencyCode = "OMR",
            reference = "EXP-1",
            note = null,
            recurrenceId = null,
            occurredAt = 5_000L,
            createdAt = 5_000L
        )
        val settlement = SettlementEntryEntity(
            id = "expense-settlement-${category.name}",
            operationId = expense.operationId,
            subjectType = SubjectType.EXPENSE,
            subjectId = expense.id,
            transactionId = null,
            entryPurpose = EntryPurpose.EXPENSE_OUTFLOW,
            category = category,
            channel = channel,
            amountSignedMinor = -expense.amountMinor,
            currencyCode = expense.currencyCode,
            confirmationType = ConfirmationType.ADMIN_CONFIRMED,
            occurredAt = expense.occurredAt,
            actorUserId = expense.actorUserId
        )

        return ExpenseReadProjectionResolver.resolve(expense, settlement)
    }
}
