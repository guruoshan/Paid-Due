package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DayClosingVarianceStatus {
    SHORT,
    EXACT,
    OVER
}

data class DayClosingReconciliationUi(
    val expectedCashMinor: Long,
    val countedCashMinor: Long,
    val differenceMinor: Long,
    val status: DayClosingVarianceStatus
)

data class DayClosingResultUi(
    val reconciliation: DayClosingReconciliationUi,
    val shiftOwnerUserId: String,
    val shiftOwnerDisplayName: String,
    val closedByUserId: String,
    val closedByDisplayName: String,
    val closedAt: Long,
    val closedAtDisplay: String
)

internal fun dayClosingReconciliation(
    expectedCashMinor: Long,
    countedCashMinor: Long
): DayClosingReconciliationUi? {

    val difference =
        try {
            Math.subtractExact(
                countedCashMinor,
                expectedCashMinor
            )
        } catch (_: ArithmeticException) {
            return null
        }

    val status =
        when {
            difference < 0L ->
                DayClosingVarianceStatus.SHORT

            difference > 0L ->
                DayClosingVarianceStatus.OVER

            else ->
                DayClosingVarianceStatus.EXACT
        }

    return DayClosingReconciliationUi(
        expectedCashMinor = expectedCashMinor,
        countedCashMinor = countedCashMinor,
        differenceMinor = difference,
        status = status
    )
}

internal fun parseDayClosingCountedMinor(
    value: String,
    decimalPlaces: Int
): Long? =
    MoneyMinorUnits
        .parse(
            value.trim(),
            decimalPlaces
        )
        .getOrNull()
        ?.amount
        ?.takeIf { it >= 0L }

internal fun formatDayClosingTime(
    epochMillis: Long,
    timezoneId: String
): String =
    try {
        Instant
            .ofEpochMilli(epochMillis)
            .atZone(
                ZoneId.of(timezoneId)
            )
            .format(
                DateTimeFormatter.ofPattern(
                    "dd MMM yyyy • hh:mm a",
                    Locale.getDefault()
                )
            )
    } catch (_: Exception) {
        epochMillis.toString()
    }