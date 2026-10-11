package com.dailymoneyregister.core.security

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TrustedSessionIds(
    val businessId: String,
    val branchId: String,
    val userId: String,
    val lastServerVerifiedAtMillis: Long,
    val lastServerVerifiedElapsedMillis: Long
)

@Singleton
class TrustedSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            lastServerVerifiedElapsedMillis = prefs.getLong(KEY_LAST_SERVER_VERIFIED_ELAPSED, 0L)
        )
    }

    fun markServerVerified(nowMillis: Long = System.currentTimeMillis()) {
        check(
            prefs.edit()
                .putLong(KEY_LAST_SERVER_VERIFIED_AT, nowMillis)
                .putLong(KEY_LAST_SERVER_VERIFIED_ELAPSED, SystemClock.elapsedRealtime())
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
        nowElapsedMillis = nowElapsedMillis
    )

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
    }
}


internal object OfflineSessionLeasePolicy {
    const val MAX_AGE_MILLIS: Long = 8 * 60 * 60 * 1000L

    fun isValid(
        lastVerifiedAtMillis: Long,
        nowMillis: Long,
        lastVerifiedElapsedMillis: Long,
        nowElapsedMillis: Long
    ): Boolean {
        // Wall-clock and monotonic elapsed time must both agree. This prevents a
        // user-adjusted clock from extending the lease; a reboot resets elapsed time
        // and invalidates the lease until the next server-verified login.
        if (
            lastVerifiedAtMillis <= 0L ||
            lastVerifiedElapsedMillis <= 0L ||
            nowMillis < lastVerifiedAtMillis ||
            nowElapsedMillis < lastVerifiedElapsedMillis
        ) return false
        return nowMillis - lastVerifiedAtMillis <= MAX_AGE_MILLIS &&
            nowElapsedMillis - lastVerifiedElapsedMillis <= MAX_AGE_MILLIS
    }
}
