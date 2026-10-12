package com.dailymoneyregister.core.cloud.sync

import android.util.Log
import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes the server-authoritative permission snapshot without replacing the
 * active local session or rotating/clearing the custom cashier sync credential.
 */
@Singleton
class CashierPermissionMetadataRefresher @Inject constructor(
    private val database: DmrDatabase
) {
    suspend fun refreshActiveCashier(): Boolean {
        val active = SessionManager.currentSession.value ?: return false
        if (active.user.role != UserRole.CASHIER) return false
        val currentMembership = active.membership ?: return false
        val membershipId = currentMembership.id
        if (CashierCloudSession.tokenFor(membershipId) == null) return false

        Log.d("PaidDueFinancialSync", "CASHIER_PERMISSION_REFRESH_REQUEST membership=$membershipId")
        val metadata = CashierCloudSession.fetchMetadata(membershipId)
        val permissions = metadata.optJSONObject("permissions")
            ?: throw IllegalArgumentException("CASHIER_METADATA_PERMISSIONS_MISSING")
        Log.d(
            "PaidDueFinancialSync",
            "CASHIER_PERMISSION_PAYLOAD_RECEIVED membership=$membershipId key_count=${permissions.length()}"
        )
        return applyMetadataForActiveCashier(active, metadata)
    }

    internal suspend fun applyMetadataForActiveCashier(
        active: ActiveSession,
        metadata: JSONObject
    ): Boolean {
        if (active.user.role != UserRole.CASHIER) return false
        val currentMembership = active.membership ?: return false
        val membershipId = currentMembership.id
        require(metadata.optString("membership_id") == membershipId) {
            "CASHIER_METADATA_MEMBERSHIP_MISMATCH"
        }
        require(metadata.optString("business_id") == active.business.id) {
            "CASHIER_METADATA_BUSINESS_MISMATCH"
        }
        require(metadata.optString("branch_id") == active.branch.id) {
            "CASHIER_METADATA_BRANCH_MISMATCH"
        }
        require(metadata.optString("role") == "CASHIER") {
            "CASHIER_METADATA_ROLE_MISMATCH"
        }

        val updatedMembership = applyPermissionSnapshot(
            currentMembership,
            metadata.optJSONObject("permissions")
                ?: throw IllegalArgumentException("CASHIER_METADATA_PERMISSIONS_MISSING")
        )
        val beforeWrite = SessionManager.currentSession.value
        if (!sameSession(beforeWrite, active)) return false

        database.withTransaction {
            val persisted = database.userDao().getMembership(active.user.id, active.branch.id)
                ?: throw IllegalStateException("CASHIER_LOCAL_MEMBERSHIP_MISSING")
            require(persisted.id == membershipId) { "CASHIER_LOCAL_MEMBERSHIP_MISMATCH" }
            database.userDao().updateMembership(updatedMembership)
        }

        val stillActive = SessionManager.currentSession.value
        if (sameSession(stillActive, active)) {
            SessionManager.setSession(active.copy(membership = updatedMembership))
            return true
        }
        return false
    }

    internal fun applyPermissionSnapshot(
        current: UserBranchMembershipEntity,
        permissions: JSONObject
    ): UserBranchMembershipEntity {
        fun value(key: String): Boolean {
            require(permissions.has(key) && !permissions.isNull(key)) {
                Log.e("PaidDueFinancialSync", "CASHIER_PERMISSION_KEY_MISSING key=$key")
                "CASHIER_METADATA_PERMISSION_MISSING:$key"
            }
            return permissions.getBoolean(key)
        }
        val updated = current.copy(
            canCreateSale = value("can_create_sale"),
            canAcceptCash = value("can_accept_cash"),
            canAcceptOnline = value("can_accept_online"),
            canCreateCreditSale = value("can_create_credit_sale"),
            canUseCoupon = value("can_use_coupon"),
            canIssueInvoice = value("can_issue_invoice"),
            canIssueReceipt = value("can_issue_receipt"),
            canRefund = value("can_refund"),
            canVoid = value("can_void"),
            canCorrectAmount = value("can_correct_amount"),
            canChangePaymentMethod = value("can_change_payment_method"),
            canViewBroadHistory = value("can_view_broad_history"),
            canManageProducts = value("can_manage_products"),
            canViewReports = value("can_view_reports")
        )
        Log.d("PaidDueFinancialSync", "CASHIER_PERMISSION_SNAPSHOT_PARSED key_count=14")
        return updated
    }

    private fun sameSession(current: ActiveSession?, expected: ActiveSession): Boolean =
        current != null &&
            current.user.id == expected.user.id &&
            current.business.id == expected.business.id &&
            current.branch.id == expected.branch.id &&
            current.membership?.id == expected.membership?.id
}
