package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.feature.register.ui.DayClosingVarianceStatus
import com.dailymoneyregister.feature.register.ui.dayClosingReconciliation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterShiftDayClosingAccountingTest {

    @Test
    fun externalMovementRoutesOnlyCashAndOnline() {
        assertEquals(
            RegisterShiftExternalMovement(
                cashMinor = 5_000L
            ),
            registerShiftExternalMovement(
                PaymentCategory.CASH,
                5_000L
            )
        )

        assertEquals(
            RegisterShiftExternalMovement(
                onlineMinor = -7_000L
            ),
            registerShiftExternalMovement(
                PaymentCategory.ONLINE,
                -7_000L
            )
        )

        assertEquals(
            RegisterShiftExternalMovement(),
            registerShiftExternalMovement(
                PaymentCategory.CREDIT,
                9_000L
            )
        )

        assertEquals(
            RegisterShiftExternalMovement(),
            registerShiftExternalMovement(
                PaymentCategory.COUPON,
                9_000L
            )
        )
    }

    @Test
    fun openingCountIsExcludedFromExpectedCash() {
        // Opening float is not an input to the expected-cash formula (audit §2.2).
        val expected = registerShiftExpectedCashMinor(
            cashSalesMinor = 5_000L,
            cashCreditRepaymentsMinor = 0L,
            cashCouponFundingMinor = 0L,
            cashLifecycleAdjustmentMinor = 0L,
            cashRefundsMinor = 0L,
            cashExpensesMinor = 0L
        )
        assertEquals(5_000L, expected)

        val result = requireNotNull(
            dayClosingReconciliation(
                expectedCashMinor = expected,
                countedCashMinor = 25_000L
            )
        )
        assertEquals(20_000L, result.differenceMinor)
        assertEquals(DayClosingVarianceStatus.OVER, result.status)
    }

    @Test
    fun couponTopUpClassificationUsesDurableIntentPayload() {
        assertTrue(
            registerShiftIsCouponTopUpIntent(
                "TOPUP|1|authoritative-payload"
            )
        )

        assertFalse(
            registerShiftIsCouponTopUpIntent(
                "SALE|1|authoritative-payload"
            )
        )

        assertFalse(
            registerShiftIsCouponTopUpIntent("")
        )
    }

    @Test
    fun expectedCashIncludesCouponFundingAndLifecycleDeltasExactlyOnce() {
        /*
         * Example:
         *
         * +  9.000 Cash Sale
         * + 27.000 Cash Credit repayment
         * +100.000 Cash Coupon funding
         * -  3.000 net lifecycle adjustment
         * -  2.000 Cash refund
         * - 25.000 Cash expense
         *
         * =106.000 expected physical Cash
         */
        assertEquals(
            106_000L,
            registerShiftExpectedCashMinor(
                cashSalesMinor = 9_000L,
                cashCreditRepaymentsMinor = 27_000L,
                cashCouponFundingMinor = 100_000L,
                cashLifecycleAdjustmentMinor = -3_000L,
                cashRefundsMinor = 2_000L,
                cashExpensesMinor = 25_000L
            )
        )
    }

    @Test
    fun lifecycleSignedMovementCanRepresentCorrectionReclassificationAndVoid() {
        val correction =
            registerShiftExternalMovement(
                PaymentCategory.CASH,
                1_000L
            )

        val paymentMethodReclassification =
            registerShiftExternalMovement(
                PaymentCategory.CASH,
                5_000L
            )

        val voidReversal =
            registerShiftExternalMovement(
                PaymentCategory.CASH,
                -9_000L
            )

        assertEquals(
            -3_000L,
            correction.cashMinor +
                paymentMethodReclassification.cashMinor +
                voidReversal.cashMinor
        )
    }
}