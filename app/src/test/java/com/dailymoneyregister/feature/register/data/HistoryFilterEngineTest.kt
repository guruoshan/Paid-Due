package com.dailymoneyregister.feature.register.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HistoryFilterEngineTest {

    @Test
    fun phoneSearchUsesCanonicalPhoneWithFormattingTolerance() {

        assertEquals(
            "96891234567",
            historyPhoneDigits(
                "+968 9123-4567"
            )
        )

        assertTrue(
            historyPhoneMatches(
                canonicalPhone =
                    "+96891234567",
                query =
                    "9123 4567"
            )
        )

        assertTrue(
            historyPhoneMatches(
                canonicalPhone =
                    "+96891234567",
                query =
                    "+968-91234567"
            )
        )

        assertFalse(
            historyPhoneMatches(
                canonicalPhone =
                    "+96891234567",
                query =
                    "99887766"
            )
        )
    }

    @Test
    fun dateSearchUsesRequestedBranchTimezone() {

        val zone =
            ZoneId.of(
                "Asia/Muscat"
            )

        val epoch =
            LocalDateTime.of(
                2026,
                9,
                20,
                10,
                30
            )
                .atZone(zone)
                .toInstant()
                .toEpochMilli()

        assertTrue(
            historyDateMatches(
                epochMillis =
                    epoch,
                timezoneId =
                    "Asia/Muscat",
                query =
                    "20/09/2026"
            )
        )

        assertTrue(
            historyDateMatches(
                epochMillis =
                    epoch,
                timezoneId =
                    "Asia/Muscat",
                query =
                    "20-09-2026"
            )
        )

        assertFalse(
            historyDateMatches(
                epochMillis =
                    epoch,
                timezoneId =
                    "Asia/Muscat",
                query =
                    "21/09/2026"
            )
        )
    }

    @Test
    fun amountSearchUsesMoneyMinorUnitsParser() {

        val parsed =
            historyAmountQuery(
                query =
                    "5.500",
                decimalPlaces =
                    3
            )

        assertEquals(
            5500L,
            parsed?.amount
        )
    }
}