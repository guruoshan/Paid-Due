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


/**
 * Immutable display summary attached to a confirmed shift-close operation.
 * It is evidence for the admin notification, not a replacement financial ledger.
 */
data class RegisterShiftCloseReportSnapshot(
    val shiftId: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val currencySymbol: String,
    val operationCount: Int,
    val salesCount: Int,
    val grossSalesMinor: Long,
    val cashSalesMinor: Long,
    val onlineSalesMinor: Long,
    val creditGivenMinor: Long,
    val couponUsedMinor: Long,
    val cashCreditRepaymentsMinor: Long,
    val onlineCreditRepaymentsMinor: Long,
    val couponCreditRepaymentsMinor: Long,
    val couponIssuedMinor: Long,
    val couponTopUpMinor: Long,
    val couponRefundCreditsMinor: Long,
    val cashRefundsMinor: Long,
    val onlineRefundsMinor: Long,
    val cashExpensesMinor: Long,
    val onlineExpensesMinor: Long,
    val cashCouponFundingMinor: Long,
    val onlineCouponFundingMinor: Long,
    val cashLifecycleAdjustmentMinor: Long,
    val onlineLifecycleAdjustmentMinor: Long,
    val refundCount: Int,
    val voidCount: Int,
    val correctionCount: Int,
    val paymentMethodChangeCount: Int,
    val expectedCashMinor: Long,
    val countedCashMinor: Long,
    val differenceMinor: Long,
    val cashierUserId: String,
    val closedByUserId: String,
    val closedAt: Long
) {
    fun toJsonString(): String = org.json.JSONObject()
        .put("shift_id", shiftId)
        .put("currency_code", currencyCode)
        .put("currency_decimal_places", currencyDecimalPlaces)
        .put("currency_symbol", currencySymbol)
        .put("operation_count", operationCount)
        .put("sales_count", salesCount)
        .put("gross_sales_minor", grossSalesMinor)
        .put("cash_sales_minor", cashSalesMinor)
        .put("online_sales_minor", onlineSalesMinor)
        .put("credit_given_minor", creditGivenMinor)
        .put("coupon_used_minor", couponUsedMinor)
        .put("cash_credit_repayments_minor", cashCreditRepaymentsMinor)
        .put("online_credit_repayments_minor", onlineCreditRepaymentsMinor)
        .put("coupon_credit_repayments_minor", couponCreditRepaymentsMinor)
        .put("coupon_issued_minor", couponIssuedMinor)
        .put("coupon_top_up_minor", couponTopUpMinor)
        .put("coupon_refund_credits_minor", couponRefundCreditsMinor)
        .put("cash_refunds_minor", cashRefundsMinor)
        .put("online_refunds_minor", onlineRefundsMinor)
        .put("cash_expenses_minor", cashExpensesMinor)
        .put("online_expenses_minor", onlineExpensesMinor)
        .put("cash_coupon_funding_minor", cashCouponFundingMinor)
        .put("online_coupon_funding_minor", onlineCouponFundingMinor)
        .put("cash_lifecycle_adjustment_minor", cashLifecycleAdjustmentMinor)
        .put("online_lifecycle_adjustment_minor", onlineLifecycleAdjustmentMinor)
        .put("refund_count", refundCount)
        .put("void_count", voidCount)
        .put("correction_count", correctionCount)
        .put("payment_method_change_count", paymentMethodChangeCount)
        .put("expected_cash_minor", expectedCashMinor)
        .put("counted_cash_minor", countedCashMinor)
        .put("difference_minor", differenceMinor)
        .put("cashier_user_id", cashierUserId)
        .put("closed_by_user_id", closedByUserId)
        .put("closed_at", closedAt)
        .toString()

    companion object {
        fun fromJsonString(value: String): RegisterShiftCloseReportSnapshot {
            val j = org.json.JSONObject(value)
            return RegisterShiftCloseReportSnapshot(
                shiftId = j.getString("shift_id"),
                currencyCode = j.getString("currency_code"),
                currencyDecimalPlaces = j.getInt("currency_decimal_places"),
                currencySymbol = j.optString("currency_symbol", ""),
                operationCount = j.optInt("operation_count"),
                salesCount = j.optInt("sales_count"),
                grossSalesMinor = j.optLong("gross_sales_minor"),
                cashSalesMinor = j.optLong("cash_sales_minor"),
                onlineSalesMinor = j.optLong("online_sales_minor"),
                creditGivenMinor = j.optLong("credit_given_minor"),
                couponUsedMinor = j.optLong("coupon_used_minor"),
                cashCreditRepaymentsMinor = j.optLong("cash_credit_repayments_minor"),
                onlineCreditRepaymentsMinor = j.optLong("online_credit_repayments_minor"),
                couponCreditRepaymentsMinor = j.optLong("coupon_credit_repayments_minor"),
                couponIssuedMinor = j.optLong("coupon_issued_minor"),
                couponTopUpMinor = j.optLong("coupon_top_up_minor"),
                couponRefundCreditsMinor = j.optLong("coupon_refund_credits_minor"),
                cashRefundsMinor = j.optLong("cash_refunds_minor"),
                onlineRefundsMinor = j.optLong("online_refunds_minor"),
                cashExpensesMinor = j.optLong("cash_expenses_minor"),
                onlineExpensesMinor = j.optLong("online_expenses_minor"),
                cashCouponFundingMinor = j.optLong("cash_coupon_funding_minor"),
                onlineCouponFundingMinor = j.optLong("online_coupon_funding_minor"),
                cashLifecycleAdjustmentMinor = j.optLong("cash_lifecycle_adjustment_minor"),
                onlineLifecycleAdjustmentMinor = j.optLong("online_lifecycle_adjustment_minor"),
                refundCount = j.optInt("refund_count"),
                voidCount = j.optInt("void_count"),
                correctionCount = j.optInt("correction_count"),
                paymentMethodChangeCount = j.optInt("payment_method_change_count"),
                expectedCashMinor = j.getLong("expected_cash_minor"),
                countedCashMinor = j.getLong("counted_cash_minor"),
                differenceMinor = j.getLong("difference_minor"),
                cashierUserId = j.getString("cashier_user_id"),
                closedByUserId = j.getString("closed_by_user_id"),
                closedAt = j.getLong("closed_at")
            )
        }
    }
}
