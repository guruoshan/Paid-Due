package com.dailymoneyregister.core.security

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor { updates[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { updates[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { updates[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { updates[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { updates[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { updates[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removals += key; return this }
        override fun clear(): SharedPreferences.Editor { removals += values.keys; return this }
        override fun commit(): Boolean { removals.forEach(values::remove); values.putAll(updates); return true }
        override fun apply() { commit() }
    }
}

class PinThrottlerTest {
    @Test
    fun lockoutOccursAfterFiveFailures() {
        val throttler = PinThrottler(maxAttempts = 5, lockoutDurationMillis = 30_000L)
        repeat(4) { assertEquals(0L, throttler.recordFailure("cashier", 1_000L)) }
        assertEquals(30L, throttler.recordFailure("cashier", 1_000L))
        assertTrue(throttler.isLockedOut("cashier", 1_000L))
    }

    @Test
    fun lockoutExpiresAfterThirtySeconds() {
        val throttler = PinThrottler(maxAttempts = 5, lockoutDurationMillis = 30_000L)
        repeat(5) { throttler.recordFailure("cashier", 1_000L) }
        assertTrue(throttler.isLockedOut("cashier", 30_999L))
        assertFalse(throttler.isLockedOut("cashier", 31_000L))
    }

    @Test
    fun successClearsFailureState() {
        val throttler = PinThrottler(maxAttempts = 5, lockoutDurationMillis = 30_000L)
        repeat(4) { throttler.recordFailure("cashier", 1_000L) }
        throttler.recordSuccess("cashier")
        assertEquals(0L, throttler.recordFailure("cashier", 1_000L))
    }

    @Test
    fun stateSurvivesNewInstanceWithSharedPreferences() {
        val prefs = TestSharedPreferences()
        repeat(4) { PinThrottler(prefs = prefs).recordFailure("cashier", 1_000L) }
        val restored = PinThrottler(prefs = prefs)
        assertEquals(30L, restored.recordFailure("cashier", 1_000L))
        assertTrue(restored.isLockedOut("cashier", 1_000L))
    }
}
