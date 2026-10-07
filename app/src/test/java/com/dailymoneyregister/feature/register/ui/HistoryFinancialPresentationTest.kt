package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.financial.FinancialFundingPart
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFinancialPresentationTest {

    @Test
    fun refundedTransactionShowsNetAmount() {
        val financial = financial().copy(
            currentAmount = MoneyMinorUnits(10_000L),
            refundedAmount = MoneyMinorUnits(3_000L),
            netAmount = MoneyMinorUnits(7_000L)
        )

        assertEquals(
            7_000L,
            historyDisplayedAmount(financial).amount
        )
    }

    @Test
    fun paymentMethodChangeShowsResultingMethod() {
        val financial = financial().copy(
            originalPaymentCategory = PaymentCategory.CASH,
            originalPaymentChannel = PaymentChannel.CASH_TENDER,
            currentPaymentCategory = PaymentCategory.ONLINE,
            currentPaymentChannel = PaymentChannel.MANUAL_QR,
            lifecycleStates =
                setOf(FinancialLifecycleState.PAYMENT_METHOD_CHANGED),
            fundingBreakdown =
                listOf(
                    FinancialFundingPart(
                        PaymentCategory.CASH,
                        PaymentChannel.CASH_TENDER,
                        10_000L
                    )
                )
        )

        assertEquals(
            listOf(PaymentCategory.ONLINE),
            historyDisplayedPaymentCategories(financial)
        )
    }

    private fun financial() =
        FinancialTransactionView(
            transaction =
                TransactionEntity(
                    id = "history-transaction",
                    branchId = "branch",
                    type = TransactionType.SALE,
                    subtotalMinor = 10_000L,
                    taxRateBps = 0,
                    taxableBaseMinor = 0L,
                    taxAmountMinor = 0L,
                    grandTotalMinor = 10_000L,
                    currencyCode = "OMR",
                    currencyDecimalPlaces = 3,
                    createdByUserId = "user",
                    deviceId = "device",
                    createdAt = 1_000L,
                    displayRef = "SAL-1"
                ),
            originalAmount = MoneyMinorUnits(10_000L),
            currentAmount = MoneyMinorUnits(10_000L),
            refundedAmount = MoneyMinorUnits.ZERO,
            netAmount = MoneyMinorUnits(10_000L),
            settlementTotal = MoneyMinorUnits(10_000L),
            isVoided = false,
            hasAmountCorrection = false,
            originalPaymentCategory = PaymentCategory.CASH,
            originalPaymentChannel = PaymentChannel.CASH_TENDER,
            currentPaymentCategory = PaymentCategory.CASH,
            currentPaymentChannel = PaymentChannel.CASH_TENDER,
            latestActivityAt = 1_000L,
            fundingBreakdown =
                listOf(
                    FinancialFundingPart(
                        PaymentCategory.CASH,
                        PaymentChannel.CASH_TENDER,
                        10_000L
                    )
                )
        )
}
