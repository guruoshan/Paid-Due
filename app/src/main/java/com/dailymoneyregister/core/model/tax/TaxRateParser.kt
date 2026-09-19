package com.dailymoneyregister.core.model.tax

/**
 * Exact basis points parser and formatter for tax percentages.
 * Strictly adheres to the zero Float/Double invariant.
 *
 * 1% = 100 bps (basis points)
 * 5% = 500 bps
 * 5.25% = 525 bps
 * 100% = 10,000 bps
 */
object TaxRateParser {

    /**
     * Strict parser for new financial input.
     *
     * Invalid input is rejected instead of silently becoming zero.
     */
    fun parseToBpsOrNull(percentText: String?): Int? {
        if (percentText.isNullOrBlank()) return 0

        val clean = percentText.trim()
        val parts = clean.split(".")

        if (parts.size > 2) return null

        val wholeStr = parts[0]
        if (wholeStr.isEmpty() || !wholeStr.all { it.isDigit() }) return null

        val wholePart = wholeStr.toIntOrNull() ?: return null

        val fractionPart = if (parts.size == 2) {
            val rawFraction = parts[1]

            if (rawFraction.length > 2) return null
            if (!rawFraction.all { it.isDigit() }) return null

            when (rawFraction.length) {
                0 -> 0
                1 -> rawFraction[0].digitToInt() * 10
                else -> rawFraction.toInt()
            }
        } else {
            0
        }

        val totalBps =
            (wholePart.toLong() * 100L) + fractionPart.toLong()

        if (totalBps !in 0L..10_000L) return null

        return totalBps.toInt()
    }

    /**
     * Legacy parser kept for existing behavior/tests.
     */
    fun parseToBps(percentText: String?): Int {
        if (percentText.isNullOrBlank()) return 0

        val clean = percentText.trim()
        val parts = clean.split(".")

        val wholeStr = parts[0].trim()
        val wholePart = wholeStr.toIntOrNull() ?: 0
        if (wholePart < 0) return 0

        val fractionPart = if (parts.size > 1) {
            val rawFrac = parts[1].trim()

            when (rawFrac.length) {
                0 -> 0
                1 -> (rawFrac[0].digitToIntOrNull() ?: 0) * 10
                else -> {
                    val d1 = rawFrac[0].digitToIntOrNull() ?: 0
                    val d2 = rawFrac[1].digitToIntOrNull() ?: 0
                    d1 * 10 + d2
                }
            }
        } else {
            0
        }

        val totalBps = (wholePart * 100) + fractionPart
        return totalBps.coerceIn(0, 10000)
    }

    fun formatBps(bps: Int): String {
        val whole = bps / 100
        val frac = bps % 100

        return when {
            frac == 0 -> "$whole%"
            frac % 10 == 0 -> "$whole.${frac / 10}%"
            else -> {
                val fracStr = if (frac < 10) "0$frac" else "$frac"
                "$whole.$fracStr%"
            }
        }
    }

    fun bpsToInputString(bps: Int): String {
        val whole = bps / 100
        val frac = bps % 100

        return when {
            frac == 0 -> "$whole.0"
            frac % 10 == 0 -> "$whole.${frac / 10}"
            else -> {
                val fracStr = if (frac < 10) "0$frac" else "$frac"
                "$whole.$fracStr"
            }
        }
    }
}