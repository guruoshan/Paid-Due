package com.dailymoneyregister.feature.reporting.ui

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.AmountCorrectionReportRow
import com.dailymoneyregister.feature.reporting.domain.CouponReportRow
import com.dailymoneyregister.feature.reporting.domain.CreditRepaymentReportRow
import com.dailymoneyregister.feature.reporting.domain.ExpenseReportRow
import com.dailymoneyregister.feature.reporting.domain.PaymentMethodChangeReportRow
import com.dailymoneyregister.feature.reporting.domain.RefundReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportActivityFilter
import com.dailymoneyregister.feature.reporting.domain.ReportActivityType
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentFilter
import com.dailymoneyregister.feature.reporting.domain.ReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportingSummary
import com.dailymoneyregister.feature.reporting.domain.SaleReportRow
import com.dailymoneyregister.feature.reporting.domain.VoidReportRow
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ReportingRowUiModel(
    val stableId: String,
    val activityType: ReportActivityType,
    val amountText: String?,
    val referenceText: String?,
    val customerName: String?,
    val customerPhone: String?,
    val customerId: String?,
    val branchName: String?,
    val cashierName: String?,
    val occurredAtText: String,
    val paymentText: String?,
    val reasonText: String?
)

enum class ReportingSummaryMetric {
    NET_SALES_ACTIVITY,
    CASH_IN_HAND,
    CASH_AT_BANK,
    CREDIT_SALES,
    CREDIT_REPAYMENTS,
    REFUNDS,
    EXPENSES
}

data class ReportingSummaryUiModel(
    val metric: ReportingSummaryMetric,
    val amountText: String
)

object ReportingUiProjection {

