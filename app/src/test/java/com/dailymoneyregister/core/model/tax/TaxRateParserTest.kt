package com.dailymoneyregister.core.model.tax

import org.junit.Assert.assertEquals
import org.junit.Test

class TaxRateParserTest {

    @Test
    fun testParseToBpsStandardPercentages() {
        assertEquals(500, TaxRateParser.parseToBps("5"))
        assertEquals(500, TaxRateParser.parseToBps("5.0"))
        assertEquals(500, TaxRateParser.parseToBps("5.00"))
        assertEquals(525, TaxRateParser.parseToBps("5.25"))
        assertEquals(550, TaxRateParser.parseToBps("5.5"))
        assertEquals(1800, TaxRateParser.parseToBps("18"))
        assertEquals(1800, TaxRateParser.parseToBps("18.0"))
        assertEquals(0, TaxRateParser.parseToBps("0"))
        assertEquals(0, TaxRateParser.parseToBps("0.0"))
        assertEquals(10000, TaxRateParser.parseToBps("100"))
    }

    @Test
    fun testParseToBpsEdgeCasesAndNull() {
        assertEquals(0, TaxRateParser.parseToBps(null))
        assertEquals(0, TaxRateParser.parseToBps(""))
        assertEquals(0, TaxRateParser.parseToBps("   "))
        assertEquals(0, TaxRateParser.parseToBps("-5"))
        assertEquals(10000, TaxRateParser.parseToBps("150")) // clamped at 10000 (100%)
    }

    @Test
    fun testBpsToInputString() {
        assertEquals("5.0", TaxRateParser.bpsToInputString(500))
        assertEquals("5.25", TaxRateParser.bpsToInputString(525))
        assertEquals("5.5", TaxRateParser.bpsToInputString(550))
        assertEquals("0.0", TaxRateParser.bpsToInputString(0))
        assertEquals("18.0", TaxRateParser.bpsToInputString(1800))
        assertEquals("0.5", TaxRateParser.bpsToInputString(50))
        assertEquals("0.05", TaxRateParser.bpsToInputString(5))
    }

    @Test
    fun testFormatBps() {
        assertEquals("5%", TaxRateParser.formatBps(500))
        assertEquals("5.25%", TaxRateParser.formatBps(525))
        assertEquals("5.5%", TaxRateParser.formatBps(550))
        assertEquals("0%", TaxRateParser.formatBps(0))
        assertEquals("18%", TaxRateParser.formatBps(1800))
    }
}
