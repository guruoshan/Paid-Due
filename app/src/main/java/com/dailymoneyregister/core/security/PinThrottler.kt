package com.dailymoneyregister.core.security

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent PIN authentication throttling to prevent brute-force attacks.
 * Policy:
 * - Max 5 consecutive failed attempts
 * - 30 seconds lockout after 5 failed attempts
 * Persists failure counts and lockout timestamps using SharedPreferences so app restarts
 * cannot bypass active lockouts.
 */
class PinThrottler(
    private val context: Context? = null,
    private val prefs: SharedPreferences? = context?.getSharedPreferences("pin_throttler_prefs", Context.MODE_PRIVATE),
    private val maxAttempts: Int = 5,
    private val lockoutDurationMillis: Long = 30_000L
) {
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val lockoutTimestamps = ConcurrentHashMap<String, Long>()

    private fun getFailures(identifier: String): Int {
        val inMemory = failureCounts[identifier]
        if (inMemory != null) return inMemory
        val countFromPrefs = prefs?.getInt("count_$identifier", 0) ?: 0
        failureCounts[identifier] = countFromPrefs
        return countFromPrefs
    }

    private fun getLockoutUntil(identifier: String): Long? {
        val inMemory = lockoutTimestamps[identifier]
        if (inMemory != null) return inMemory
        if (prefs?.contains("lockout_$identifier") == true) {
            val ts = prefs.getLong("lockout_$identifier", 0L)
            lockoutTimestamps[identifier] = ts
            return ts
        }
        return null
    }

    /**
     * Checks if the given user or identifier is currently locked out.
     * Returns remaining lockout seconds if locked, or 0 if allowed.
     */
    fun getRemainingLockoutSeconds(identifier: String, currentTimeMillis: Long = System.currentTimeMillis()): Long {
        val lockoutUntil = getLockoutUntil(identifier) ?: return 0L
        val remaining = lockoutUntil - currentTimeMillis
        return if (remaining > 0L) {
            (remaining + 999L) / 1000L // Round up to seconds
        } else {
            // Lockout expired
            resetLockout(identifier)
            0L
        }
    }

    fun isLockedOut(identifier: String, currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return getRemainingLockoutSeconds(identifier, currentTimeMillis) > 0L
    }

    /**
     * Records a failed attempt. If threshold reached, triggers lockout.
     */
    fun recordFailure(identifier: String, currentTimeMillis: Long = System.currentTimeMillis()): Long {
        val currentFailures = getFailures(identifier) + 1
        failureCounts[identifier] = currentFailures
        prefs?.edit()?.putInt("count_$identifier", currentFailures)?.commit()

        if (currentFailures >= maxAttempts) {
            val lockoutUntil = currentTimeMillis + lockoutDurationMillis
            lockoutTimestamps[identifier] = lockoutUntil
            prefs?.edit()?.putLong("lockout_$identifier", lockoutUntil)?.commit()
            return lockoutDurationMillis / 1000L
        }
        return 0L
    }

    /**
     * Records a successful authentication, resetting failed attempts.
     */
    fun recordSuccess(identifier: String) {
        resetLockout(identifier)
    }

    fun resetLockout(identifier: String) {
        failureCounts.remove(identifier)
        lockoutTimestamps.remove(identifier)
        prefs?.edit()
            ?.remove("count_$identifier")
            ?.remove("lockout_$identifier")
            ?.commit()
    }
}
