package com.dailymoneyregister.core.model.money

import com.dailymoneyregister.core.model.currency.CurrencyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyMinorUnitsTest {

    @Test
    fun testOmrThreeDecimalFormattingAndParsing() {
        val config = CurrencyRegistry.OMR
        assertEquals(3, config.decimalPlaces)

        // 9.300 OMR = 9300 minor units (baisa)
        val money = MoneyMinorUnits(9300L)
        val formatted = money.formatDisplay(config.decimalPlaces, config.symbol, config.symbolPosition == com.dailymoneyregister.core.model.currency.CurrencySymbolPosition.PREFIX)
        assertEquals("9.300 OMR", formatted)

        // Parsing "9.300"
        val parsedResult = MoneyMinorUnits.parse("9.300", config.decimalPlaces)
        assertTrue(parsedResult.isSuccess)
        assertEquals(MoneyMinorUnits(9300L), parsedResult.getOrThrow())

        // Parsing partial decimals "9.3" -> 9300
        val partialParsed = MoneyMinorUnits.parse("9.3", config.decimalPlaces)
        assertTrue(partialParsed.isSuccess)
        assertEquals(MoneyMinorUnits(9300L), partialParsed.getOrThrow())

        // Parsing "0.050" -> 50 baisa
        val small = MoneyMinorUnits.parse("0.050", config.decimalPlaces)
        assertTrue(small.isSuccess)
        assertEquals(MoneyMinorUnits(50L), small.getOrThrow())
    }

    @Test
    fun testInrTwoDecimalFormattingAndParsing() {
        val config = CurrencyRegistry.INR
        assertEquals(2, config.decimalPlaces)

        // ₹125.50 = 12550 paise
        val money = MoneyMinorUnits(12550L)
        val formatted = money.formatDisplay(config.decimalPlaces, config.symbol, config.symbolPosition == com.dailymoneyregister.core.model.currency.CurrencySymbolPosition.PREFIX)
        assertEquals("₹125.50", formatted)

        // Parsing "125.50"
        val parsedResult = MoneyMinorUnits.parse("125.50", config.decimalPlaces)
        assertTrue(parsedResult.isSuccess)
        assertEquals(MoneyMinorUnits(12550L), parsedResult.getOrThrow())
    }

    @Test
    fun testStrictIntegerArithmeticZeroFloatOrDouble() {
        val m1 = MoneyMinorUnits(9300L)
        val m2 = MoneyMinorUnits(700L)

        // Addition
        val sum = m1 + m2
        assertEquals(MoneyMinorUnits(10000L), sum)

        // Subtraction
        val diff = m1 - m2
        assertEquals(MoneyMinorUnits(8600L), diff)

        // Negation
        val neg = -m1
        assertEquals(MoneyMinorUnits(-9300L), neg)
        assertTrue(neg.isNegative)
        assertFalse(neg.isPositive)

        // Multiplication by integer factor
        val multiplied = m2 * 3L
        assertEquals(MoneyMinorUnits(2100L), multiplied)
    }

    @Test
    fun testNegativeMoneyFormatting() {
        val config = CurrencyRegistry.OMR
        val refund = MoneyMinorUnits(-3000L)
        val formatted = refund.formatDisplay(config.decimalPlaces, config.symbol, false)
        assertEquals("-3.000 OMR", formatted)
    }
    @Test
    fun absUsesExactNegationAndFailsClosedForLongMinValue() {
        assertEquals(MoneyMinorUnits(100L), MoneyMinorUnits(-100L).abs())
        assertEquals(MoneyMinorUnits(100L), MoneyMinorUnits(100L).abs())

        try {
            MoneyMinorUnits(Long.MIN_VALUE).abs()
            throw AssertionError("Expected Long.MIN_VALUE absolute value to fail closed")
        } catch (_: ArithmeticException) {
            // Expected: the positive magnitude is not representable as a Long.
        }
    }

}