    private val dateTimeFormatter =
        DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm"
        )

    fun filterRows(
        rows: List<ReportRow>,
        query: String,
        activity: ReportActivityFilter,
        payment: ReportPaymentFilter
    ): List<ReportRow> {

        val normalized =
            query.trim().lowercase()

        return rows.filter { row ->
            matchesActivity(row, activity) &&
                matchesPayment(row, payment) &&
                matchesQuery(row, normalized)
        }
    }

    fun toRows(
        rows: List<ReportRow>,
        decimalPlaces: Int,
        currencySymbol: String,
        timezoneId: String
    ): List<ReportingRowUiModel> =
        rows.map { row ->

            val amount =
                when (row) {
                    is SaleReportRow ->
                        row.originalAmount

                    is CreditRepaymentReportRow ->
                        row.amount

                    is CouponReportRow ->
                        row.signedAmount

                    is RefundReportRow ->
                        MoneyMinorUnits(
                            Math.negateExact(
                                row.amount.amount
                            )
                        )

                    is ExpenseReportRow ->
                        MoneyMinorUnits(
                            Math.negateExact(
                                row.amount.amount
                            )
                        )

                    is VoidReportRow ->
                        MoneyMinorUnits(
                            Math.negateExact(
                                row.affectedAmount.amount
                            )
                        )

                    is AmountCorrectionReportRow ->
                        row.signedDifference

                    is PaymentMethodChangeReportRow ->
                        null
                }

            val paymentText =
                paymentCategories(row)
                    .joinToString(" + ") {
                        it.name
                    }
                    .ifBlank {
                        null
                    }

            val reason =
                when (row) {
                    is RefundReportRow ->
                        row.reason

                    is ExpenseReportRow ->
                        row.note

                    is VoidReportRow ->
                        row.reason

                    is AmountCorrectionReportRow ->
                        row.reason

                    is PaymentMethodChangeReportRow ->
                        row.reason

                    else ->
                        null
                }

            ReportingRowUiModel(
                stableId = row.stableId,
                activityType = row.activityType,
                amountText =
                    amount?.let {
                        val magnitude = formatMagnitude(
                            it,
                            decimalPlaces,
                            currencySymbol
                        )
                        when {
                            it.amount > 0L -> "+ $magnitude"
                            it.amount < 0L -> "- $magnitude"
                            else -> magnitude
                        }
                    },
                referenceText =
                    row.reference
                        ?: row.transactionId
                        ?: row.operationId,
                customerName =
                    row.person?.name,
                customerPhone =
                    row.person?.phone,
                customerId =
                    row.person?.id,
                branchName =
                    row.branch.name,
                cashierName =
                    row.cashier.displayName,
                occurredAtText =
                    formatOccurredAt(
                        row.occurredAt,
                        timezoneId
                    ),
                paymentText =
                    paymentText,
                reasonText =
                    reason
            )
        }

    fun summaryCards(
        summary: ReportingSummary,
        decimalPlaces: Int,
        currencySymbol: String
    ): List<ReportingSummaryUiModel> {

        val repaymentTotal =
            MoneyMinorUnits(
                Math.addExact(
                    Math.addExact(
                        summary.cashCreditRepayments.amount,
                        summary.onlineCreditRepayments.amount
                    ),
                    summary.couponCreditRepayments.amount
                )
            )

        return listOf(
            ReportingSummaryUiModel(
                ReportingSummaryMetric.NET_SALES_ACTIVITY,
                formatSignedMoney(
                    summary.periodNetSalesActivity,
                    decimalPlaces,
                    currencySymbol
                )
            ),
            ReportingSummaryUiModel(
                ReportingSummaryMetric.CASH_IN_HAND,
                formatSignedMoney(
                    summary.cashInHand,
                    decimalPlaces,
                    currencySymbol
                )
            ),
            ReportingSummaryUiModel(
                ReportingSummaryMetric.CASH_AT_BANK,
                formatSignedMoney(
                    summary.cashAtBank,
                    decimalPlaces,
                    currencySymbol
                )
            ),
            ReportingSummaryUiModel(
                ReportingSummaryMetric.CREDIT_SALES,
                formatSignedMoney(
                    summary.creditSalesOriginated,
                    decimalPlaces,
                    currencySymbol
                )
            ),
            ReportingSummaryUiModel(
                ReportingSummaryMetric.CREDIT_REPAYMENTS,
                formatSignedMoney(
                    repaymentTotal,
                    decimalPlaces,
                    currencySymbol
                )
            ),
            ReportingSummaryUiModel(
                ReportingSummaryMetric.REFUNDS,
                formatSignedMoney(
                    summary.periodRefundEffect,
                    decimalPlaces,
                    currencySymbol
                )
            ),
            ReportingSummaryUiModel(
                ReportingSummaryMetric.EXPENSES,
                formatSignedMoney(
                    MoneyMinorUnits(
                        Math.negateExact(
                            summary.totalExpenses.amount
                        )
                    ),
                    decimalPlaces,
                    currencySymbol
                )
            )
        )
    }

    private fun matchesActivity(
        row: ReportRow,
        filter: ReportActivityFilter
    ): Boolean =
        when (filter) {
            ReportActivityFilter.ALL ->
                true

            ReportActivityFilter.SALES ->
                row.activityType ==
                    ReportActivityType.SALE

            ReportActivityFilter.CREDIT ->
                row.activityType ==
                    ReportActivityType.CREDIT_SALE

            ReportActivityFilter.CREDIT_REPAYMENTS ->
                row.activityType ==
                    ReportActivityType.CREDIT_REPAYMENT

            ReportActivityFilter.COUPON ->
                row.activityType in setOf(
                    ReportActivityType.COUPON_PURCHASE,
                    ReportActivityType.COUPON_TOP_UP,
                    ReportActivityType.COUPON_REDEMPTION,
                    ReportActivityType.COUPON_REFUND,
                    ReportActivityType.COUPON_BONUS,
                    ReportActivityType.COUPON_CREDIT_REPAYMENT
                )

            ReportActivityFilter.REFUNDS ->
                row.activityType ==
                    ReportActivityType.REFUND

            ReportActivityFilter.EXPENSES ->
                row.activityType ==
                    ReportActivityType.EXPENSE

            ReportActivityFilter.VOIDS ->
                row.activityType ==
                    ReportActivityType.VOID

            ReportActivityFilter.CORRECTIONS ->
                row.activityType ==
                    ReportActivityType.AMOUNT_CORRECTION

            ReportActivityFilter.PAYMENT_METHOD_CHANGES ->
                row.activityType ==
                    ReportActivityType.PAYMENT_METHOD_CHANGE
        }

    private fun matchesPayment(
        row: ReportRow,
        filter: ReportPaymentFilter
    ): Boolean {

        if (filter == ReportPaymentFilter.ALL) {
            return true
        }

        val required =
            when (filter) {
                ReportPaymentFilter.CASH ->
                    PaymentCategory.CASH

                ReportPaymentFilter.ONLINE ->
                    PaymentCategory.ONLINE

                ReportPaymentFilter.CREDIT ->
                    PaymentCategory.CREDIT

                ReportPaymentFilter.COUPON ->
                    PaymentCategory.COUPON

                ReportPaymentFilter.ALL ->
                    return true
            }

        return required in paymentCategories(row)
    }

    private fun paymentCategories(
        row: ReportRow
    ): Set<PaymentCategory> =
        when (row) {
            is SaleReportRow ->
                row.paymentParts
                    .map { it.category }
                    .toSet()

            is CreditRepaymentReportRow ->
                setOf(
                    row.paymentCategory
                )

            is CouponReportRow ->
                setOfNotNull(
                    row.fundingCategory
                        ?: PaymentCategory.COUPON
                )

            is RefundReportRow ->
                row.destinationParts
                    .map { it.category }
                    .toSet()

            is ExpenseReportRow ->
                setOf(
                    row.paymentCategory
                )

            is PaymentMethodChangeReportRow ->
                setOf(
                    row.previousCategory,
                    row.newCategory
                )

            is VoidReportRow,
            is AmountCorrectionReportRow ->
                emptySet()
        }

    private fun matchesQuery(
        row: ReportRow,
        normalizedQuery: String
    ): Boolean {

        if (normalizedQuery.isBlank()) {
            return true
        }

        return listOfNotNull(
            row.reference,
            row.transactionId,
            row.operationId,
            row.person?.name,
            row.person?.phone,
            row.branch.name,
            row.cashier.displayName
        ).any {
            it.lowercase()
                .contains(normalizedQuery)
        }
    }

    private fun formatMagnitude(
        value: MoneyMinorUnits,
        decimalPlaces: Int,
        currencySymbol: String
    ): String {
        require(decimalPlaces >= 0)
        val number = BigDecimal.valueOf(value.amount, decimalPlaces)
            .abs()
            .toPlainString()
        return if (currencySymbol.isBlank()) number else "$currencySymbol $number"
    }

    private fun formatSignedMoney(
        value: MoneyMinorUnits,
        decimalPlaces: Int,
        currencySymbol: String
    ): String {
        val magnitude = formatMagnitude(
            value,
            decimalPlaces,
            currencySymbol
        )
        return when {
            value.amount > 0L -> "+ $magnitude"
            value.amount < 0L -> "- $magnitude"
            else -> magnitude
        }
    }

    private fun formatOccurredAt(
        occurredAt: Long,
        timezoneId: String
    ): String =
        Instant.ofEpochMilli(occurredAt)
            .atZone(
                ZoneId.of(timezoneId)
            )
            .format(dateTimeFormatter)
}