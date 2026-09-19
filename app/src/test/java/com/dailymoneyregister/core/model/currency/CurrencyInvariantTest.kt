package com.dailymoneyregister.core.model.currency

import org.junit.Assert.assertThrows
import org.junit.Test

class CurrencyInvariantTest {

    @Test
    fun testCurrencyMatchingPasses() {
        CurrencyInvariant.assertSameCurrency("OMR", "OMR", "Credit repayment allocation")
        CurrencyInvariant.assertSameCurrency("INR", "INR", "Coupon purchase")
    }

    @Test
    fun testCurrencyMismatchThrowsException() {
        assertThrows(CurrencyMismatchException::class.java) {
            CurrencyInvariant.assertSameCurrency("OMR", "INR", "Repayment allocation")
        }

        assertThrows(CurrencyMismatchException::class.java) {
            CurrencyInvariant.assertSameCurrency("USD", "OMR", "Coupon redemption")
        }
    }
}
