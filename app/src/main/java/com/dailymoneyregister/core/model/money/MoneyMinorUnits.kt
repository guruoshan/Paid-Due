package com.dailymoneyregister.core.model.money

import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.currency.CurrencySymbolPosition

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Immutable value representation of money stored strictly as integer minor units (e.g. baisa for OMR, paise for INR).
 * 
 * ARCHITECTURAL RULE:
 * Absolutely NO Float or Double types may ever be used for financial representation or calculation.
 * All arithmetic is performed on 64-bit Long or BigInteger.
 */
@JvmInline
value class MoneyMinorUnits(val amount: Long) : Comparable<MoneyMinorUnits> {

    operator fun plus(other: MoneyMinorUnits): MoneyMinorUnits {
        return MoneyMinorUnits(Math.addExact(this.amount, other.amount))
    }

    operator fun minus(other: MoneyMinorUnits): MoneyMinorUnits {
        return MoneyMinorUnits(Math.subtractExact(this.amount, other.amount))
    }

    operator fun unaryMinus(): MoneyMinorUnits {
        return MoneyMinorUnits(Math.negateExact(this.amount))
    }

    operator fun times(factor: Long): MoneyMinorUnits {
        return MoneyMinorUnits(Math.multiplyExact(this.amount, factor))
    }

    override fun compareTo(other: MoneyMinorUnits): Int = this.amount.compareTo(other.amount)

    val isZero: Boolean get() = amount == 0L
    val isPositive: Boolean get() = amount > 0L
    val isNegative: Boolean get() = amount < 0L

    fun formatDisplay(currency: CurrencyConfig): String = formatDisplay(
        decimalPlaces = currency.decimalPlaces,
        symbol = currency.symbol,
        isSymbolPrefix = currency.symbolPosition == CurrencySymbolPosition.PREFIX,
        groupingSeparator = currency.groupingSeparator,
        decimalSeparator = currency.decimalSeparator
    )

    /**
     * Format into display string according to the given decimal places and symbol.
     * ZERO FLOATS USED: pure integer arithmetic and string operations.
     */
    fun formatDisplay(
        decimalPlaces: Int,
        symbol: String,
        isSymbolPrefix: Boolean = false,
        groupingSeparator: Char = ',',
        decimalSeparator: Char = '.'
    ): String {
        val isNeg = amount < 0

        val divisor = integerPowerOfTen(decimalPlaces)
        // Strip the sign as text so Long.MIN_VALUE never needs to be negated.
        val major = (amount / divisor).toString().removePrefix("-")
        val minor = Math.abs(amount % divisor)

        // Format major with grouping
        val majorStr = formatWithGrouping(major, groupingSeparator)

        val numberPart = if (decimalPlaces > 0) {
            val minorStr = minor.toString().padStart(decimalPlaces, '0')
            "$majorStr$decimalSeparator$minorStr"
        } else {
            majorStr
        }

        val signedNumber = if (isNeg) "-$numberPart" else numberPart

        return if (isSymbolPrefix) {
            "$symbol$signedNumber"
        } else {
            "$signedNumber $symbol".trim()
        }
    }

    companion object {
        val ZERO = MoneyMinorUnits(0L)

        fun integerPowerOfTen(exponent: Int): Long {
            require(exponent in 0..18) { "Exponent must be between 0 and 18" }
            var result = 1L
            for (i in 1..exponent) {
                result *= 10L
            }
            return result
        }

        private fun formatWithGrouping(s: String, separator: Char): String {
            val len = s.length
            if (len <= 3) return s

            val sb = StringBuilder()
            val firstGroupLen = if (len % 3 == 0) 3 else len % 3
            sb.append(s.substring(0, firstGroupLen))

            var i = firstGroupLen
            while (i < len) {
                sb.append(separator)
                sb.append(s.substring(i, i + 3))
                i += 3
            }
            return sb.toString()
        }

        /**
         * Parse user entered text directly into MoneyMinorUnits without any Float or Double conversion.
         */
        fun parse(input: String, decimalPlaces: Int): Result<MoneyMinorUnits> {
            val cleaned = input.trim().replace(" ", "").replace(",", "")
            if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty input"))

            val isNegative = cleaned.startsWith("-")
            val raw = if (isNegative) cleaned.substring(1) else cleaned
            if (raw.isEmpty()) {
                return Result.failure(IllegalArgumentException("Missing monetary digits"))
            }

            val parts = raw.split('.')
            if (parts.size > 2) return Result.failure(IllegalArgumentException("Invalid format: multiple decimal points"))

            val majorStr = parts[0]
            val minorStr = if (parts.size == 2) parts[1] else ""

            if (majorStr.isEmpty() && minorStr.isEmpty()) {
                return Result.failure(IllegalArgumentException("Missing monetary digits"))
            }

            if (!majorStr.all { it.isDigit() } && majorStr.isNotEmpty()) {
                return Result.failure(IllegalArgumentException("Non-digit characters in major unit"))
            }
            if (!minorStr.all { it.isDigit() }) {
                return Result.failure(IllegalArgumentException("Non-digit characters in minor unit"))
            }

            val major = if (majorStr.isEmpty()) 0L else majorStr.toLongOrNull()
                ?: return Result.failure(IllegalArgumentException("Major part overflow"))

            // Financial input must be exact. Never silently truncate precision.
            if (minorStr.length > decimalPlaces) {
                return Result.failure(IllegalArgumentException("Too many decimal places"))
            }
            val adjustedMinorStr = minorStr.padEnd(decimalPlaces, '0')

            val minor = if (adjustedMinorStr.isEmpty()) 0L else adjustedMinorStr.toLongOrNull()
                ?: return Result.failure(IllegalArgumentException("Minor part overflow"))

            val divisor = integerPowerOfTen(decimalPlaces)
            val total = try {
                val majorUnits = Math.multiplyExact(major, divisor)
                val sum = Math.addExact(majorUnits, minor)
                if (isNegative) Math.negateExact(sum) else sum
            } catch (e: ArithmeticException) {
                return Result.failure(IllegalArgumentException("Monetary value overflow", e))
            }

            return Result.success(MoneyMinorUnits(total))
        }
    }
}
