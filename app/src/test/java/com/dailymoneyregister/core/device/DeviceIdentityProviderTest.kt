package com.dailymoneyregister.core.device

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM unit tests for DeviceIdentityProvider - no Android framework required.
 *
 * DeviceIdentityProvider accepts a [prefsProvider] lambda for testability.
 * The in-memory SharedPreferences stub below fulfils the same contract as
 * a real SharedPreferences instance.
 *
 * Tests:
 *  1. Generated device ID is non-blank.
 *  2. Repeated calls on the same instance return the same ID (lazy val).
 *  3. A second provider instance backed by the same backing store returns the
 *     same ID, simulating app restart / process death and recreation.
 */
class DeviceIdentityProviderTest {

    // Shared in-memory store, persists between DeviceIdentityProvider instances in a test
    private val store = mutableMapOf<String, String?>()

    private fun makePrefs(): SharedPreferences = object : SharedPreferences {
        override fun getString(key: String, defValue: String?): String? =
            if (store.containsKey(key)) store[key] else defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = apply { store[k] = v }
            override fun remove(k: String) = apply { store.remove(k) }
            override fun clear() = apply { store.clear() }
            override fun commit() = true
            override fun apply() {}
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
            override fun putBoolean(k: String, v: Boolean) = this
        }

        override fun contains(key: String) = store.containsKey(key)
        override fun getAll(): Map<String, *> = store.toMap()
        override fun getStringSet(k: String, d: MutableSet<String>?) = null
        override fun getInt(k: String, d: Int) = d
        override fun getLong(k: String, d: Long) = d
        override fun getFloat(k: String, d: Float) = d
        override fun getBoolean(k: String, d: Boolean) = d
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener
        ) {}
    }

    @Before
    fun setUp() {
        store.clear()
    }

    @Test
    fun `deviceId is non-blank`() {
        val provider = DeviceIdentityProvider { makePrefs() }
        assertTrue("Device ID must not be blank", provider.deviceId.isNotBlank())
    }

    @Test
    fun `repeated calls on same instance return stable ID`() {
        val provider = DeviceIdentityProvider { makePrefs() }
        val id1 = provider.deviceId
        val id2 = provider.deviceId
        assertEquals("Same instance must always return same ID (lazy val)", id1, id2)
    }

    @Test
    fun `new provider instance reads persisted ID simulating app restart`() {
        // First app launch - generates and persists the ID
        val id1 = DeviceIdentityProvider { makePrefs() }.deviceId

        // Second app launch - same backing store, new provider instance
        val id2 = DeviceIdentityProvider { makePrefs() }.deviceId

        assertEquals(
            "A restarted app must return the same persisted device ID",
            id1,
            id2
        )
    }
}
