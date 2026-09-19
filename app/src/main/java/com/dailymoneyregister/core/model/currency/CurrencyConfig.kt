package com.dailymoneyregister.core.model.currency

enum class CurrencySymbolPosition {
    PREFIX,
    SUFFIX
}

/**
 * Currency configuration metadata for a branch or business.
 */
data class CurrencyConfig(
    val code: String,                // e.g. "OMR", "INR", "AED"
    val symbol: String,              // e.g. "OMR", "₹", "AED"
    val decimalPlaces: Int,          // 3 for OMR, 2 for INR, 3 for BHD/KWD
    val symbolPosition: CurrencySymbolPosition = CurrencySymbolPosition.SUFFIX,
    val groupingSeparator: Char = ',',
    val decimalSeparator: Char = '.'
)

/**
 * Irreversible snapshot of currency settings captured at transaction time.
 * Historical transactions must NEVER change currency decimal representation.
 */
data class CurrencySnapshot(
    val code: String,
    val decimalPlaces: Int
)

/**
 * Standard currency registry for primary target markets.
 */
object CurrencyRegistry {
    val OMR = CurrencyConfig(
        code = "OMR",
        symbol = "OMR",
        decimalPlaces = 3,
        symbolPosition = CurrencySymbolPosition.SUFFIX
    )

    val INR = CurrencyConfig(
        code = "INR",
        symbol = "₹",
        decimalPlaces = 2,
        symbolPosition = CurrencySymbolPosition.PREFIX
    )

    val AED = CurrencyConfig(
        code = "AED",
        symbol = "AED",
        decimalPlaces = 2,
        symbolPosition = CurrencySymbolPosition.SUFFIX
    )

    val SAR = CurrencyConfig(
        code = "SAR",
        symbol = "SAR",
        decimalPlaces = 2,
        symbolPosition = CurrencySymbolPosition.SUFFIX
    )

    val BHD = CurrencyConfig(
        code = "BHD",
        symbol = "BHD",
        decimalPlaces = 3,
        symbolPosition = CurrencySymbolPosition.SUFFIX
    )

    val KWD = CurrencyConfig(
        code = "KWD",
        symbol = "KWD",
        decimalPlaces = 3,
        symbolPosition = CurrencySymbolPosition.SUFFIX
    )

    val USD = CurrencyConfig(
        code = "USD",
        symbol = "$",
        decimalPlaces = 2,
        symbolPosition = CurrencySymbolPosition.PREFIX
    )

    private val all = listOf(OMR, INR, AED, SAR, BHD, KWD, USD).associateBy { it.code }

    fun findByCode(code: String): CurrencyConfig {
        return all[code.uppercase()] ?: CurrencyConfig(
            code = code.uppercase(),
            symbol = code.uppercase(),
            decimalPlaces = 2
        )
    }

    fun getAllSupported(): List<CurrencyConfig> = all.values.toList()
}

/**
 * Permanent Currency Invariant Enforcer.
 * Rules:
 * 1. Credit balances, Coupon wallets, repayments, settlements and standalone financial ledger events must never combine different currencies.
 * 2. Allocations may only occur between records of the same currency.
 */
object CurrencyInvariant {
    fun assertSameCurrency(currency1: String, currency2: String, operationDescription: String) {
        if (!currency1.equals(currency2, ignoreCase = true)) {
            throw CurrencyMismatchException(
                "Currency violation during $operationDescription: cannot combine '$currency1' and '$currency2'. " +
                        "Financial ledgers must never mix unlike currencies."
            )
        }
    }
}

class CurrencyMismatchException(message: String) : IllegalStateException(message)
