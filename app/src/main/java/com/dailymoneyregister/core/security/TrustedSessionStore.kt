package com.dailymoneyregister.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TrustedSessionIds(
    val businessId: String,
    val branchId: String,
    val userId: String
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
            userId = userId
        )
    }

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
    }
}
