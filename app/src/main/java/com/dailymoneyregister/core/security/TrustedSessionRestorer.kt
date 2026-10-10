package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.cloud.BusinessRestorer
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.AuthorityConnectivity
import com.dailymoneyregister.core.security.AuthorityDecision
import com.dailymoneyregister.core.security.RemoteAuthorityResult
import com.dailymoneyregister.core.security.OnlineOfflineAuthorityPolicy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustedSessionRestorer @Inject constructor(
    private val database: DmrDatabase,
    private val trustedSessionStore: TrustedSessionStore,
    private val languageManager: LanguageManager,
    private val businessRestorer: BusinessRestorer
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

        // Local IDs and active flags are only cached data. Require a fresh
        // server membership check before restoring a remembered session.
        val remotelyAuthorized = when (user.role) {
            UserRole.ADMIN -> businessRestorer.validateAdminSession(
                business.id, branch.id, user.id
            )
            UserRole.CASHIER -> {
                val activeMembership = membership ?: return invalidate()
                val identity = userDao.getHumanIdentityById(user.identityId)
                val permissions = mapOf(
                    "CREATE_SALE" to activeMembership.canCreateSale,
                    "ACCEPT_CASH" to activeMembership.canAcceptCash,
                    "ACCEPT_ONLINE" to activeMembership.canAcceptOnline,
                    "CREATE_CREDIT_SALE" to activeMembership.canCreateCreditSale,
                    "USE_COUPON" to activeMembership.canUseCoupon,
                    "ISSUE_INVOICE" to activeMembership.canIssueInvoice,
                    "ISSUE_RECEIPT" to activeMembership.canIssueReceipt,
                    "REFUND" to activeMembership.canRefund,
                    "VOID" to activeMembership.canVoid,
                    "CORRECT_AMOUNT" to activeMembership.canCorrectAmount,
                    "CHANGE_PAYMENT_METHOD" to activeMembership.canChangePaymentMethod,
                    "VIEW_BROAD_HISTORY" to activeMembership.canViewBroadHistory,
                    "MANAGE_PRODUCTS" to activeMembership.canManageProducts,
                    "VIEW_REPORTS" to activeMembership.canViewReports
                )
                businessRestorer.validateCashierSession(
                    activeMembership.id, business.id, branch.id,
                    identity?.canonicalPhone, permissions
                )
            }
        }
        val decision = OnlineOfflineAuthorityPolicy.evaluate(
            connectivity = AuthorityConnectivity.ONLINE,
            remoteResult = if (remotelyAuthorized) RemoteAuthorityResult.AUTHORIZED
                else RemoteAuthorityResult.REVOKED,
            lastServerValidatedAtMillis = System.currentTimeMillis(),
            nowMillis = System.currentTimeMillis(),
            offlineGraceMillis = 0L
        )
        if (decision != AuthorityDecision.ALLOW_ONLINE) return invalidate()

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