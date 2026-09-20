package com.dailymoneyregister.feature.register.data

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecentDayBoundaryTest {

    @Test
    fun emitsCurrentAndNextLocalDayStartAcrossRollover() = runBlocking {
        val zoneId = ZoneId.of("Asia/Muscat")
        var now = ZonedDateTime.of(2026, 9, 20, 23, 59, 59, 0, zoneId)
            .toInstant()
            .toEpochMilli()

        val boundaries = observeLocalDayStarts(
            zoneId = zoneId,
            nowMillis = { now },
            waitFor = { waitMillis -> now += waitMillis }
        ).take(2).toList()

        assertEquals(
            ZonedDateTime.of(2026, 9, 20, 0, 0, 0, 0, zoneId).toInstant().toEpochMilli(),
            boundaries[0]
        )
        assertEquals(
            ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, zoneId).toInstant().toEpochMilli(),
            boundaries[1]
        )
    }

    @Test
    fun computesNextBoundaryByLocalCalendarAcrossDst() = runBlocking {
        val zoneId = ZoneId.of("America/New_York")
        var now = ZonedDateTime.of(2026, 3, 8, 1, 59, 59, 0, zoneId)
            .toInstant()
            .toEpochMilli()
        val waits = mutableListOf<Long>()

        val boundaries = observeLocalDayStarts(
            zoneId = zoneId,
            nowMillis = { now },
            waitFor = { waitMillis ->
                waits += waitMillis
                now += waitMillis
            }
        ).take(2).toList()

        assertEquals(2, boundaries.size)
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zoneId).toInstant().toEpochMilli(),
            boundaries[0]
        )
        assertEquals(
            ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zoneId).toInstant().toEpochMilli(),
            boundaries[1]
        )
        assertNotEquals(24 * 60 * 60 * 1000L, waits.single())
    }
}
