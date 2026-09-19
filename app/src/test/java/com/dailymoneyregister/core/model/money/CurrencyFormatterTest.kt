package com.dailymoneyregister.core.model.money

import com.dailymoneyregister.core.model.currency.CurrencyRegistry
import com.dailymoneyregister.core.model.currency.CurrencySymbolPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyFormatterTest {
    @Test
    fun omrUsesThreeDecimalPlaces() {
        assertEquals("9.300 OMR", MoneyMinorUnits(9300L).formatDisplay(CurrencyRegistry.OMR))
        assertEquals("0.001 OMR", MoneyMinorUnits(1L).formatDisplay(CurrencyRegistry.OMR))
    }

    @Test
    fun inrUsesTwoDecimalPlaces() {
        assertEquals("\u20B9125.50", MoneyMinorUnits(12550L).formatDisplay(CurrencyRegistry.INR))
        assertEquals("\u20B90.01", MoneyMinorUnits(1L).formatDisplay(CurrencyRegistry.INR))
    }

    @Test
    fun prefixComesFromMetadata() {
        val currency = CurrencyRegistry.OMR.copy(symbol = "R", symbolPosition = CurrencySymbolPosition.PREFIX)
        assertEquals("R1.234", MoneyMinorUnits(1234L).formatDisplay(currency))
    }

    @Test
    fun suffixComesFromMetadata() {
        val currency = CurrencyRegistry.INR.copy(symbol = "Rs", symbolPosition = CurrencySymbolPosition.SUFFIX)
        assertEquals("12.34 Rs", MoneyMinorUnits(1234L).formatDisplay(currency))
    }

    @Test
    fun zeroRetainsCurrencyPrecision() {
        assertEquals("0.000 OMR", MoneyMinorUnits.ZERO.formatDisplay(CurrencyRegistry.OMR))
        assertEquals("\u20B90.00", MoneyMinorUnits.ZERO.formatDisplay(CurrencyRegistry.INR))
    }

    @Test
    fun largestAmountRemainsExactAndUnchanged() {
        val money = MoneyMinorUnits(Long.MAX_VALUE)
        assertEquals("9,223,372,036,854,775.807 OMR", money.formatDisplay(CurrencyRegistry.OMR))
        assertEquals("\u20B992,233,720,368,547,758.07", money.formatDisplay(CurrencyRegistry.INR))
        assertEquals(Long.MAX_VALUE, money.amount)
    }

    @Test
    fun smallestAmountDoesNotOverflow() {
        val money = MoneyMinorUnits(Long.MIN_VALUE)
        assertEquals("-9,223,372,036,854,775.808 OMR", money.formatDisplay(CurrencyRegistry.OMR))
        assertEquals("\u20B9-92,233,720,368,547,758.08", money.formatDisplay(CurrencyRegistry.INR))
        assertEquals("-9,223,372,036,854,775,808", money.formatDisplay(0, ""))
        assertEquals(Long.MIN_VALUE, money.amount)
    }

    @Test
    fun separatorsComeFromMetadata() {
        val currency = CurrencyRegistry.OMR.copy(groupingSeparator = '.', decimalSeparator = ',')
        assertEquals("1.234,567 OMR", MoneyMinorUnits(1234567L).formatDisplay(currency))
    }
}