package com.dailymoneyregister.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dailymoneyregister.core.database.DmrDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Development convenience only.
 *
 * In a debuggable APK, restores a real existing business/branch/user session
 * without asking for password or PIN.
 *
 * Release builds never bypass authentication.
 */
@Singleton
class DebugSessionBootstrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DmrDatabase
) {

    suspend fun bootstrapIfDebug(): Boolean {
        val isDebuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (!isDebuggable) return false

        val businessDao = database.businessBranchDao()
        val userDao = database.userDao()

        val business = businessDao.getFirstBusiness() ?: return false
        val branch = businessDao.getFirstBranch(business.id) ?: return false

        // Debug convenience must follow the active app's admin-only login policy.
        val user = userDao.getFirstAdmin(business.id)?.takeIf { it.isActive }
            ?: return false

        val membership = userDao.getMembership(user.id, branch.id) ?: return false

        SessionManager.setSession(
            ActiveSession(
                business = business,
                branch = branch,
                user = user,
                membership = membership
            )
        )

        return true
    }
}
