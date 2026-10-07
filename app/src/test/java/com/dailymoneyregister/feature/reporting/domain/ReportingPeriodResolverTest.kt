package com.dailymoneyregister.feature.reporting.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReportingPeriodResolverTest {

    private val now =
        Instant.parse(
            "2026-10-06T12:00:00Z"
        ).toEpochMilli()

    @Test
    fun todayUsesAuthoritativeBranchTimezone() {

        val result =
            ReportingPeriodResolver.resolve(
                preset =
                    ReportingPeriodPreset.TODAY,
                timezoneId =
                    "Asia/Muscat",
                nowMillis =
                    now
            )

        assertEquals(
            Instant.parse(
                "2026-10-05T20:00:00Z"
            ).toEpochMilli(),
            result.fromInclusive
        )

        assertEquals(
            Instant.parse(
                "2026-10-06T20:00:00Z"
            ).toEpochMilli(),
            result.toExclusive
        )
    }

    @Test
    fun yesterdayUsesPreviousLocalCalendarDay() {

        val result =
            ReportingPeriodResolver.resolve(
                preset =
                    ReportingPeriodPreset.YESTERDAY,
                timezoneId =
                    "Asia/Muscat",
                nowMillis =
                    now
            )

        assertEquals(
            Instant.parse(
                "2026-10-04T20:00:00Z"
            ).toEpochMilli(),
            result.fromInclusive
        )

        assertEquals(
            Instant.parse(
                "2026-10-05T20:00:00Z"
            ).toEpochMilli(),
            result.toExclusive
        )
    }

    @Test
    fun thisWeekUsesMondayToMondayInBranchTimezone() {

        val result =
            ReportingPeriodResolver.resolve(
                preset =
                    ReportingPeriodPreset.THIS_WEEK,
                timezoneId =
                    "Asia/Muscat",
                nowMillis =
                    now
            )

        assertEquals(
            Instant.parse(
                "2026-10-04T20:00:00Z"
            ).toEpochMilli(),
            result.fromInclusive
        )

        assertEquals(
            Instant.parse(
                "2026-10-11T20:00:00Z"
            ).toEpochMilli(),
            result.toExclusive
        )
    }

    @Test
    fun thisMonthUsesLocalCalendarMonth() {

        val result =
            ReportingPeriodResolver.resolve(
                preset =
                    ReportingPeriodPreset.THIS_MONTH,
                timezoneId =
                    "Asia/Muscat",
                nowMillis =
                    now
            )

        assertEquals(
            Instant.parse(
                "2026-09-30T20:00:00Z"
            ).toEpochMilli(),
            result.fromInclusive
        )

        assertEquals(
            Instant.parse(
                "2026-10-31T20:00:00Z"
            ).toEpochMilli(),
            result.toExclusive
        )
    }

    @Test
    fun todayUsesCalendarBoundaryAcrossDstTimezone() {

        val dstNow =
            Instant.parse(
                "2026-03-08T16:00:00Z"
            ).toEpochMilli()

        val result =
            ReportingPeriodResolver.resolve(
                preset =
                    ReportingPeriodPreset.TODAY,
                timezoneId =
                    "America/New_York",
                nowMillis =
                    dstNow
            )

        assertEquals(
            Instant.parse(
                "2026-03-08T05:00:00Z"
            ).toEpochMilli(),
            result.fromInclusive
        )

        assertEquals(
            Instant.parse(
                "2026-03-09T04:00:00Z"
            ).toEpochMilli(),
            result.toExclusive
        )
    }

    @Test
    fun customPresetRequiresExplicitBoundaries() {

        try {
            ReportingPeriodResolver.resolve(
                preset =
                    ReportingPeriodPreset.CUSTOM,
                timezoneId =
                    "Asia/Muscat",
                nowMillis =
                    now
            )

            fail(
                "CUSTOM reporting preset must require explicit boundaries."
            )
        }
        catch (_: IllegalArgumentException) {
            // Expected fail-closed behavior.
        }
    }

    @Test
    fun customUsesExactHalfOpenBoundaries() {

        val result =
            ReportingPeriodResolver.custom(
                fromInclusive =
                    100L,
                toExclusive =
                    200L
            )

        assertEquals(
            100L,
            result.fromInclusive
        )

        assertEquals(
            200L,
            result.toExclusive
        )

        assertEquals(
            ReportingPeriodPreset.CUSTOM,
            result.preset
        )
    }
}