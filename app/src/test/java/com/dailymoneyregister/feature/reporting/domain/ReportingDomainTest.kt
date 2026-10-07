package com.dailymoneyregister.feature.reporting.domain

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingDomainTest {

    private fun couponRow(
        kind: CouponReportEventKind,
        signedMinor: Long
    ): CouponReportRow =
        CouponReportRow(
            stableId = "coupon:test",
            operationId = "op-test",
            transactionId = null,
            reference = null,
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = ReportBranch("branch-1", "Main"),
            cashier = ReportCashier("user-1", "Cashier"),
            person = ReportPerson("customer-1", "Customer", null),
            walletId = "wallet-1",
            ledgerEventId = "event-1",
            eventKind = kind,
            signedAmount = MoneyMinorUnits(signedMinor),
            fundingCategory = null,
            fundingChannel = null,
            authorizedUserId = null,
            authorizedUserName = null,
            resultingBalance = null
        )

    @Test
    fun periodIncludesLowerBoundaryAndExcludesUpperBoundary() {
        val period =
            ReportingPeriod(
                fromInclusive = 100L,
                toExclusive = 200L
            )

        assertTrue(period.contains(100L))
        assertTrue(period.contains(199L))

        assertFalse(period.contains(99L))
        assertFalse(period.contains(200L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun periodRejectsEmptyRange() {
        ReportingPeriod(
            fromInclusive = 100L,
            toExclusive = 100L
        )
    }

    @Test
    fun cashCreditRepaymentRemainsRepaymentNotSale() {
        val row =
            CreditRepaymentReportRow(
                stableId = "repayment:r1",
                operationId = "op-r1",
                transactionId = null,
                reference = null,
                occurredAt = 100L,
                currencyCode = "OMR",
                branch =
                    ReportBranch(
                        id = "branch-1",
                        name = "Main"
                    ),
                cashier =
                    ReportCashier(
                        userId = "user-1",
                        displayName = "Cashier"
                    ),
                person =
                    ReportPerson(
                        id = "customer-1",
                        name = "Customer",
                        phone = "+96890000000"
                    ),
                repaymentId = "r1",
                amount = MoneyMinorUnits(1000L),
                paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER,
                allocations = emptyList(),
                balanceBefore = MoneyMinorUnits(5000L),
                balanceAfter = MoneyMinorUnits(4000L)
            )

        assertEquals(
            ReportActivityType.CREDIT_REPAYMENT,
            row.activityType
        )
    }

    @Test
    fun couponCreditRepaymentIsValidReportingFundingCategory() {
        val row =
            CreditRepaymentReportRow(
                stableId = "repayment:r2",
                operationId = "op-r2",
                transactionId = null,
                reference = null,
                occurredAt = 100L,
                currencyCode = "OMR",
                branch = ReportBranch("branch-1", null),
                cashier = ReportCashier("user-1", null),
                person =
                    ReportPerson(
                        "customer-1",
                        "Customer",
                        null
                    ),
                repaymentId = "r2",
                amount = MoneyMinorUnits(500L),
                paymentCategory = PaymentCategory.COUPON,
                paymentChannel =
                    PaymentChannel.COUPON_WALLET,
                allocations = emptyList(),
                balanceBefore = null,
                balanceAfter = null
            )

        assertEquals(
            PaymentCategory.COUPON,
            row.paymentCategory
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun repaymentRejectsCreditAsCollectionFunding() {
        CreditRepaymentReportRow(
            stableId = "repayment:bad",
            operationId = "op-bad",
            transactionId = null,
            reference = null,
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = ReportBranch("branch-1", null),
            cashier = ReportCashier("user-1", null),
            person =
                ReportPerson(
                    "customer-1",
                    "Customer",
                    null
                ),
            repaymentId = "bad",
            amount = MoneyMinorUnits(100L),
            paymentCategory = PaymentCategory.CREDIT,
            paymentChannel =
                PaymentChannel.CREDIT_ACCOUNT,
            allocations = emptyList(),
            balanceBefore = null,
            balanceAfter = null
        )
    }

    @Test
    fun correctionDifferenceIsExactSignedPeriodEffect() {
        val row =
            AmountCorrectionReportRow(
                stableId = "correction:c1",
                operationId = "op-c1",
                transactionId = "tx-1",
                reference = "SALE-1",
                occurredAt = 100L,
                currencyCode = "OMR",
                branch = ReportBranch("branch-1", "Main"),
                cashier = ReportCashier("user-1", "Cashier"),
                person = null,
                eventId = "c1",
                previousAmount = MoneyMinorUnits(5000L),
                correctedAmount = MoneyMinorUnits(4500L),
                reason = "Correction"
            )

        assertEquals(
            MoneyMinorUnits(-500L),
            row.signedDifference
        )
    }

    @Test
    fun couponRedemptionPreservesAuthoritativeSignedEffect() {
        val row =
            CouponReportRow(
                stableId = "coupon:e1",
                operationId = "op-e1",
                transactionId = "tx-1",
                reference = "SALE-1",
                occurredAt = 100L,
                currencyCode = "OMR",
                branch = ReportBranch("branch-1", "Main"),
                cashier = ReportCashier("user-1", "Cashier"),
                person =
                    ReportPerson(
                        "customer-1",
                        "Customer",
                        "+96890000000"
                    ),
                walletId = "wallet-1",
                ledgerEventId = "e1",
                eventKind =
                    CouponReportEventKind.REDEMPTION,
                signedAmount = MoneyMinorUnits(-1000L),
                fundingCategory =
                    PaymentCategory.COUPON,
                fundingChannel =
                    PaymentChannel.COUPON_WALLET,
                authorizedUserId = null,
                authorizedUserName = null,
                resultingBalance =
                    MoneyMinorUnits(4000L)
            )

        assertEquals(
            ReportActivityType.COUPON_REDEMPTION,
            row.activityType
        )

        assertEquals(
            MoneyMinorUnits(-1000L),
            row.signedAmount
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun couponPurchaseRejectsNegativeAmount() {
        couponRow(CouponReportEventKind.PURCHASE, -100L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun couponRedemptionRejectsPositiveAmount() {
        couponRow(CouponReportEventKind.REDEMPTION, 100L)
    }

    @Test
    fun couponPromotionBonusIsPositiveAndMapsToBonusActivity() {
        val row = couponRow(CouponReportEventKind.PROMOTION_BONUS, 100L)

        assertEquals(ReportActivityType.COUPON_BONUS, row.activityType)
    }

    @Test
    fun expenseStoresPositiveBusinessAmount() {
        val row =
            ExpenseReportRow(
                stableId = "expense:e1",
                operationId = "op-e1",
                reference = "EXP-1",
                occurredAt = 100L,
                currencyCode = "OMR",
                branch = ReportBranch("branch-1", "Main"),
                cashier = ReportCashier("user-1", "Cashier"),
                person =
                    ReportPerson(
                        null,
                        "Supplier",
                        "+96890000000"
                    ),
                expenseId = "e1",
                category = "Supplies",
                amount = MoneyMinorUnits(2500L),
                paymentCategory = PaymentCategory.CASH,
                paymentChannel =
                    PaymentChannel.CASH_TENDER,
                note = null,
                supportingDocumentCount = 2
            )

        assertEquals(
            MoneyMinorUnits(2500L),
            row.amount
        )

        assertEquals(
            ReportActivityType.EXPENSE,
            row.activityType
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun voidRejectsBlankOperationId() {
        VoidReportRow(
            stableId = "void:v1",
            operationId = " ",
            transactionId = "tx-1",
            reference = null,
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = ReportBranch("branch-1", "Main"),
            cashier = ReportCashier("user-1", "Cashier"),
            person = null,
            eventId = "v1",
            affectedAmount = MoneyMinorUnits(1000L),
            reason = null
        )
    }

    @Test
    fun creditSaleAndNormalSaleRemainDistinguishable() {
        val common =
            SaleReportRow(
                stableId = "sale:tx-1",
                operationId = "op-1",
                transactionId = "tx-1",
                reference = "SALE-1",
                occurredAt = 100L,
                currencyCode = "OMR",
                branch = ReportBranch("branch-1", "Main"),
                cashier = ReportCashier("user-1", "Cashier"),
                person = null,
                subtotal = MoneyMinorUnits(1000L),
                taxAmount = MoneyMinorUnits.ZERO,
                shippingAmount = MoneyMinorUnits.ZERO,
                packingAmount = MoneyMinorUnits.ZERO,
                originalAmount = MoneyMinorUnits(1000L),
                lifetimeCurrentAmount =
                    MoneyMinorUnits(1000L),
                lifetimeRefundedAmount =
                    MoneyMinorUnits.ZERO,
                paymentParts =
                    listOf(
                        ReportPaymentPart(
                            category = PaymentCategory.CASH,
                            channel =
                                PaymentChannel.CASH_TENDER,
                            amount = MoneyMinorUnits(1000L)
                        )
                    ),
                items = emptyList(),
                lifecycleStatus =
                    ReportLifecycleStatus.NORMAL,
                isCreditSale = false
            )

        val credit =
            common.copy(
                stableId = "sale:tx-2",
                transactionId = "tx-2",
                isCreditSale = true,
                paymentParts =
                    listOf(
                        ReportPaymentPart(
                            category =
                                PaymentCategory.CREDIT,
                            channel =
                                PaymentChannel.CREDIT_ACCOUNT,
                            amount =
                                MoneyMinorUnits(1000L)
                        )
                    )
            )

        assertEquals(
            ReportActivityType.SALE,
            common.activityType
        )

        assertEquals(
            ReportActivityType.CREDIT_SALE,
            credit.activityType
        )
    }

    @Test
    fun reportingFilterTrimsSearchWithoutChangingOriginalInput() {
        val filter =
            ReportingFilter(
                query = "  +96890000000  "
            )

        assertEquals(
            "+96890000000",
            filter.normalizedQuery
        )

        assertEquals(
            "  +96890000000  ",
            filter.query
        )
    }
}