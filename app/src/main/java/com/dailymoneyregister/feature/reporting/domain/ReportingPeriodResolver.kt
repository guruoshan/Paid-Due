package com.dailymoneyregister.feature.reporting.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Resolves Reporting presets against the authoritative branch timezone.
 *
 * This is reporting-scope logic only. It owns no financial state and performs
 * no financial arithmetic.
 */
object ReportingPeriodResolver {

    fun resolve(
        preset: ReportingPeriodPreset,
        timezoneId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ReportingPeriod {

        require(preset != ReportingPeriodPreset.CUSTOM) {
            "CUSTOM reporting periods require explicit boundaries."
        }

        val zoneId =
            ZoneId.of(timezoneId)

        val today =
            Instant.ofEpochMilli(nowMillis)
                .atZone(zoneId)
                .toLocalDate()

        val startDate =
            when (preset) {
                ReportingPeriodPreset.TODAY ->
                    today

                ReportingPeriodPreset.YESTERDAY ->
                    today.minusDays(1)

                ReportingPeriodPreset.THIS_WEEK ->
                    today.with(
                        TemporalAdjusters.previousOrSame(
                            DayOfWeek.MONDAY
                        )
                    )

                ReportingPeriodPreset.THIS_MONTH ->
                    today.withDayOfMonth(1)

                ReportingPeriodPreset.CUSTOM ->
                    error("CUSTOM is handled above.")
            }

        val endDate =
            when (preset) {
                ReportingPeriodPreset.TODAY ->
                    today.plusDays(1)

                ReportingPeriodPreset.YESTERDAY ->
                    today

                ReportingPeriodPreset.THIS_WEEK ->
                    startDate.plusWeeks(1)

                ReportingPeriodPreset.THIS_MONTH ->
                    startDate.plusMonths(1)

                ReportingPeriodPreset.CUSTOM ->
                    error("CUSTOM is handled above.")
            }

        return ReportingPeriod(
            fromInclusive =
                startDate
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli(),
            toExclusive =
                endDate
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli(),
            preset = preset
        )
    }

    fun custom(
        fromInclusive: Long,
        toExclusive: Long
    ): ReportingPeriod =
        ReportingPeriod(
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            preset = ReportingPeriodPreset.CUSTOM
        )
}