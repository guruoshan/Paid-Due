package com.dailymoneyregister.core.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TrustedSessionIds(
    val businessId: String,
    val branchId: String,
    val userId: String,
    val lastServerVerifiedAtMillis: Long,
    val lastServerVerifiedElapsedMillis: Long,
    val lastServerVerifiedBootCount: Int
)

@Singleton
class TrustedSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        businessId: String,
        branchId: String,
        userId: String
    ) {
        check(
            prefs.edit()
                .putString(KEY_BUSINESS_ID, businessId)
                .putString(KEY_BRANCH_ID, branchId)
                .putString(KEY_USER_ID, userId)
                .putLong(KEY_LAST_SERVER_VERIFIED_AT, System.currentTimeMillis())
                .putLong(KEY_LAST_SERVER_VERIFIED_ELAPSED, SystemClock.elapsedRealtime())
                .putInt(KEY_LAST_SERVER_VERIFIED_BOOT_COUNT, currentBootCount())
                .commit()
        ) {
            "Could not persist trusted session"
        }
    }

    fun load(): TrustedSessionIds? {
        val businessId = prefs.getString(KEY_BUSINESS_ID, null)
        val branchId = prefs.getString(KEY_BRANCH_ID, null)
        val userId = prefs.getString(KEY_USER_ID, null)

        if (
            businessId.isNullOrBlank() ||
            branchId.isNullOrBlank() ||
            userId.isNullOrBlank()
        ) {
            return null
        }

        return TrustedSessionIds(
            businessId = businessId,
            branchId = branchId,
            userId = userId,
            lastServerVerifiedAtMillis = prefs.getLong(KEY_LAST_SERVER_VERIFIED_AT, 0L),
            lastServerVerifiedElapsedMillis = prefs.getLong(KEY_LAST_SERVER_VERIFIED_ELAPSED, 0L),
            lastServerVerifiedBootCount = prefs.getInt(KEY_LAST_SERVER_VERIFIED_BOOT_COUNT, -1)
        )
    }

    fun markServerVerified(nowMillis: Long = System.currentTimeMillis()) {
        check(
            prefs.edit()
                .putLong(KEY_LAST_SERVER_VERIFIED_AT, nowMillis)
                .putLong(KEY_LAST_SERVER_VERIFIED_ELAPSED, SystemClock.elapsedRealtime())
                .putInt(KEY_LAST_SERVER_VERIFIED_BOOT_COUNT, currentBootCount())
                .commit()
        ) {
            "Could not update trusted session verification time"
        }
    }

    fun isOfflineLeaseValid(
        nowMillis: Long = System.currentTimeMillis(),
        nowElapsedMillis: Long = SystemClock.elapsedRealtime()
    ): Boolean = OfflineSessionLeasePolicy.isValid(
        lastVerifiedAtMillis = prefs.getLong(KEY_LAST_SERVER_VERIFIED_AT, 0L),
        nowMillis = nowMillis,
        lastVerifiedElapsedMillis = prefs.getLong(KEY_LAST_SERVER_VERIFIED_ELAPSED, 0L),
        nowElapsedMillis = nowElapsedMillis,
        lastVerifiedBootCount = prefs.getInt(KEY_LAST_SERVER_VERIFIED_BOOT_COUNT, -1),
        currentBootCount = currentBootCount()
    )

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    fun clear() {
        check(prefs.edit().clear().commit()) {
            "Could not clear trusted session"
        }
    }

    companion object {
        const val PREFS_NAME = "dmr_trusted_session"

        private const val KEY_BUSINESS_ID = "business_id"
        private const val KEY_BRANCH_ID = "branch_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_SERVER_VERIFIED_AT = "last_server_verified_at"
        private const val KEY_LAST_SERVER_VERIFIED_ELAPSED = "last_server_verified_elapsed"
        private const val KEY_LAST_SERVER_VERIFIED_BOOT_COUNT = "last_server_verified_boot_count"
    }
}


internal object OfflineSessionLeasePolicy {
    const val MAX_AGE_MILLIS: Long = 24 * 60 * 60 * 1000L

    fun isValid(
        lastVerifiedAtMillis: Long,
        nowMillis: Long,
        lastVerifiedElapsedMillis: Long,
        nowElapsedMillis: Long,
        lastVerifiedBootCount: Int,
        currentBootCount: Int
    ): Boolean {
        // Wall clock, monotonic elapsed time, and boot count must all agree. This
        // prevents clock rollback and invalidates offline leases after device reboot.
        if (
            lastVerifiedAtMillis <= 0L ||
            lastVerifiedElapsedMillis <= 0L ||
            lastVerifiedBootCount < 0 ||
            currentBootCount < 0 ||
            currentBootCount != lastVerifiedBootCount ||
            nowMillis < lastVerifiedAtMillis ||
            nowElapsedMillis < lastVerifiedElapsedMillis
        ) return false
        return nowMillis - lastVerifiedAtMillis <= MAX_AGE_MILLIS &&
            nowElapsedMillis - lastVerifiedElapsedMillis <= MAX_AGE_MILLIS
    }
}
