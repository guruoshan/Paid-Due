package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.AmountCorrectionReportRow
import com.dailymoneyregister.feature.reporting.domain.CouponReportEventKind
import com.dailymoneyregister.feature.reporting.domain.CouponReportRow
import com.dailymoneyregister.feature.reporting.domain.CreditRepaymentReportRow
import com.dailymoneyregister.feature.reporting.domain.ExpenseReportRow
import com.dailymoneyregister.feature.reporting.domain.PaymentMethodChangeReportRow
import com.dailymoneyregister.feature.reporting.domain.RefundReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportBranch
import com.dailymoneyregister.feature.reporting.domain.ReportCashier
import com.dailymoneyregister.feature.reporting.domain.ReportLifecycleStatus
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentPart
import com.dailymoneyregister.feature.reporting.domain.ReportPerson
import com.dailymoneyregister.feature.reporting.domain.SaleReportRow
import com.dailymoneyregister.feature.reporting.domain.VoidReportRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingAggregatorTest {

    private val branch = ReportBranch("branch-1", "Main")
    private val cashier = ReportCashier("admin-1", "Admin")
    private val person = ReportPerson("customer-1", "Customer", "+96890000000")

    private fun money(value: Long) = MoneyMinorUnits(value)

    private fun part(
        category: PaymentCategory,
        channel: PaymentChannel,
        amount: Long
    ) = ReportPaymentPart(category, channel, money(amount))

    private fun sale(
        id: String = "sale-1",
        amount: Long = 1_000L,
        paymentParts: List<ReportPaymentPart> =
            listOf(part(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, amount))
    ) = SaleReportRow(
        stableId = "sale:$id",
        operationId = "operation-$id",
        transactionId = id,
        reference = "REF-$id",
        occurredAt = 100L,
        currencyCode = "OMR",
        branch = branch,
        cashier = cashier,
        person = person,
        subtotal = money(amount),
        taxAmount = money(0L),
        shippingAmount = money(0L),
        packingAmount = money(0L),
        originalAmount = money(amount),
        lifetimeCurrentAmount = money(amount),
        lifetimeRefundedAmount = money(0L),
        paymentParts = paymentParts,
        items = emptyList(),
        lifecycleStatus = ReportLifecycleStatus.NORMAL,
        isCreditSale = paymentParts.any { it.category == PaymentCategory.CREDIT }
    )

    @Test
    fun saleRefundVoidCorrectionAndPaymentChangePreservePeriodSemantics() {
        val rows = listOf(
            sale(
                amount = 1_000L,
                paymentParts = listOf(
                    part(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 600L),
                    part(PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR, 200L),
                    part(PaymentCategory.CREDIT, PaymentChannel.CREDIT_ACCOUNT, 200L)
                )
            ),
            RefundReportRow(
                stableId = "refund:r1",
                operationId = "refund-op",
                transactionId = "old-sale",
                reference = "OLD",
                occurredAt = 200L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = person,
                refundEventId = "r1",
                amount = money(100L),
                reason = "Return",
                destinationParts = listOf(
                    part(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 100L)
                )
            ),
            VoidReportRow(
                stableId = "void:v1",
                operationId = "void-op",
                transactionId = "old-sale-2",
                reference = "OLD2",
                occurredAt = 300L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = person,
                eventId = "v1",
                affectedAmount = money(50L),
                reason = "Void",
                reversalParts = listOf(
                    part(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 50L)
                )
            ),
            AmountCorrectionReportRow(
                stableId = "correction:c1",
                operationId = "correction-op",
                transactionId = "old-sale-3",
                reference = "OLD3",
                occurredAt = 400L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = person,
                eventId = "c1",
                previousAmount = money(1_000L),
                correctedAmount = money(1_100L),
                reason = "Correction",
                fundingParts = listOf(
                    part(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 100L)
                )
            ),
            PaymentMethodChangeReportRow(
                stableId = "paymentchange:p1",
                operationId = "payment-op",
                transactionId = "old-sale-4",
                reference = "OLD4",
                occurredAt = 500L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = person,
                eventId = "p1",
                previousCategory = PaymentCategory.CASH,
                previousChannel = PaymentChannel.CASH_TENDER,
                newCategory = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.MANUAL_QR,
                reason = "Reclassified"
            )
        )

        val summary = ReportingAggregator.build(rows)

        assertEquals(1_000L, summary.periodSaleInflow.amount)
        assertEquals(-100L, summary.periodRefundEffect.amount)
        assertEquals(-50L, summary.periodVoidEffect.amount)
        assertEquals(100L, summary.periodCorrectionEffect.amount)
        assertEquals(950L, summary.periodNetSalesActivity.amount)
        assertEquals(600L, summary.cashSalesCollected.amount)
        assertEquals(200L, summary.onlineSalesCollected.amount)
        assertEquals(200L, summary.creditSalesOriginated.amount)
        assertEquals(550L, summary.cashInHand.amount)
        assertEquals(200L, summary.cashAtBank.amount)
        assertEquals(100L, summary.cashRefunded.amount)
        assertEquals(1, summary.counts.sales)
        assertEquals(1, summary.counts.refunds)
        assertEquals(1, summary.counts.voids)
        assertEquals(1, summary.counts.corrections)
        assertEquals(1, summary.counts.paymentMethodChanges)
    }

    @Test
    fun negativeCorrectionCanMakePeriodActivityNegativeWithoutRewritingSale() {
        val correction = AmountCorrectionReportRow(
            stableId = "correction:down",
            operationId = "op-down",
            transactionId = "old-sale",
            reference = "OLD",
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = person,
            eventId = "down",
            previousAmount = money(1_000L),
            correctedAmount = money(700L),
            reason = null
        )
        val summary = ReportingAggregator.build(listOf(correction))
        assertEquals(-300L, summary.periodCorrectionEffect.amount)
        assertEquals(-300L, summary.periodNetSalesActivity.amount)
        assertEquals(0L, summary.periodSaleInflow.amount)
    }

    @Test
    fun creditRepaymentsAreCollectionsNeverRevenue() {
        fun repayment(
            id: String,
            amount: Long,
            category: PaymentCategory,
            channel: PaymentChannel
        ) = CreditRepaymentReportRow(
            stableId = "repayment:$id",
            operationId = "operation-$id",
            transactionId = null,
            reference = null,
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = person,
            repaymentId = id,
            amount = money(amount),
            paymentCategory = category,
            paymentChannel = channel,
            allocations = emptyList(),
            balanceBefore = null,
            balanceAfter = null
        )

        val summary = ReportingAggregator.build(
            listOf(
                repayment("cash", 200L, PaymentCategory.CASH, PaymentChannel.CASH_TENDER),
                repayment("online", 300L, PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR),
                repayment("coupon", 100L, PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET)
            )
        )

        assertEquals(0L, summary.periodSaleInflow.amount)
        assertEquals(0L, summary.periodNetSalesActivity.amount)
        assertEquals(200L, summary.cashCreditRepayments.amount)
        assertEquals(300L, summary.onlineCreditRepayments.amount)
        assertEquals(100L, summary.couponCreditRepayments.amount)
        assertEquals(3, summary.counts.creditRepayments)
    }

    @Test
    fun couponActivityDoesNotCreateMerchandiseRevenueOrDoubleCountRepayment() {
        fun coupon(
            id: String,
            kind: CouponReportEventKind,
            signed: Long,
            funding: PaymentCategory? = null,
            fundingParts: List<ReportPaymentPart> = emptyList()
        ) = CouponReportRow(
                stableId = "coupon:$id",
                operationId = "operation-$id",
                transactionId = null,
                reference = null,
                occurredAt = 100L,
                currencyCode = "OMR",
                branch = branch,
                cashier = cashier,
                person = person,
                walletId = "wallet-1",
                ledgerEventId = id,
                eventKind = kind,
                signedAmount = money(signed),
                fundingCategory = funding,
                fundingChannel = when (funding) {
                    PaymentCategory.CASH -> PaymentChannel.CASH_TENDER
                    PaymentCategory.ONLINE -> PaymentChannel.MANUAL_QR
                    else -> null
                },
                authorizedUserId = null,
                authorizedUserName = null,
                resultingBalance = null,
                fundingParts = fundingParts
            )

        val couponRepayment = CreditRepaymentReportRow(
            stableId = "repayment:coupon",
            operationId = "repayment-op",
            transactionId = null,
            reference = null,
            occurredAt = 200L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = person,
            repaymentId = "coupon",
            amount = money(100L),
            paymentCategory = PaymentCategory.COUPON,
            paymentChannel = PaymentChannel.COUPON_WALLET,
            allocations = emptyList(),
            balanceBefore = null,
            balanceAfter = null
        )

        val summary = ReportingAggregator.build(
            listOf(
                coupon(
                    "purchase",
                    CouponReportEventKind.PURCHASE,
                    500L,
                    fundingParts = listOf(
                        part(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 300L),
                        part(PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR, 200L)
                    )
                ),
                coupon(
                    "topup",
                    CouponReportEventKind.TOP_UP,
                    200L,
                    fundingParts = listOf(
                        part(PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR, 200L)
                    )
                ),
                coupon("redeem", CouponReportEventKind.REDEMPTION, -300L),
                coupon("refund", CouponReportEventKind.REFUND_CREDIT, 150L),
                coupon("bonus", CouponReportEventKind.PROMOTION_BONUS, 50L),
                coupon("repay-ledger", CouponReportEventKind.CREDIT_REPAYMENT, -100L),
                couponRepayment
            )
        )

        assertEquals(0L, summary.periodSaleInflow.amount)
        assertEquals(500L, summary.couponPurchased.amount)
        assertEquals(200L, summary.couponToppedUp.amount)
        assertEquals(300L, summary.cashInHand.amount)
        assertEquals(400L, summary.cashAtBank.amount)
        assertEquals(300L, summary.couponRedeemed.amount)
        assertEquals(150L, summary.couponRefunded.amount)
        assertEquals(100L, summary.couponCreditRepayments.amount)
        assertEquals(6, summary.counts.couponEvents)
        assertEquals(1, summary.counts.creditRepayments)
    }

    @Test
    fun expenseAndRefundToCouponStaySeparatedFromRevenueAndExternalRefundTotals() {
        val expense = ExpenseReportRow(
            stableId = "expense:e1",
            operationId = "expense-op",
            reference = null,
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = null,
            expenseId = "e1",
            category = "Supplies",
            amount = money(250L),
            paymentCategory = PaymentCategory.CASH,
            paymentChannel = PaymentChannel.CASH_TENDER,
            note = null,
            supportingDocumentCount = null
        )
        val refund = RefundReportRow(
            stableId = "refund:coupon",
            operationId = "refund-op",
            transactionId = "old-sale",
            reference = "OLD",
            occurredAt = 120L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = person,
            refundEventId = "coupon",
            amount = money(500L),
            reason = null,
            destinationParts = listOf(
                part(PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, 500L)
            )
        )
        val couponCredit = CouponReportRow(
            stableId = "coupon:refund-credit",
            operationId = "refund-op",
            transactionId = "old-sale",
            reference = "OLD",
            occurredAt = 120L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = person,
            walletId = "wallet",
            ledgerEventId = "refund-credit",
            eventKind = CouponReportEventKind.REFUND_CREDIT,
            signedAmount = money(500L),
            fundingCategory = null,
            fundingChannel = null,
            authorizedUserId = null,
            authorizedUserName = null,
            resultingBalance = money(500L)
        )

        val summary = ReportingAggregator.build(listOf(expense, refund, couponCredit))
        assertEquals(-500L, summary.periodNetSalesActivity.amount)
        assertEquals(250L, summary.cashExpenses.amount)
        assertEquals(-250L, summary.cashInHand.amount)
        assertEquals(0L, summary.cashAtBank.amount)
        assertEquals(250L, summary.totalExpenses.amount)
        assertEquals(500L, summary.couponRefunded.amount)
        assertEquals(0L, summary.cashRefunded.amount)
        assertEquals(0L, summary.onlineRefunded.amount)
    }

    @Test
    fun exactArithmeticFailsClosedOnOverflow() {
        val result = runCatching {
            ReportingAggregator.build(
                listOf(
                    sale("max", Long.MAX_VALUE, emptyList()),
                    sale("overflow", 1L, emptyList())
                )
            )
        }
        assertTrue(result.exceptionOrNull() is ArithmeticException)
    }
}