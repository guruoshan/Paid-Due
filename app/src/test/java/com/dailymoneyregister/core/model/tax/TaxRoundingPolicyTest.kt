package com.dailymoneyregister.core.model.tax

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Test

class TaxRoundingPolicyTest {

    @Test
    fun testTaxCalculationOman5Percent() {
        val halfUpPolicy = HalfUpTaxPolicy()

        // 6.000 OMR subtotal = 6000 baisa. 5% tax = 500 bps
        // Tax = (6000 * 500) / 10,000 = 300 baisa (0.300 OMR)
        val tax = halfUpPolicy.calculateTax(MoneyMinorUnits(6000L), 500)
        assertEquals(MoneyMinorUnits(300L), tax)

        // 1.000 OMR subtotal = 1000 baisa. 5% tax = 50 baisa (0.050 OMR)
        val taxSmall = halfUpPolicy.calculateTax(MoneyMinorUnits(1000L), 500)
        assertEquals(MoneyMinorUnits(50L), taxSmall)
    }

    @Test
    fun testTaxRoundingPoliciesDifferences() {
        // Base = 999 baisa. Rate = 500 bps (5%)
        // Raw tax = 999 * 500 / 10000 = 49.95 baisa
        // Quotient = 49, Remainder = 9500 / 10000 -> Remainder * 2 = 19000 >= 10000
        val base = MoneyMinorUnits(999L)
        val rateBps = 500

        val floorTax = FloorTaxPolicy().calculateTax(base, rateBps)
        assertEquals(MoneyMinorUnits(49L), floorTax)

        val ceilTax = CeilingTaxPolicy().calculateTax(base, rateBps)
        assertEquals(MoneyMinorUnits(50L), ceilTax)

        val halfUpTax = HalfUpTaxPolicy().calculateTax(base, rateBps)
        assertEquals(MoneyMinorUnits(50L), halfUpTax)

        val halfEvenTax = HalfEvenTaxPolicy().calculateTax(base, rateBps)
        assertEquals(MoneyMinorUnits(50L), halfEvenTax)
    }

    @Test
    fun halfUpRoundsExactHalfAwayFromZeroForPositiveTax() {
        val policy = HalfUpTaxPolicy()

        // 970 * 5% = 48.5 minor units -> HALF_UP rounds to 49.
        assertEquals(
            MoneyMinorUnits(49L),
            policy.calculateTax(MoneyMinorUnits(970L), 500)
        )

        // 990 * 5% = 49.5 minor units -> HALF_UP rounds to 50.
        assertEquals(
            MoneyMinorUnits(50L),
            policy.calculateTax(MoneyMinorUnits(990L), 500)
        )
    }

    @Test
    fun testHalfEvenBankersRounding() {
        val policy = HalfEvenTaxPolicy()

        // Test half-way cases:
        // Quotient 48, Remainder 5000/10000 (even quotient) -> stays 48
        // Quotient 49, Remainder 5000/10000 (odd quotient) -> rounds up to 50
        // Base: 970 * 500 / 10000 = 48.5 -> rounds to 48 (even)
        val baseEven = MoneyMinorUnits(970L)
        val taxEven = policy.calculateTax(baseEven, 500)
        assertEquals(MoneyMinorUnits(48L), taxEven)

        // Base: 990 * 500 / 10000 = 49.5 -> rounds to 50 (even)
        val baseOdd = MoneyMinorUnits(990L)
        val taxOdd = policy.calculateTax(baseOdd, 500)
        assertEquals(MoneyMinorUnits(50L), taxOdd)
    }
}
