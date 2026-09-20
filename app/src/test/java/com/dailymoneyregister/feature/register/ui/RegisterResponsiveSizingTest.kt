package com.dailymoneyregister.feature.register.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.dailymoneyregister.feature.register.ui.components.RecentRowHeightBudget
import org.junit.Test

class RegisterResponsiveSizingTest {
    @Test
    fun compactScreenKeepsPhoneSizedControls() {
        val sizing = RegisterResponsiveSizing.forScreen(320, 640)
        assertEquals(44, sizing.actionHeight.value.toInt())
        assertEquals(56, sizing.keypadButtonHeight.value.toInt())
    }

    @Test
    fun normalPhoneUsesComfortableSizing() {
        val sizing = RegisterResponsiveSizing.forScreen(400, 760)
        assertEquals(48, sizing.actionHeight.value.toInt())
        assertEquals(64, sizing.keypadButtonHeight.value.toInt())
    }

    @Test
    fun largeScreenScalesInteractiveControlsAndRecentRows() {
        val sizing = RegisterResponsiveSizing.forScreen(800, 1280)
        assertEquals(56, sizing.actionHeight.value.toInt())
        assertEquals(72, sizing.keypadButtonHeight.value.toInt())
        assertTrue(sizing.recentMaxHeight.value > 260f)
    }

    @Test
    fun compactHeightGrowsAcrossScreenSizesAndRemainsBelowExpandedHeight() {
        val sizes = listOf(
            RegisterResponsiveSizing.forScreen(320, 640),
            RegisterResponsiveSizing.forScreen(400, 760),
            RegisterResponsiveSizing.forScreen(800, 1280)
        )
        assertEquals(listOf(100f, 150f, 200f), sizes.map { it.compactRecentMaxHeight.value })
        assertEquals(listOf(180f, 260f, 360f), sizes.map { it.recentMaxHeight.value })
        sizes.zipWithNext().forEach { (smaller, larger) ->
            assertTrue(smaller.compactRecentMaxHeight < larger.compactRecentMaxHeight)
        }
        sizes.forEach { assertTrue(it.compactRecentMaxHeight < it.recentMaxHeight) }
    }

    @Test
    fun heightBreakpointsAlsoIncreaseCompactSpaceAtTheSameWidth() {
        assertEquals(
            listOf(100f, 150f, 200f),
            listOf(640, 700, 900).map {
                RegisterResponsiveSizing.forScreen(320, it).compactRecentMaxHeight.value
            }
        )
    }

    @Test
    fun compactCapacityDependsOnMeasuredHeightRatherThanFixedRowCount() {
        fun capacity(maxHeight: Int, rowHeight: Int): Int {
            val budget = RecentRowHeightBudget(maxHeight, 6)
            return generateSequence { rowHeight }.takeWhile { budget.add(it) }.count()
        }
        assertEquals(2, capacity(100, 40))
        assertEquals(4, capacity(200, 40))
        assertEquals(3, capacity(200, 60))
        assertEquals(7, capacity(200, 20))
    }

    @Test
    fun compactBudgetIncludesOnlyCompleteRowsAndNoReservedSlots() {
        val budget = RecentRowHeightBudget(100, 6)
        assertEquals(0, budget.usedHeight)
        assertTrue(budget.add(40))
        assertTrue(budget.add(54))
        assertEquals(100, budget.usedHeight)
        assertFalse(budget.add(1))
        assertEquals(100, budget.usedHeight)
        val tooSmall = RecentRowHeightBudget(30, 6)
        assertFalse(tooSmall.add(40))
        assertEquals(0, tooSmall.usedHeight)
    }
}
