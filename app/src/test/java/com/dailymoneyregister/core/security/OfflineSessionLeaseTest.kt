package com.dailymoneyregister.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionLeaseTest {
    private val wallNow = 1_800_000_000_000L
    private val elapsedNow = 400_000_000L
    private val bootCount = 42

    private fun valid(wallAge: Long, elapsedAge: Long, currentBoot: Int = bootCount) =
        OfflineSessionLeasePolicy.isValid(
            lastVerifiedAtMillis = wallNow - wallAge,
            nowMillis = wallNow,
            lastVerifiedElapsedMillis = elapsedNow - elapsedAge,
            nowElapsedMillis = elapsedNow,
            lastVerifiedBootCount = bootCount,
            currentBootCount = currentBoot
        )

    @Test
    fun allowsPreviouslyVerifiedSessionWithinTwentyFourHourLeaseOnSameBoot() {
        assertTrue(valid(23 * 60 * 60 * 1000L, 23 * 60 * 60 * 1000L))
    }

    @Test
    fun rejectsExpiredOrMissingVerificationTimestamp() {
        assertFalse(valid(25 * 60 * 60 * 1000L, 7 * 60 * 60 * 1000L))
        assertFalse(valid(7 * 60 * 60 * 1000L, 25 * 60 * 60 * 1000L))
        assertFalse(
            OfflineSessionLeasePolicy.isValid(
                lastVerifiedAtMillis = 0L,
                nowMillis = wallNow,
                lastVerifiedElapsedMillis = 0L,
                nowElapsedMillis = elapsedNow,
                lastVerifiedBootCount = bootCount,
                currentBootCount = bootCount
            )
        )
    }

    @Test
    fun rejectsClockRollbackAndDeviceReboot() {
        assertFalse(
            OfflineSessionLeasePolicy.isValid(
                lastVerifiedAtMillis = wallNow,
                nowMillis = wallNow - 1L,
                lastVerifiedElapsedMillis = elapsedNow,
                nowElapsedMillis = elapsedNow + 1L,
                lastVerifiedBootCount = bootCount,
                currentBootCount = bootCount
            )
        )
        assertFalse(valid(1L, 1L, currentBoot = bootCount + 1))
    }
}
