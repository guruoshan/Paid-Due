package com.dailymoneyregister.feature.register.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayClosingPresentationTest {

    @Test
    fun shortIsCountedMinusExpected() {
        val result =
            requireNotNull(
                dayClosingReconciliation(
                    expectedCashMinor = 111_000L,
                    countedCashMinor = 110_000L
                )
            )

        assertEquals(
            -1_000L,
            result.differenceMinor
        )

        assertEquals(
            DayClosingVarianceStatus.SHORT,
            result.status
        )
    }

    @Test
    fun exactHasZeroDifference() {
        val result =
            requireNotNull(
                dayClosingReconciliation(
                    expectedCashMinor = 111_000L,
                    countedCashMinor = 111_000L
                )
            )

        assertEquals(
            0L,
            result.differenceMinor
        )

        assertEquals(
            DayClosingVarianceStatus.EXACT,
            result.status
        )
    }

    @Test
    fun overIsCountedMinusExpected() {
        val result =
            requireNotNull(
                dayClosingReconciliation(
                    expectedCashMinor = 111_000L,
                    countedCashMinor = 112_000L
                )
            )

        assertEquals(
            1_000L,
            result.differenceMinor
        )

        assertEquals(
            DayClosingVarianceStatus.OVER,
            result.status
        )
    }

    @Test
    fun omrCountParsesUsingThreeDecimals() {
        assertEquals(
            110_000L,
            parseDayClosingCountedMinor(
                value = "110",
                decimalPlaces = 3
            )
        )

        assertEquals(
            110_500L,
            parseDayClosingCountedMinor(
                value = "110.500",
                decimalPlaces = 3
            )
        )

        assertNull(
            parseDayClosingCountedMinor(
                value = "110.0001",
                decimalPlaces = 3
            )
        )
    }

    @Test
    fun negativeCountIsRejected() {
        assertNull(
            parseDayClosingCountedMinor(
                value = "-1",
                decimalPlaces = 3
            )
        )
    }
}