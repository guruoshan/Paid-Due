package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.ids.CashShiftId

data class RegisterShiftResult(
    val shiftId: CashShiftId,
    val branchId: String,
    val actorUserId: String,
    val openedAt: Long,
    val isOpen: Boolean,
    val countedCashMinor: Long? = null,
    val countedAt: Long? = null,
    val closedAt: Long? = null
)
/**
 * Read-only reconciliation projection for one immutable cashier shift.
 *
 * All amounts are derived from authoritative operation-linked financial
 * evidence. This object is never persisted as financial truth.
 */
data class RegisterShiftReport(
    val shift: RegisterShiftResult,
    val operationCount: Int,

    val salesCount: Int,
    val grossSalesMinor: Long,

    val cashSalesMinor: Long,
    val onlineSalesMinor: Long,
    val couponUsedMinor: Long,
    val creditGivenMinor: Long,

    val cashCreditRepaymentsMinor: Long,
    val onlineCreditRepaymentsMinor: Long,
    val couponCreditRepaymentsMinor: Long,

    val couponIssuedMinor: Long,
    val couponTopUpMinor: Long,
    val couponRefundCreditsMinor: Long,

    val cashRefundsMinor: Long,
    val onlineRefundsMinor: Long,

    val refundCount: Int,
    val voidCount: Int,
    val correctionCount: Int,
    val paymentMethodChangeCount: Int,

    val expectedCashMinor: Long,

    /*
     * Read-only Expense reconciliation totals.
     *
     * These are derived from authoritative Expense +
     * EXPENSE_OUTFLOW settlement evidence for this shift.
     * They are never persisted as a second financial balance.
     */
    val cashExpensesMinor: Long = 0L,
    val onlineExpensesMinor: Long = 0L,

    val cashCouponFundingMinor: Long = 0L,
    val onlineCouponFundingMinor: Long = 0L,
    val cashLifecycleAdjustmentMinor: Long = 0L,
    val onlineLifecycleAdjustmentMinor: Long = 0L
) {
    val totalCreditRepaymentsMinor: Long
        get() =
            cashCreditRepaymentsMinor +
                onlineCreditRepaymentsMinor +
                couponCreditRepaymentsMinor

    val totalCashCollectedMinor: Long
        get() =
            cashSalesMinor +
                cashCreditRepaymentsMinor +
                cashCouponFundingMinor +
                cashLifecycleAdjustmentMinor

    val netCashMovementMinor: Long
        get() = expectedCashMinor

    val countedCashMinor: Long?
        get() = shift.countedCashMinor

    val cashDifferenceMinor: Long?
        get() =
            countedCashMinor?.let {
                it - expectedCashMinor
            }
}
