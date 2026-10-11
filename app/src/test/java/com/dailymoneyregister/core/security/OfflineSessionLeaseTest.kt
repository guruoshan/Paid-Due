package com.dailymoneyregister.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionLeaseTest {
    private val wallNow = 1_800_000_000_000L
    private val elapsedNow = 400_000_000L

    private fun valid(wallAge: Long, elapsedAge: Long) = OfflineSessionLeasePolicy.isValid(
        lastVerifiedAtMillis = wallNow - wallAge,
        nowMillis = wallNow,
        lastVerifiedElapsedMillis = elapsedNow - elapsedAge,
        nowElapsedMillis = elapsedNow
    )

    @Test
    fun allowsPreviouslyVerifiedSessionWithinEightHourLease() {
        assertTrue(valid(7 * 60 * 60 * 1000L, 7 * 60 * 60 * 1000L))
    }

    @Test
    fun rejectsExpiredOrMissingVerificationTimestamp() {
        assertFalse(valid(9 * 60 * 60 * 1000L, 7 * 60 * 60 * 1000L))
        assertFalse(valid(7 * 60 * 60 * 1000L, 9 * 60 * 60 * 1000L))
        assertFalse(
            OfflineSessionLeasePolicy.isValid(
                lastVerifiedAtMillis = 0L,
                nowMillis = wallNow,
                lastVerifiedElapsedMillis = 0L,
                nowElapsedMillis = elapsedNow
            )
        )
    }

    @Test
    fun rejectsClockRollbackAndRebootElapsedReset() {
        assertFalse(
            OfflineSessionLeasePolicy.isValid(
                lastVerifiedAtMillis = wallNow,
                nowMillis = wallNow - 1L,
                lastVerifiedElapsedMillis = elapsedNow,
                nowElapsedMillis = elapsedNow + 1L
            )
        )
        assertFalse(
            OfflineSessionLeasePolicy.isValid(
                lastVerifiedAtMillis = wallNow,
                nowMillis = wallNow + 1L,
                lastVerifiedElapsedMillis = elapsedNow,
                nowElapsedMillis = 1L
            )
        )
    }
}
