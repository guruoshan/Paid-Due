package com.dailymoneyregister.core.model.tax

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import java.math.BigInteger

/**
 * Pluggable tax rounding policy abstraction.
 * Regulatory rules in different jurisdictions require different rounding policies:
 * - HALF_EVEN (Banker's rounding - e.g. international financial standard)
 * - HALF_UP (Standard arithmetic rounding - e.g. Oman/GCC standard)
 * - CEILING (Always round up to protect tax authority)
 * - FLOOR (Truncate fractional minor unit)
 */
enum class TaxRoundingMode {
    HALF_EVEN,
    HALF_UP,
    CEILING,
    FLOOR
}

interface TaxRoundingPolicy {
    val mode: TaxRoundingMode

    /**
     * Calculates tax amount in minor units given a taxable base and a tax rate in basis points (1% = 100 bps).
     * Exact integer/rational calculation:
     * Tax = (TaxableBase * RateBps) / 10,000
     */
    fun calculateTax(taxableBase: MoneyMinorUnits, rateBps: Int): MoneyMinorUnits {
        if (taxableBase.amount <= 0L || rateBps <= 0) return MoneyMinorUnits.ZERO

        val baseBig = BigInteger.valueOf(taxableBase.amount)
        val rateBig = BigInteger.valueOf(rateBps.toLong())
        val product = baseBig.multiply(rateBig)
        val divisor = BigInteger.valueOf(10_000L)

        val quotientAndRemainder = product.divideAndRemainder(divisor)
        val quotient = quotientAndRemainder[0].toLong()
        val remainder = quotientAndRemainder[1].toLong()

        if (remainder == 0L) {
            return MoneyMinorUnits(quotient)
        }

        val roundedAmount = when (mode) {
            TaxRoundingMode.FLOOR -> quotient
            TaxRoundingMode.CEILING -> quotient + 1L
            TaxRoundingMode.HALF_UP -> {
                // If remainder >= divisor / 2 -> round up
                if (remainder * 2 >= 10_000L) quotient + 1L else quotient
            }
            TaxRoundingMode.HALF_EVEN -> {
                val doubleRemainder = remainder * 2
                if (doubleRemainder > 10_000L) {
                    quotient + 1L
                } else if (doubleRemainder < 10_000L) {
                    quotient
                } else {
                    // Exactly half -> round to even
                    if (quotient % 2L != 0L) quotient + 1L else quotient
                }
            }
        }

        return MoneyMinorUnits(roundedAmount)
    }
}

class HalfEvenTaxPolicy : TaxRoundingPolicy {
    override val mode = TaxRoundingMode.HALF_EVEN
}

class HalfUpTaxPolicy : TaxRoundingPolicy {
    override val mode = TaxRoundingMode.HALF_UP
}

class CeilingTaxPolicy : TaxRoundingPolicy {
    override val mode = TaxRoundingMode.CEILING
}

class FloorTaxPolicy : TaxRoundingPolicy {
    override val mode = TaxRoundingMode.FLOOR
}

object TaxPolicyRegistry {
    fun getPolicy(mode: TaxRoundingMode): TaxRoundingPolicy = when (mode) {
        TaxRoundingMode.HALF_EVEN -> HalfEvenTaxPolicy()
        TaxRoundingMode.HALF_UP -> HalfUpTaxPolicy()
        TaxRoundingMode.CEILING -> CeilingTaxPolicy()
        TaxRoundingMode.FLOOR -> FloorTaxPolicy()
    }
}

/**
 * Immutable snapshot of tax calculations applied to a transaction.
 */
data class TransactionTaxSnapshot(
    val rateBps: Int,                     // 500 = 5.00%
    val taxableBase: MoneyMinorUnits,
    val taxAmount: MoneyMinorUnits,
    val roundingMode: TaxRoundingMode
)
