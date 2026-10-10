package com.dailymoneyregister.feature.register.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RegisterShiftCloseReportSnapshotTest {
    @Test
    fun `closing summary round trips all reconciliation values`() {
        val expected = RegisterShiftCloseReportSnapshot(
            shiftId = "shift-123",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            operationCount = 42,
            salesCount = 18,
            grossSalesMinor = 250_125L,
            cashSalesMinor = 150_000L,
            onlineSalesMinor = 75_000L,
            creditGivenMinor = 20_000L,
            couponUsedMinor = 5_125L,
            cashCreditRepaymentsMinor = 4_000L,
            onlineCreditRepaymentsMinor = 5_000L,
            couponCreditRepaymentsMinor = 500L,
            couponIssuedMinor = 7_000L,
            couponTopUpMinor = 2_000L,
            couponRefundCreditsMinor = 1_000L,
            cashRefundsMinor = 1_500L,
            onlineRefundsMinor = 2_500L,
            cashExpensesMinor = 12_000L,
            onlineExpensesMinor = 4_500L,
            cashCouponFundingMinor = 7_000L,
            onlineCouponFundingMinor = 2_000L,
            cashLifecycleAdjustmentMinor = -500L,
            onlineLifecycleAdjustmentMinor = 1_000L,
            refundCount = 2,
            voidCount = 1,
            correctionCount = 3,
            paymentMethodChangeCount = 1,
            expectedCashMinor = 141_000L,
            countedCashMinor = 140_500L,
            differenceMinor = -500L,
            cashierUserId = "cashier-1",
            closedByUserId = "cashier-1",
            closedAt = 1_791_630_000_000L
        )

        assertEquals(expected, RegisterShiftCloseReportSnapshot.fromJsonString(expected.toJsonString()))
    }
}
