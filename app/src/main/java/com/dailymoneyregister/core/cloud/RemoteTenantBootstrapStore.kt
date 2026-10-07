package com.dailymoneyregister.core.cloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PendingRemoteTenantBootstrap(
    val email: String
)

@Singleton
class RemoteTenantBootstrapStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun arm(
        email: String
    ): Result<Unit> {

        val normalizedEmail =
            email.trim().lowercase()

        if (normalizedEmail.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "Admin email is required for cloud bootstrap"
                )
            )
        }

        val existing =
            pendingOrNull()

        if (
            existing != null &&
            existing.email != normalizedEmail
        ) {
            return Result.failure(
                IllegalStateException(
                    "A different Admin cloud bootstrap is already pending"
                )
            )
        }

        val committed =
            preferences.edit()
                .putBoolean(KEY_PENDING, true)
                .putString(
                    KEY_PENDING_EMAIL,
                    normalizedEmail
                )
                .commit()

        if (!committed) {
            return Result.failure(
                IllegalStateException(
                    "Cloud bootstrap recovery state could not be persisted"
                )
            )
        }

        return Result.success(Unit)
    }

    fun pendingOrNull():
        PendingRemoteTenantBootstrap? {

        if (
            !preferences.getBoolean(
                KEY_PENDING,
                false
            )
        ) {
            return null
        }

        val email =
            preferences.getString(
                KEY_PENDING_EMAIL,
                null
            )
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        return PendingRemoteTenantBootstrap(
            email = email
        )
    }

    fun markLinked(
        businessId: String,
        branchId: String,
        adminUserId: String,
        authUserId: String
    ): Result<Unit> {

        val committed =
            preferences.edit()
                .putBoolean(KEY_PENDING, false)
                .remove(KEY_PENDING_EMAIL)
                .putString(
                    KEY_LINKED_BUSINESS_ID,
                    businessId
                )
                .putString(
                    KEY_LINKED_BRANCH_ID,
                    branchId
                )
                .putString(
                    KEY_LINKED_ADMIN_USER_ID,
                    adminUserId
                )
                .putString(
                    KEY_LINKED_AUTH_USER_ID,
                    authUserId
                )
                .commit()

        if (!committed) {
            return Result.failure(
                IllegalStateException(
                    "Cloud tenant link could not be durably recorded"
                )
            )
        }

        return Result.success(Unit)
    }

    companion object {

        private const val PREFS_NAME =
            "dmr_remote_tenant_bootstrap"

        private const val KEY_PENDING =
            "pending"

        private const val KEY_PENDING_EMAIL =
            "pending_email"

        private const val KEY_LINKED_BUSINESS_ID =
            "linked_business_id"

        private const val KEY_LINKED_BRANCH_ID =
            "linked_branch_id"

        private const val KEY_LINKED_ADMIN_USER_ID =
            "linked_admin_user_id"

        private const val KEY_LINKED_AUTH_USER_ID =
            "linked_auth_user_id"
    }
}