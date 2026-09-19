package com.dailymoneyregister.feature.register.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue(sizing.keypadButtonHeight.value > 56f)
    }

    @Test
    fun largeScreenScalesInteractiveControlsAndRecentRows() {
        val sizing = RegisterResponsiveSizing.forScreen(800, 1280)
        assertTrue(sizing.actionHeight.value > 48f)
        assertTrue(sizing.keypadButtonHeight.value > 64f)
        assertTrue(sizing.recentMaxHeight.value > 260f)
    }
}
