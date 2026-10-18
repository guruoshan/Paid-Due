package com.dailymoneyregister.feature.customers.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerFinancialReconciliationMathTest {

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
    fun checkedMinorUnitSumFailsRatherThanWrappingOnOverflow() {
        try {
            CustomerFinancialReconciliationMath.sumMinor(listOf(Long.MAX_VALUE, 1L))
            throw AssertionError("Expected arithmetic overflow to fail closed")
        } catch (_: ArithmeticException) {
            // Expected: financial totals must never silently wrap.
        }
    }
}
