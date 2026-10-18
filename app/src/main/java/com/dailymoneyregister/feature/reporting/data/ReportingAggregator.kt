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
        validateRows(rows)

        var saleInflow = 0L
        var refundEffect = 0L
        var voidEffect = 0L
        var correctionEffect = 0L

        var cashSales = 0L
        var onlineSales = 0L
        var couponSales = 0L
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
                            PaymentCategory.COUPON ->
                                couponSales = Math.addExact(couponSales, part.amount.amount)
                        }
                    }
                }

                is CreditRepaymentReportRow -> {
                    repaymentCount = Math.addExact(repaymentCount, 1)
                    val parts = row.paymentParts.ifEmpty {
                        listOfNotNull(
                            row.paymentChannel?.let { channel ->
                                com.dailymoneyregister.feature.reporting.domain.ReportPaymentPart(
                                    row.paymentCategory,
                                    channel,
                                    row.amount
                                )
                            }
                        )
                    }
                    check(parts.isNotEmpty()) {
                        "Repayment ${row.repaymentId} has no reportable tender evidence."
                    }
                    check(parts.fold(0L) { total, part -> Math.addExact(total, part.amount.amount) } == row.amount.amount) {
                        "Repayment ${row.repaymentId} tender parts do not equal its amount."
                    }
                    parts.forEach { part ->
                        when (part.category) {
                            PaymentCategory.CASH ->
                                cashRepayments = Math.addExact(cashRepayments, part.amount.amount)
                            PaymentCategory.ONLINE ->
                                onlineRepayments = Math.addExact(onlineRepayments, part.amount.amount)
                            PaymentCategory.COUPON ->
                                couponRepayments = Math.addExact(couponRepayments, part.amount.amount)
                            PaymentCategory.CREDIT ->
                                error("Credit cannot fund a credit repayment.")
                        }
                    }
                }

                is CouponReportRow -> {
                    couponCount = Math.addExact(couponCount, 1)
                    when (row.eventKind) {
                        CouponReportEventKind.PURCHASE ->
                            couponPurchased = Math.addExact(couponPurchased, row.signedAmount.amount)
                        CouponReportEventKind.TOP_UP ->
                            couponToppedUp = Math.addExact(couponToppedUp, row.signedAmount.amount)
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
                }

                is AmountCorrectionReportRow -> {
                    correctionCount = Math.addExact(correctionCount, 1)
                    correctionEffect = Math.addExact(
                        correctionEffect,
                        row.signedDifference.amount
                    )
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

        return ReportingSummary(
            periodSaleInflow = MoneyMinorUnits(saleInflow),
            periodRefundEffect = MoneyMinorUnits(refundEffect),
            periodVoidEffect = MoneyMinorUnits(voidEffect),
            periodCorrectionEffect = MoneyMinorUnits(correctionEffect),
            periodNetSalesActivity = MoneyMinorUnits(netSalesActivity),
            cashSalesCollected = MoneyMinorUnits(cashSales),
            onlineSalesCollected = MoneyMinorUnits(onlineSales),
            couponSalesCollected = MoneyMinorUnits(couponSales),
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

    private fun validateRows(rows: List<ReportRow>) {
        check(rows.map { it.stableId }.distinct().size == rows.size) {
            "Reporting input contains duplicate stable row identities."
        }

        rows.filterIsInstance<SaleReportRow>().forEach { row ->
            check(!row.operationId.isNullOrBlank()) {
                "Sale ${row.transactionId} has no authoritative operation identity."
            }
            check(row.paymentParts.isNotEmpty()) {
                "Sale ${row.transactionId} has no authoritative tender breakdown."
            }
            check(row.paymentParts.fold(0L) { total, part ->
                Math.addExact(total, part.amount.amount)
            } == row.originalAmount.amount) {
                "Sale ${row.transactionId} tender total differs from its original amount."
            }
        }

        rows.filterIsInstance<RefundReportRow>().forEach { row ->
            check(row.destinationParts.isNotEmpty()) {
                "Refund ${row.refundEventId} has no authoritative destination breakdown."
            }
            check(row.destinationParts.fold(0L) { total, part ->
                Math.addExact(total, part.amount.amount)
            } == row.amount.amount) {
                "Refund ${row.refundEventId} destinations differ from its amount."
            }
        }

        rows.filterIsInstance<VoidReportRow>().forEach { row ->
            check(row.affectedAmount.amount > 0L) {
                "Void ${row.eventId} has no positive event-specific reversal amount."
            }
        }

        rows.filterIsInstance<CreditRepaymentReportRow>().forEach { row ->
            check(row.paymentParts.isNotEmpty()) {
                "Repayment ${row.repaymentId} has no authoritative tender breakdown."
            }
            check(row.paymentParts.fold(0L) { total, part ->
                Math.addExact(total, part.amount.amount)
            } == row.amount.amount) {
                "Repayment ${row.repaymentId} tender parts differ from its amount."
            }
            check(row.allocations.fold(0L) { total, allocation ->
                Math.addExact(total, allocation.amount.amount)
            } == row.amount.amount) {
                "Repayment ${row.repaymentId} allocations differ from its amount."
            }
        }

        rows.filterIsInstance<CouponReportRow>().forEach { row ->
            if (
                row.eventKind == CouponReportEventKind.PURCHASE ||
                row.eventKind == CouponReportEventKind.TOP_UP
            ) {
                check(row.fundingCategory != null && row.fundingChannel != null) {
                    "Coupon funding event ${row.ledgerEventId} has no authoritative funding identity."
                }
            }
        }

        rows.filterIsInstance<AmountCorrectionReportRow>()
            .groupBy { it.transactionId }
            .values
            .forEach { chain ->
                val ordered = chain.sortedWith(compareBy<AmountCorrectionReportRow> { it.occurredAt }.thenBy { it.eventId })
                ordered.zipWithNext().forEach { (previous, next) ->
                    check(previous.correctedAmount.amount == next.previousAmount.amount) {
                        "Correction chain is discontinuous between ${previous.eventId} and ${next.eventId}."
                    }
                }
            }
    }
}