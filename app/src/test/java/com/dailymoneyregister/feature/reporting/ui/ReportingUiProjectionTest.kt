package com.dailymoneyregister.feature.reporting.ui

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.ExpenseReportRow
import com.dailymoneyregister.feature.reporting.domain.RefundReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportActivityFilter
import com.dailymoneyregister.feature.reporting.domain.ReportBranch
import com.dailymoneyregister.feature.reporting.domain.ReportCashier
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentFilter
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentPart
import com.dailymoneyregister.feature.reporting.domain.ReportPerson
import com.dailymoneyregister.feature.reporting.domain.ReportingActivityCounts
import com.dailymoneyregister.feature.reporting.domain.ReportingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingUiProjectionTest {

    private val branch =
        ReportBranch(
            "b1",
            "Main"
        )

    private val cashier =
        ReportCashier(
            "u1",
            "Admin"
        )

    private val person =
        ReportPerson(
            "c1",
            "Ahmed",
            "+96890000000"
        )

    @Test
    fun searchActivityAndPaymentFiltersUseExistingRowsOnly() {

        val refund =
            RefundReportRow(
                stableId = "refund:r1",
                operationId = "op1",
                transactionId = "tx1",
                reference = "REF-001",
                occurredAt = 1_000L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = person,
                refundEventId = "r1",
                amount =
                    MoneyMinorUnits(500L),
                reason = "Return",
                destinationParts =
                    listOf(
                        ReportPaymentPart(
                            PaymentCategory.CASH,
                            PaymentChannel.CASH_TENDER,
                            MoneyMinorUnits(500L)
                        )
                    )
            )

        val expense =
            ExpenseReportRow(
                stableId = "expense:e1",
                operationId = "op2",
                reference = "EXP-001",
                occurredAt = 2_000L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = null,
                expenseId = "e1",
                category = "Supplies",
                amount =
                    MoneyMinorUnits(250L),
                paymentCategory =
                    PaymentCategory.ONLINE,
                paymentChannel =
                    PaymentChannel.MANUAL_QR,
                note = "Paper",
                supportingDocumentCount = null
            )

        val filtered =
            ReportingUiProjection.filterRows(
                rows =
                    listOf(
                        refund,
                        expense
                    ),
                query = "Ahmed",
                activity =
                    ReportActivityFilter.REFUNDS,
                payment =
                    ReportPaymentFilter.CASH
            )

        assertEquals(
            listOf(refund),
            filtered
        )

        val projected = ReportingUiProjection.toRows(
            rows = listOf(refund, expense),
            decimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat"
        )
        assertEquals("- OMR 0.500", projected[0].amountText)
        assertEquals("- OMR 0.250", projected[1].amountText)
    }

    @Test
    fun summaryProjectionUsesAuthoritativeSummaryAndHidesDeferredFields() {

        val summary =
            ReportingSummary(
                periodSaleInflow =
                    MoneyMinorUnits(1_000L),
                periodRefundEffect =
                    MoneyMinorUnits(-100L),
                periodVoidEffect =
                    MoneyMinorUnits.ZERO,
                periodCorrectionEffect =
                    MoneyMinorUnits.ZERO,
                periodNetSalesActivity =
                    MoneyMinorUnits(900L),
                cashSalesCollected =
                    MoneyMinorUnits(600L),
                onlineSalesCollected =
                    MoneyMinorUnits(200L),
                cashInHand =
                    MoneyMinorUnits(650L),
                cashAtBank =
                    MoneyMinorUnits(225L),
                creditSalesOriginated =
                    MoneyMinorUnits(200L),
                cashCreditRepayments =
                    MoneyMinorUnits(50L),
                onlineCreditRepayments =
                    MoneyMinorUnits(25L),
                couponCreditRepayments =
                    MoneyMinorUnits(10L),
                creditOutstanding =
                    MoneyMinorUnits.ZERO,
                totalExpenses =
                    MoneyMinorUnits(40L),
                openingCash = null,
                expectedCash = null,
                counts =
                    ReportingActivityCounts()
            )

        val cards =
            ReportingUiProjection.summaryCards(
                summary = summary,
                decimalPlaces = 3,
                currencySymbol = "OMR"
            )

        assertEquals(
            8,
            cards.size
        )
        assertEquals("+ OMR 0.900", cards[0].amountText)
        assertEquals("+ OMR 0.650", cards[1].amountText)
        assertEquals("+ OMR 0.225", cards[2].amountText)
        assertEquals(ReportingSummaryMetric.CREDIT_OUTSTANDING, cards[5].metric)
        assertEquals("- OMR 0.100", cards[6].amountText)
        assertEquals("- OMR 0.040", cards[7].amountText)
    }
}