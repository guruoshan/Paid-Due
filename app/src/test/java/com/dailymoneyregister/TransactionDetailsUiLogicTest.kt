package com.dailymoneyregister

import com.dailymoneyregister.feature.register.ui.sortTransactionDetailsTimeline
import com.dailymoneyregister.feature.register.ui.validateTransactionDetailsTimezoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionDetailsUiLogicTest {

    private data class TimelineProbe(
        val id: String,
        val occurredAt: Long
    )

    @Test
    fun validBranchTimezoneIsAccepted() {
        assertEquals(
            "Asia/Muscat",
            validateTransactionDetailsTimezoneId("Asia/Muscat")
        )
    }

    @Test
    fun timezoneWhitespaceIsNormalized() {
        assertEquals(
            "Asia/Muscat",
            validateTransactionDetailsTimezoneId("  Asia/Muscat  ")
        )
    }

    @Test
    fun nullTimezoneFailsClosed() {
        assertNull(
            validateTransactionDetailsTimezoneId(null)
        )
    }

    @Test
    fun blankTimezoneFailsClosed() {
        assertNull(
            validateTransactionDetailsTimezoneId("   ")
        )
    }

    @Test
    fun invalidTimezoneFailsClosed() {
        assertNull(
            validateTransactionDetailsTimezoneId("Not/A_Real_Zone")
        )
    }

    @Test
    fun timelineSortsChronologically() {
        val input = listOf(
            TimelineProbe("refund", 300L),
            TimelineProbe("sale", 100L),
            TimelineProbe("correction", 200L)
        )

        val result =
            sortTransactionDetailsTimeline(
                items = input,
                occurredAt = { it.occurredAt },
                stableId = { it.id }
            )

        assertEquals(
            listOf("sale", "correction", "refund"),
            result.map { it.id }
        )
    }

    @Test
    fun equalTimestampsUseStableIdForDeterministicOrder() {
        val input = listOf(
            TimelineProbe("void:z", 200L),
            TimelineProbe("refund:b", 200L),
            TimelineProbe("correction:a", 200L)
        )

        val result =
            sortTransactionDetailsTimeline(
                items = input,
                occurredAt = { it.occurredAt },
                stableId = { it.id }
            )

        assertEquals(
            listOf(
                "correction:a",
                "refund:b",
                "void:z"
            ),
            result.map { it.id }
        )
    }

    @Test
    fun timelineSortDoesNotMutateInput() {
        val input = listOf(
            TimelineProbe("later", 20L),
            TimelineProbe("earlier", 10L)
        )

        sortTransactionDetailsTimeline(
            items = input,
            occurredAt = { it.occurredAt },
            stableId = { it.id }
        )

        assertEquals(
            listOf("later", "earlier"),
            input.map { it.id }
        )
    }
}