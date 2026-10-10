package com.dailymoneyregister.core.cloud.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessLocalDateTest {
    @Test
    fun midnightBoundaryUsesBranchTimezone() {
        assertEquals(
            "2026-01-01",
            businessLocalDateFor(Instant.parse("2026-01-01T19:59:59Z").toEpochMilli(), "Asia/Muscat")
        )
        assertEquals(
            "2026-01-02",
            businessLocalDateFor(Instant.parse("2026-01-01T20:00:00Z").toEpochMilli(), "Asia/Muscat")
        )
    }

    @Test
    fun dubaiUsesItsOwnZoneRules() {
        assertEquals(
            "2026-01-02",
            businessLocalDateFor(Instant.parse("2026-01-01T20:00:00Z").toEpochMilli(), "Asia/Dubai")
        )
    }
}
