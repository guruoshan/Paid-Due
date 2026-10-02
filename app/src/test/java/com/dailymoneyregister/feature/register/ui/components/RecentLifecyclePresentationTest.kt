package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentLifecyclePresentationTest {

    @Test
    fun ordinaryTransactionHasNoLifecycleBadges() {
        assertEquals(emptyList<RecentLifecycleBadge>(), recentLifecycleBadges(view()))
    }

    @Test
    fun correctedPartiallyRefundedTransactionShowsBothBadges() {
        assertEquals(
            listOf(RecentLifecycleBadge.PARTIALLY_REFUNDED, RecentLifecycleBadge.CORRECTED),
            recentLifecycleBadges(view(refunded = 25L, hasCorrection = true))
        )
    }

    @Test
    fun fullyRefundedTransactionShowsFullRefundBadge() {
        assertEquals(
            listOf(RecentLifecycleBadge.FULLY_REFUNDED),
            recentLifecycleBadges(view(refunded = 100L))
        )
    }

    @Test
    fun voidedFullyRefundedCorrectedTransactionShowsAllBadges() {
        assertEquals(
            listOf(
                RecentLifecycleBadge.VOIDED,
                RecentLifecycleBadge.FULLY_REFUNDED,
                RecentLifecycleBadge.CORRECTED
            ),
            recentLifecycleBadges(view(isVoided = true, refunded = 100L, hasCorrection = true))
        )
    }

    @Test
    fun paymentMethodChangeUsesResolverLifecycleState() {
        assertEquals(
            listOf(RecentLifecycleBadge.PAYMENT_METHOD_CHANGED),
            recentLifecycleBadges(
                view(states = setOf(FinancialLifecycleState.PAYMENT_METHOD_CHANGED))
            )
        )
    }

    private fun view(
        isVoided: Boolean = false,
        refunded: Long = 0L,
        hasCorrection: Boolean = false,
        states: Set<FinancialLifecycleState> = emptySet()
    ): FinancialTransactionView {
        val current = MoneyMinorUnits(100L)
        return FinancialTransactionView(
            transaction = TransactionEntity(
                id = "transaction-1",
                branchId = "branch-1",
                type = TransactionType.SALE,
                subtotalMinor = 100L,
                taxRateBps = 0,
                taxableBaseMinor = 100L,
                taxAmountMinor = 0L,
                grandTotalMinor = 100L,
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                createdByUserId = "user-1",
                deviceId = "device-1",
                createdAt = 1L,
                displayRef = "TX-1"
            ),
            originalAmount = current,
            currentAmount = current,
            refundedAmount = MoneyMinorUnits(refunded),
            netAmount = current - MoneyMinorUnits(refunded),
            settlementTotal = current,
            isVoided = isVoided,
            hasAmountCorrection = hasCorrection,
            originalPaymentCategory = PaymentCategory.CASH,
            originalPaymentChannel = PaymentChannel.CASH_TENDER,
            currentPaymentCategory = PaymentCategory.CASH,
            currentPaymentChannel = PaymentChannel.CASH_TENDER,
            latestActivityAt = 1L,
            lifecycleStates = states
        )
    }
}
