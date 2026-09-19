package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustedSessionRestorer @Inject constructor(
    private val database: DmrDatabase,
    private val trustedSessionStore: TrustedSessionStore,
    private val languageManager: LanguageManager
) {

    suspend fun restore(): ActiveSession? {
        val ids =
            trustedSessionStore.load()
                ?: return null

        val businessDao =
            database.businessBranchDao()

        val userDao =
            database.userDao()

        val business =
            businessDao.getBusinessById(ids.businessId)
                ?: return invalidate()

        val branch =
            businessDao.getBranchById(ids.branchId)
                ?: return invalidate()

        val user =
            userDao.getUserById(ids.userId)
                ?: return invalidate()

        if (
            branch.businessId != business.id ||
            user.businessId != business.id ||
            !user.isActive
        ) {
            return invalidate()
        }

        val membership =
            userDao.getMembership(
                user.id,
                branch.id
            )

        val session =
            when {
                user.role == UserRole.ADMIN &&
                    user.authType == AuthType.PASSWORD -> {
                    ActiveSession(
                        business = business,
                        branch = branch,
                        user = user,
                        membership = membership,
                        requiresPinChange = false
                    )
                }

                user.role == UserRole.CASHIER &&
                    user.authType == AuthType.PIN &&
                    membership != null -> {
                    ActiveSession(
                        business = business,
                        branch = branch,
                        user = user,
                        membership = membership,
                        requiresPinChange = user.mustChangePin
                    )
                }

                else -> {
                    return invalidate()
                }
            }

        SessionManager.setSession(session)

        languageManager.setLanguage(
            user.languageCode
        )

        return session
    }

    private fun invalidate(): ActiveSession? {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        return null
    }
}