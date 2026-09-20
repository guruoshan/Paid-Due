package com.dailymoneyregister.core.model.timezone

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimezoneRegistryTest {

    @Test
    fun resolvesKnownTimezone() {
        assertEquals(
            ZoneId.of("Asia/Muscat"),
            TimezoneRegistry.resolve("Asia/Muscat")
        )
    }

    @Test
    fun acceptsValidTimezoneNotCurrentlyShownInSetup() {
        assertTrue(TimezoneRegistry.isValid("Europe/London"))
        assertFalse(
            TimezoneRegistry.setupTimezoneIds.contains("Europe/London")
        )
    }

    @Test
    fun rejectsInvalidTimezone() {
        assertFalse(TimezoneRegistry.isValid("Not/A_Timezone"))

        try {
            TimezoneRegistry.resolve("Not/A_Timezone")
            fail("Invalid timezone must be rejected")
        } catch (_: InvalidTimezoneException) {
            // Expected.
        }
    }

    @Test
    fun rejectsBlankTimezone() {
        assertFalse(TimezoneRegistry.isValid(""))
        assertFalse(TimezoneRegistry.isValid("   "))
    }

    @Test
    fun everyCurrentSetupTimezoneIsValid() {
        TimezoneRegistry.setupTimezoneIds.forEach { timezoneId ->
            assertTrue(
                "$timezoneId must be a valid timezone",
                TimezoneRegistry.isValid(timezoneId)
            )
        }
    }
}
