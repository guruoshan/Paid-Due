package com.dailymoneyregister.core.model.financial

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentActivityAccountingTest {

    @Test
    fun couponSell100_thenCouponMerchandise50_todayTotalIs100Not150() {
        val activities = listOf(
            activity(
                id = "coupon-sell",
                type = RecentActivityType.COUPON_SELL,
                signedEffect = 100_000L,
                funding = listOf(
                    part(
                        PaymentCategory.CASH,
                        PaymentChannel.CASH_TENDER,
                        100_000L
                    )
                )
            ),
            activity(
                id = "coupon-merchandise",
                type = RecentActivityType.MERCHANDISE_SALE,
                signedEffect = 50_000L,
                funding = listOf(
                    part(
                        PaymentCategory.COUPON,
                        PaymentChannel.COUPON_WALLET,
                        50_000L
                    )
                )
            )
        )

        assertEquals(
            100_000L,
            RecentActivityTotals.todayTotal(activities).amount
        )
    }

    @Test
    fun futureDayCouponOnlyRedemption_addsZeroToTodayTotal() {
        val redemption = activity(
            id = "future-redemption",
            type = RecentActivityType.MERCHANDISE_SALE,
            signedEffect = 50_000L,
            funding = listOf(
                part(
                    PaymentCategory.COUPON,
                    PaymentChannel.COUPON_WALLET,
                    50_000L
                )
            )
        )

        assertEquals(
            0L,
            RecentActivityTotals.todayTotal(listOf(redemption)).amount
        )
    }

    @Test
    fun splitTender_coupon50_cash20_addsOnly20ToTodayTotal() {
        val sale = activity(
            id = "split",
            type = RecentActivityType.MERCHANDISE_SALE,
            signedEffect = 70_000L,
            funding = listOf(
                part(
                    PaymentCategory.COUPON,
                    PaymentChannel.COUPON_WALLET,
                    50_000L
                ),
                part(
                    PaymentCategory.CASH,
                    PaymentChannel.CASH_TENDER,
                    20_000L
                )
            )
        )

        assertEquals(
            20_000L,
            RecentActivityTotals.todayTotal(listOf(sale)).amount
        )
    }

    @Test
    fun topUp20_thenCouponSale70_addsOnly20ToTodayTotal() {
        val activities = listOf(
            activity(
                id = "top-up",
                type = RecentActivityType.COUPON_TOP_UP,
                signedEffect = 20_000L,
                funding = listOf(
                    part(
                        PaymentCategory.CASH,
                        PaymentChannel.CASH_TENDER,
                        20_000L
                    )
                )
            ),
            activity(
                id = "merchandise",
                type = RecentActivityType.MERCHANDISE_SALE,
                signedEffect = 70_000L,
                funding = listOf(
                    part(
                        PaymentCategory.COUPON,
                        PaymentChannel.COUPON_WALLET,
                        70_000L
                    )
                )
            )
        )

        assertEquals(
            20_000L,
            RecentActivityTotals.todayTotal(activities).amount
        )
    }

    @Test
    fun couponTopUp30Cash_adds30ToTodayTotal() {
        val topUp = activity(
            id = "top-up-only",
            type = RecentActivityType.COUPON_TOP_UP,
            signedEffect = 30_000L,
            funding = listOf(
                part(
                    PaymentCategory.CASH,
                    PaymentChannel.CASH_TENDER,
                    30_000L
                )
            )
        )

        assertEquals(
            30_000L,
            RecentActivityTotals.todayTotal(listOf(topUp)).amount
        )
    }

    @Test
    fun refundToCoupon_doesNotCreateNewTodayTotalValue() {
        val refund = activity(
            id = "refund-coupon",
            type = RecentActivityType.REFUND_TO_COUPON,
            signedEffect = -25_000L,
            funding = listOf(
                part(
                    PaymentCategory.COUPON,
                    PaymentChannel.COUPON_WALLET,
                    25_000L
                )
            )
        )

        assertEquals(
            0L,
            RecentActivityTotals.todayTotal(listOf(refund)).amount
        )
    }

    @Test
    fun couponOnlyVoid_doesNotReverseValueThatRecentTotalNeverAdded() {
        val void = activity(
            id = "void-coupon",
            type = RecentActivityType.VOID,
            signedEffect = -50_000L,
            funding = listOf(
                part(
                    PaymentCategory.COUPON,
                    PaymentChannel.COUPON_WALLET,
                    50_000L
                )
            )
        )

        assertEquals(
            0L,
            RecentActivityTotals.todayTotal(listOf(void)).amount
        )
    }

    @Test
    fun cashRefund_remainsNegativeInTodayTotal() {
        val refund = activity(
            id = "refund-cash",
            type = RecentActivityType.REFUND,
            signedEffect = -20_000L,
            funding = listOf(
                part(
                    PaymentCategory.CASH,
                    PaymentChannel.CASH_TENDER,
                    20_000L
                )
            )
        )

        assertEquals(
            -20_000L,
            RecentActivityTotals.todayTotal(listOf(refund)).amount
        )
    }

    private fun part(
        category: PaymentCategory,
        channel: PaymentChannel,
        amount: Long
    ) = FinancialFundingPart(
        category = category,
        channel = channel,
        amountMinor = amount
    )

    private fun activity(
        id: String,
        type: RecentActivityType,
        signedEffect: Long,
        funding: List<FinancialFundingPart>
    ) = RecentFinancialActivity(
        activityId = id,
        transactionId = "tx-$id",
        operationId = "op-$id",
        type = type,
        signedEffect = MoneyMinorUnits(signedEffect),
        fundingBreakdown = funding,
        occurredAt = 1_000L,
        customerId = null,
        customerName = null,
        status = RecentActivityStatus.RECORDED,
        originalTransactionId = null,
        sourceType = RecentActivitySourceType.TRANSACTION,
        sourceReference = id,
        displayRef = "REF-$id"
    )
}