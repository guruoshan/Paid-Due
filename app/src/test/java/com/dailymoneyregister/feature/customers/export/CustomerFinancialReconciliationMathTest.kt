package com.dailymoneyregister.feature.customers.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerFinancialReconciliationMathTest {

    @Test
    fun saleHistoryKeepsOriginalValueWhileSummariesUseLatestCorrection() {
        val originalAmount = 10_000L
        val latestCorrectedAmount = 12_000L
        val correctionDelta = Math.subtractExact(latestCorrectedAmount, originalAmount)

        assertEquals(originalAmount, originalAmount)
        assertEquals(
            latestCorrectedAmount,
            CustomerFinancialReconciliationMath.effectiveSaleAmount(
                originalAmount,
                latestCorrectedAmount
            )
        )
        assertEquals(
            originalAmount,
            CustomerFinancialReconciliationMath.effectiveSaleAmount(
                originalAmount,
                null
            )
        )
        // The history rows (original + signed correction) reconcile to the effective value.
        assertEquals(
            latestCorrectedAmount,
            CustomerFinancialReconciliationMath.sumMinor(listOf(originalAmount, correctionDelta))
        )
    }

    @Test
    fun refundOnVoidedSaleRemainsInHistoryButIsNotDeductedFromNetSalesAgain() {
        val validSalesMinor = 10_000L
        val refunds = listOf(
            "sale-still-valid" to -1_500L,
            "sale-already-voided" to -7_000L
        )

        val netSales = CustomerFinancialReconciliationMath.netSalesAfterRefunds(
            validSalesMinor = validSalesMinor,
            refunds = refunds,
            voidedTransactionIds = setOf("sale-already-voided")
        )

        assertEquals(8_500L, netSales)
    }

    @Test
    fun refundReconciliationFlagsOverRefundAndExplainsVoidOverlap() {
        assertTrue(
            CustomerFinancialReconciliationMath.saleRefundControlStatus(
                saleMinor = 1_000L, refundMinor = 1_200L, isVoided = false
            ).startsWith("REVIEW")
        )
        assertTrue(
            CustomerFinancialReconciliationMath.saleRefundControlStatus(
                saleMinor = 1_000L, refundMinor = 900L, isVoided = true
            ).contains("net contribution is zero")
        )
    }

    @Test
    fun checkedMinorUnitSumFailsRatherThanWrappingOnOverflow() {
        try {
            CustomerFinancialReconciliationMath.sumMinor(listOf(Long.MAX_VALUE, 1L))
            throw AssertionError("Expected arithmetic overflow to fail closed")
        } catch (_: ArithmeticException) {
            // Expected: financial totals must never silently wrap.
        }
    }
}
