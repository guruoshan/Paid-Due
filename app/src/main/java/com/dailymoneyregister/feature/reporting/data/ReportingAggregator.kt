package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.AmountCorrectionReportRow
import com.dailymoneyregister.feature.reporting.domain.CouponReportEventKind
import com.dailymoneyregister.feature.reporting.domain.CouponReportRow
import com.dailymoneyregister.feature.reporting.domain.CreditRepaymentReportRow
import com.dailymoneyregister.feature.reporting.domain.ExpenseReportRow
import com.dailymoneyregister.feature.reporting.domain.PaymentMethodChangeReportRow
import com.dailymoneyregister.feature.reporting.domain.RefundReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportingActivityCounts
import com.dailymoneyregister.feature.reporting.domain.ReportingSummary
import com.dailymoneyregister.feature.reporting.domain.SaleReportRow
import com.dailymoneyregister.feature.reporting.domain.VoidReportRow

/**
 * Pure Reporting aggregation over already-authoritative read rows.
 *
 * No persisted state is owned here. Each lifecycle row contributes on its own
 * occurredAt, so later refunds/voids/corrections never rewrite an older sale.
 */
internal object ReportingAggregator {

    fun build(
        rows: List<ReportRow>,
        creditOutstanding: MoneyMinorUnits = MoneyMinorUnits.ZERO
    ): ReportingSummary {
        var saleInflow = 0L
        var refundEffect = 0L
        var voidEffect = 0L
        var correctionEffect = 0L

        var cashSales = 0L
        var onlineSales = 0L
        var creditSales = 0L

        var cashRepayments = 0L
        var onlineRepayments = 0L
        var couponRepayments = 0L

        var couponPurchased = 0L
        var couponToppedUp = 0L
        var couponRedeemed = 0L
        var couponRefunded = 0L

        var cashRefunded = 0L
        var onlineRefunded = 0L
        var cashExpenses = 0L
        var onlineExpenses = 0L
        var cashCouponReceipts = 0L
        var onlineCouponReceipts = 0L
        var cashVoided = 0L
        var onlineVoided = 0L
        var cashCorrectionDelta = 0L
        var onlineCorrectionDelta = 0L

        var salesCount = 0
        var repaymentCount = 0
        var couponCount = 0
        var refundCount = 0
        var expenseCount = 0
        var voidCount = 0
        var correctionCount = 0
        var paymentChangeCount = 0

        rows.forEach { row ->
            when (row) {
                is SaleReportRow -> {
                    salesCount = Math.addExact(salesCount, 1)
                    saleInflow = Math.addExact(saleInflow, row.originalAmount.amount)
                    row.paymentParts.forEach { part ->
                        when (part.category) {
                            PaymentCategory.CASH ->
                                cashSales = Math.addExact(cashSales, part.amount.amount)
                            PaymentCategory.ONLINE ->
                                onlineSales = Math.addExact(onlineSales, part.amount.amount)
                            PaymentCategory.CREDIT ->
                                creditSales = Math.addExact(creditSales, part.amount.amount)
                            PaymentCategory.COUPON -> Unit
                        }
                    }
                }

                is CreditRepaymentReportRow -> {
                    repaymentCount = Math.addExact(repaymentCount, 1)
                    when (row.paymentCategory) {
                        PaymentCategory.CASH ->
                            cashRepayments = Math.addExact(cashRepayments, row.amount.amount)
                        PaymentCategory.ONLINE ->
                            onlineRepayments = Math.addExact(onlineRepayments, row.amount.amount)
                        PaymentCategory.COUPON ->
                            couponRepayments = Math.addExact(couponRepayments, row.amount.amount)
                        PaymentCategory.CREDIT ->
                            error("Credit cannot fund a credit repayment.")
                    }
                }

                is CouponReportRow -> {
                    couponCount = Math.addExact(couponCount, 1)
                    when (row.eventKind) {
                        CouponReportEventKind.PURCHASE -> {
                            couponPurchased = Math.addExact(couponPurchased, row.signedAmount.amount)
                            when (row.fundingCategory) {
                                PaymentCategory.CASH ->
                                    cashCouponReceipts = Math.addExact(cashCouponReceipts, row.signedAmount.amount)
                                PaymentCategory.ONLINE ->
                                    onlineCouponReceipts = Math.addExact(onlineCouponReceipts, row.signedAmount.amount)
                                PaymentCategory.CREDIT, PaymentCategory.COUPON, null -> Unit
                            }
                        }
                        CouponReportEventKind.TOP_UP -> {
                            couponToppedUp = Math.addExact(couponToppedUp, row.signedAmount.amount)
                            when (row.fundingCategory) {
                                PaymentCategory.CASH ->
                                    cashCouponReceipts = Math.addExact(cashCouponReceipts, row.signedAmount.amount)
                                PaymentCategory.ONLINE ->
                                    onlineCouponReceipts = Math.addExact(onlineCouponReceipts, row.signedAmount.amount)
                                PaymentCategory.CREDIT, PaymentCategory.COUPON, null -> Unit
                            }
                        }
                        CouponReportEventKind.REDEMPTION ->
                            couponRedeemed = Math.addExact(
                                couponRedeemed,
                                Math.negateExact(row.signedAmount.amount)
                            )
                        CouponReportEventKind.REFUND_CREDIT ->
                            couponRefunded = Math.addExact(couponRefunded, row.signedAmount.amount)
                        CouponReportEventKind.PROMOTION_BONUS -> Unit
                        CouponReportEventKind.CREDIT_REPAYMENT -> Unit
                    }
                }

                is RefundReportRow -> {
                    refundCount = Math.addExact(refundCount, 1)
                    refundEffect = Math.subtractExact(refundEffect, row.amount.amount)
                    row.destinationParts.forEach { part ->
                        when (part.category) {
                            PaymentCategory.CASH ->
                                cashRefunded = Math.addExact(cashRefunded, part.amount.amount)
                            PaymentCategory.ONLINE ->
                                onlineRefunded = Math.addExact(onlineRefunded, part.amount.amount)
                            PaymentCategory.COUPON,
                            PaymentCategory.CREDIT -> Unit
                        }
                    }
                }

                is ExpenseReportRow -> {
                    expenseCount = Math.addExact(expenseCount, 1)
                    when (row.paymentCategory) {
                        PaymentCategory.CASH ->
                            cashExpenses = Math.addExact(cashExpenses, row.amount.amount)
                        PaymentCategory.ONLINE ->
                            onlineExpenses = Math.addExact(onlineExpenses, row.amount.amount)
                        PaymentCategory.CREDIT,
                        PaymentCategory.COUPON ->
                            error("Unsupported Expense payment category in Reporting.")
                    }
                }

                is VoidReportRow -> {
                    voidCount = Math.addExact(voidCount, 1)
                    voidEffect = Math.subtractExact(voidEffect, row.affectedAmount.amount)
                    row.reversalParts.forEach { part ->
                        when (part.category) {
                            PaymentCategory.CASH ->
                                cashVoided = Math.addExact(cashVoided, part.amount.amount)
                            PaymentCategory.ONLINE ->
                                onlineVoided = Math.addExact(onlineVoided, part.amount.amount)
                            PaymentCategory.CREDIT, PaymentCategory.COUPON -> Unit
                        }
                    }
                }

                is AmountCorrectionReportRow -> {
                    correctionCount = Math.addExact(correctionCount, 1)
                    correctionEffect = Math.addExact(
                        correctionEffect,
                        row.signedDifference.amount
                    )
                    val correctionSign = when {
                        row.signedDifference.amount > 0L -> 1L
                        row.signedDifference.amount < 0L -> -1L
                        else -> 0L
                    }
                    row.fundingParts.forEach { part ->
                        val signedTenderDelta = Math.multiplyExact(
                            part.amount.amount,
                            correctionSign
                        )
                        when (part.category) {
                            PaymentCategory.CASH ->
                                cashCorrectionDelta = Math.addExact(cashCorrectionDelta, signedTenderDelta)
                            PaymentCategory.ONLINE ->
                                onlineCorrectionDelta = Math.addExact(onlineCorrectionDelta, signedTenderDelta)
                            PaymentCategory.CREDIT, PaymentCategory.COUPON -> Unit
                        }
                    }
                }

                is PaymentMethodChangeReportRow -> {
                    paymentChangeCount = Math.addExact(paymentChangeCount, 1)
                    // Classification/audit activity only: zero sales-revenue effect.
                }
            }
        }

        val netSalesActivity = Math.addExact(
            Math.addExact(saleInflow, refundEffect),
            Math.addExact(voidEffect, correctionEffect)
        )

        val cashInHand = Math.subtractExact(
            Math.subtractExact(
                Math.addExact(Math.addExact(Math.addExact(cashSales, cashRepayments), cashCouponReceipts), cashCorrectionDelta),
                cashRefunded
            ),
            Math.addExact(cashExpenses, cashVoided)
        )
        val cashAtBank = Math.subtractExact(
            Math.subtractExact(
                Math.addExact(Math.addExact(Math.addExact(onlineSales, onlineRepayments), onlineCouponReceipts), onlineCorrectionDelta),
                onlineRefunded
            ),
            Math.addExact(onlineExpenses, onlineVoided)
        )

        return ReportingSummary(
            periodSaleInflow = MoneyMinorUnits(saleInflow),
            periodRefundEffect = MoneyMinorUnits(refundEffect),
            periodVoidEffect = MoneyMinorUnits(voidEffect),
            periodCorrectionEffect = MoneyMinorUnits(correctionEffect),
            periodNetSalesActivity = MoneyMinorUnits(netSalesActivity),
            cashSalesCollected = MoneyMinorUnits(cashSales),
            onlineSalesCollected = MoneyMinorUnits(onlineSales),
            creditSalesOriginated = MoneyMinorUnits(creditSales),
            cashCreditRepayments = MoneyMinorUnits(cashRepayments),
            onlineCreditRepayments = MoneyMinorUnits(onlineRepayments),
            couponCreditRepayments = MoneyMinorUnits(couponRepayments),
            creditOutstanding = creditOutstanding,
            couponPurchased = MoneyMinorUnits(couponPurchased),
            couponToppedUp = MoneyMinorUnits(couponToppedUp),
            couponRedeemed = MoneyMinorUnits(couponRedeemed),
            couponRefunded = MoneyMinorUnits(couponRefunded),
            cashRefunded = MoneyMinorUnits(cashRefunded),
            onlineRefunded = MoneyMinorUnits(onlineRefunded),
            cashExpenses = MoneyMinorUnits(cashExpenses),
            onlineExpenses = MoneyMinorUnits(onlineExpenses),
            totalExpenses = MoneyMinorUnits(Math.addExact(cashExpenses, onlineExpenses)),
            cashInHand = MoneyMinorUnits(cashInHand),
            cashAtBank = MoneyMinorUnits(cashAtBank),
            counts = ReportingActivityCounts(
                sales = salesCount,
                creditRepayments = repaymentCount,
                couponEvents = couponCount,
                refunds = refundCount,
                expenses = expenseCount,
                voids = voidCount,
                corrections = correctionCount,
                paymentMethodChanges = paymentChangeCount
            )
        )
    }
}