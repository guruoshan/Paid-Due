package com.dailymoneyregister.core.security

import androidx.room.withTransaction

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.cloud.sync.CashierCloudSession
import com.dailymoneyregister.core.cloud.sync.RemoteFinancialSyncRepository
import com.dailymoneyregister.core.cloud.sync.SessionAuthorizationResult
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustedSessionRestorer @Inject constructor(
    private val database: DmrDatabase,
    private val trustedSessionStore: TrustedSessionStore,
    private val languageManager: LanguageManager,
    private val remoteFinancialSyncRepository: RemoteFinancialSyncRepository
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

        // Offline leases must not resurrect a session whose local branch membership
        // is missing or belongs to a different user/branch/role.
        if (!trustedMembershipMatches(user.id, branch.id, user.role, membership)) {
            return invalidate()
        }

        var session =
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

        val authorization = when (session.user.role) {
            UserRole.ADMIN -> remoteFinancialSyncRepository.validateAdminSession(
                expectedMembershipId = session.membership?.id.orEmpty(),
                expectedBusinessId = session.business.id,
                expectedBranchId = session.branch.id,
                expectedLocalUserId = session.user.id
            )
            UserRole.CASHIER -> {
                val membership = session.membership
                val membershipId = membership?.id
                if (membership == null || membershipId.isNullOrBlank() ||
                    CashierCloudSession.tokenFor(membershipId) == null
                ) {
                    SessionAuthorizationResult.UNAVAILABLE
                } else {
                    try {
                        val metadata = CashierCloudSession.fetchMetadata(membershipId)
                        if (
                            metadata.optString("membership_id") != membershipId ||
                            metadata.optString("business_id") != session.business.id ||
                            metadata.optString("branch_id") != session.branch.id ||
                            metadata.optString("role") != "CASHIER"
                        ) {
                            SessionAuthorizationResult.REJECTED
                        } else {
                            val permissions = metadata.optJSONObject("permissions")
                                ?: throw java.io.IOException("CASHIER_METADATA_PERMISSIONS_MISSING")
                            val required = listOf(
                                "can_create_sale", "can_accept_cash", "can_accept_online",
                                "can_create_credit_sale", "can_use_coupon", "can_issue_invoice",
                                "can_issue_receipt", "can_refund", "can_void", "can_correct_amount",
                                "can_change_payment_method", "can_view_broad_history",
                                "can_manage_products", "can_view_reports"
                            )
                            if (required.any { !permissions.has(it) || permissions.isNull(it) }) {
                                throw java.io.IOException("CASHIER_METADATA_PERMISSIONS_INCOMPLETE")
                            }
                            val refreshed = membership.copy(
                                canCreateSale = permissions.getBoolean("can_create_sale"),
                                canAcceptCash = permissions.getBoolean("can_accept_cash"),
                                canAcceptOnline = permissions.getBoolean("can_accept_online"),
                                canCreateCreditSale = permissions.getBoolean("can_create_credit_sale"),
                                canUseCoupon = permissions.getBoolean("can_use_coupon"),
                                canIssueInvoice = permissions.getBoolean("can_issue_invoice"),
                                canIssueReceipt = permissions.getBoolean("can_issue_receipt"),
                                canRefund = permissions.getBoolean("can_refund"),
                                canVoid = permissions.getBoolean("can_void"),
                                canCorrectAmount = permissions.getBoolean("can_correct_amount"),
                                canChangePaymentMethod = permissions.getBoolean("can_change_payment_method"),
                                canViewBroadHistory = permissions.getBoolean("can_view_broad_history"),
                                canManageProducts = permissions.getBoolean("can_manage_products"),
                                canViewReports = permissions.getBoolean("can_view_reports")
                            )
                            // Refresh non-financial business/branch configuration only
                            // after the server confirms the exact tenant and membership.
                            val businessName = metadata.optString("business_name")
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?: throw java.io.IOException("CASHIER_METADATA_BUSINESS_NAME_MISSING")
                            val branchName = metadata.optString("branch_name")
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?: throw java.io.IOException("CASHIER_METADATA_BRANCH_NAME_MISSING")
                            val branchCode = metadata.optString("branch_code")
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?: throw java.io.IOException("CASHIER_METADATA_BRANCH_CODE_MISSING")
                            val taxMode = runCatching {
                                TaxRoundingMode.valueOf(metadata.getString("tax_rounding_mode"))
                            }.getOrElse { throw java.io.IOException("CASHIER_METADATA_TAX_MODE_INVALID") }
                            val now = System.currentTimeMillis()
                            val updatedBusiness = session.business.copy(
                                name = businessName,
                                updatedAt = now
                            )
                            val updatedBranch = session.branch.copy(
                                name = branchName,
                                code = branchCode,
                                address = metadata.optString("branch_address").takeUnless { it.isBlank() || it == "null" },
                                phone = metadata.optString("branch_phone").takeUnless { it.isBlank() || it == "null" },
                                taxRegistrationNumber = metadata.optString("tax_registration_number").takeUnless { it.isBlank() || it == "null" },
                                countryCode = metadata.getString("country_code"),
                                currencyCode = metadata.getString("currency_code"),
                                currencyDecimalPlaces = metadata.getInt("currency_decimal_places"),
                                currencySymbol = metadata.getString("currency_symbol"),
                                timezoneId = metadata.getString("timezone_id"),
                                taxRoundingMode = taxMode,
                                defaultTaxRateBps = metadata.getInt("default_tax_rate_bps"),
                                updatedAt = now
                            )
                            database.withTransaction {
                                userDao.updateMembership(refreshed)
                                database.businessBranchDao().updateBusinessDetailsAtomically(
                                    updatedBusiness,
                                    updatedBranch
                                )
                            }
                            session = session.copy(
                                business = updatedBusiness,
                                branch = updatedBranch,
                                membership = refreshed
                            )
                            SessionAuthorizationResult.AUTHORIZED
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: SecurityException) {
                        SessionAuthorizationResult.REJECTED
                    } catch (_: Exception) {
                        SessionAuthorizationResult.UNAVAILABLE
                    }
                }
            }
        }

        when (authorization) {
            SessionAuthorizationResult.AUTHORIZED -> {
                trustedSessionStore.markServerVerified()
            }
            SessionAuthorizationResult.REJECTED -> return invalidate()
            SessionAuthorizationResult.UNAVAILABLE -> {
                if (!trustedSessionStore.isOfflineLeaseValid()) {
                    return invalidate()
                }
                val offlineSession = session.copy(isOfflineLease = true)
                SessionManager.setSession(offlineSession)
                languageManager.setLanguage(user.languageCode)
                return offlineSession
            }
        }

        SessionManager.setSession(session)
        languageManager.setLanguage(user.languageCode)
        return session
    }

    private fun invalidate(): ActiveSession? {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        return null
    }
}

internal fun trustedMembershipMatches(
    userId: String,
    branchId: String,
    role: UserRole,
    membership: com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity?
): Boolean =
    membership != null &&
        membership.userId == userId &&
        membership.branchId == branchId &&
        membership.role == role
