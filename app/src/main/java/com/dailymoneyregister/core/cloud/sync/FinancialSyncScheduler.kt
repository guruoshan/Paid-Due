package com.dailymoneyregister.core.cloud.sync

import android.util.Log
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinancialSyncScheduler @Inject constructor(
    private val syncEngine: FinancialSyncEngine,
    private val database: DmrDatabase,
    private val customerDirectorySyncClient: CustomerDirectorySyncClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun startAutoSync() {
        if (started) return
        started = true

        scope.launch {
            SessionManager.currentSession
                .map { session -> session?.let { it.membership?.id to it } }
                .distinctUntilChanged { old, new -> old?.first == new?.first }
                .collectLatest { keyedSession ->
                val session = keyedSession?.second
                if (session == null) return@collectLatest

                val membershipId = session.membership?.id
                if (membershipId.isNullOrBlank()) {
                    Log.e("PaidDueFinancialSync", "MEMBERSHIP_MISSING")
                    return@collectLatest
                }

                while (true) {
                    executeSync(
                        role = session.user.role,
                        businessId = session.business.id,
                        membershipId = membershipId
                    )
                    delay(15_000)
                }
            }
        }
    }

    fun triggerSync() {
        scope.launch {
            val session = SessionManager.currentSession.value
                ?: return@launch

            val membershipId = session.membership?.id
                ?: return@launch

            executeSync(
                role = session.user.role,
                businessId = session.business.id,
                membershipId = membershipId
            )
        }
    }

    /**
     * Pull server-authoritative permissions on each sync cycle. Permission changes no
     * longer revoke the transport token; this refresh keeps the local Room session and
     * reactive UI aligned with the membership row while the server re-checks every write.
     */
    private suspend fun refreshCashierPermissions(membershipId: String, businessId: String) {
        val active = SessionManager.currentSession.value ?: return
        if (active.user.role != UserRole.CASHIER || active.membership?.id != membershipId) return

        val metadata = CashierCloudSession.fetchMetadata(membershipId)
        if (metadata.optString("business_id") != businessId ||
            metadata.optString("branch_id") != active.branch.id ||
            metadata.optString("role") != "CASHIER") {
            CashierCloudSession.clear()
            throw SecurityException("CASHIER_METADATA_SCOPE_MISMATCH")
        }

        val permissions = metadata.optJSONObject("permissions")
            ?: throw IllegalStateException("CASHIER_METADATA_PERMISSIONS_MISSING")
        val local = database.userDao().getMembership(active.user.id, active.branch.id)
            ?: throw IllegalStateException("CASHIER_LOCAL_MEMBERSHIP_MISSING")

        val updated = local.copy(
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
        if (updated != local) {
            database.userDao().updateMembership(updated)
            SessionManager.setSession(active.copy(membership = updated))
            Log.i("PaidDueFinancialSync", "CASHIER_PERMISSIONS_REFRESHED membership=$membershipId")
        }
    }

    private suspend fun syncCustomerDirectoryBestEffort() {
        try {
            customerDirectorySyncClient.syncIfDue()
        } catch (error: Exception) {
            // Customer profile sync must never acknowledge, discard, or block financial outbox work.
            Log.w("PaidDueCustomerSync", "CUSTOMER_DIRECTORY_SYNC_DEFERRED", error)
        }
    }

    private suspend fun executeSync(
        role: UserRole,
        businessId: String,
        membershipId: String
    ) {
        try {
            when (role) {
                UserRole.ADMIN -> {
                    syncEngine.syncOutbound(membershipId, role)
                    syncEngine.syncInbound(
                        businessId,
                        SessionManager.currentSession.value?.user?.id ?: membershipId
                    )
                    syncCustomerDirectoryBestEffort()
                }

                UserRole.CASHIER -> {
                    if (CashierCloudSession.tokenFor(membershipId) == null) {
                        Log.w(
                            "PaidDueFinancialSync",
                            "CASHIER_CLOUD_SESSION_REQUIRED_PENDING_UPLOAD"
                        )
                        return
                    }

                    try {
                        refreshCashierPermissions(membershipId, businessId)
                    } catch (error: SecurityException) {
                        // The server rejected/revoked the custom session. Keep the durable
                        // outbox untouched and wait for explicit re-authentication.
                        Log.w("PaidDueFinancialSync", "CASHIER_METADATA_AUTH_REQUIRED")
                        return
                    } catch (error: Exception) {
                        // A temporary metadata outage must not block an otherwise valid upload;
                        // the ingest RPC independently checks current server-side permissions.
                        Log.w("PaidDueFinancialSync", "CASHIER_METADATA_REFRESH_DEFERRED", error)
                    }
                    if (CashierCloudSession.tokenFor(membershipId) == null) return
                    val uploaded = syncEngine.syncOutbound(membershipId, role)

                    val applied = syncEngine.syncInbound(
                        businessId,
                        SessionManager.currentSession.value?.user?.id ?: membershipId
                    )

                    Log.i(
                        "PaidDueFinancialSync",
                        "CASHIER_UPLOAD_ACKNOWLEDGED=$uploaded INBOUND_APPLIED=$applied"
                    )
                    syncCustomerDirectoryBestEffort()
                }

                else -> Unit
            }
        } catch (error: Exception) {
            Log.e(
                "PaidDueFinancialSync",
                "FINANCIAL_SYNC_EXCEPTION",
                error
            )
        }
    }
}
