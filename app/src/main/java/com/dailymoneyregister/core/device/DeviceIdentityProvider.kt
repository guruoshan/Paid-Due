package com.dailymoneyregister.core.device

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a stable, anonymous installation ID that:
 * - Is generated once using a random UUID on first use.
 * - Is persisted in private SharedPreferences (key "device_id", file "dmr_device_identity").
 * - Survives app restarts (same ID returned on every subsequent launch).
 * - Does not use IMEI, hardware serial, or any device identifier.
 * - Serializes first-use reads and writes across provider instances in this process.
 * - Requires the initial synchronous write to succeed before returning a new ID.
 *
 * The [prefsProvider] parameter exists for testability; production callers use the
 * @Inject constructor which supplies the real SharedPreferences automatically.
 */
@Singleton
class DeviceIdentityProvider(
    private val prefsProvider: () -> SharedPreferences
) {
    companion object {
        private const val PREFS_NAME = "dmr_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private val identityLock = Any()
    }

    /** Hilt-injected constructor. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefsProvider = { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    )

    val deviceId: String by lazy {
        synchronized(identityLock) {
            val prefs = prefsProvider()
            prefs.getString(KEY_DEVICE_ID, null) ?: run {
                val id = UUID.randomUUID().toString()
                check(prefs.edit().putString(KEY_DEVICE_ID, id).commit()) {
                    "Could not persist installation identity"
                }
                id
            }
        }
    }
}
