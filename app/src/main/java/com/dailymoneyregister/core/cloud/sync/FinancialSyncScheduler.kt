package com.dailymoneyregister.core.cloud.sync

import android.util.Log
import com.dailymoneyregister.core.model.enums.UserRole
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
                if (session == null || session.user.role != UserRole.ADMIN) {
                    Log.i("PaidDueFinancialSync", "SYNC_SKIPPED_NON_OWNER_SESSION")
                    return@collectLatest
                }

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
            if (session.user.role != UserRole.ADMIN) {
                Log.i("PaidDueFinancialSync", "SYNC_TRIGGER_SKIPPED_NON_OWNER_SESSION")
                return@launch
            }

            val membershipId = session.membership?.id
                ?: return@launch

            executeSync(
                role = session.user.role,
                businessId = session.business.id,
                membershipId = membershipId
            )
        }
    }

    private suspend fun syncCustomerDirectoryBestEffort() {
        try {
            customerDirectorySyncClient.syncIfDue()
        } catch (error: Exception) {
            // Directory sync is best-effort: never blocks or acknowledges financial outbox work.
            Log.w("PaidDueCustomerSync", "CUSTOMER_DIRECTORY_SYNC_DEFERRED", error)
        }
    }

    private suspend fun executeSync(
        role: UserRole,
        businessId: String,
        membershipId: String
    ) {
        if (role != UserRole.ADMIN ||
            SessionManager.currentSession.value?.user?.role != UserRole.ADMIN
        ) {
            Log.i("PaidDueFinancialSync", "SYNC_EXECUTION_SKIPPED_NON_OWNER_SESSION")
            return
        }
        try {
            when (role) {
                UserRole.ADMIN -> {
                    syncCustomerDirectoryBestEffort()
                    syncEngine.syncOutbound(membershipId, role)
                    syncEngine.syncInbound(
                        businessId,
                        SessionManager.currentSession.value?.user?.id ?: membershipId
                    )
                }

                UserRole.CASHIER -> {
                    syncCustomerDirectoryBestEffort()
                    if (CashierCloudSession.tokenFor(membershipId) == null) {
                        Log.w(
                            "PaidDueFinancialSync",
                            "CASHIER_CLOUD_SESSION_REQUIRED_PENDING_UPLOAD"
                        )
                        return
                    }

                    val uploaded = syncEngine.syncOutbound(membershipId, role)

                    val applied = syncEngine.syncInbound(
                        businessId,
                        SessionManager.currentSession.value?.user?.id ?: membershipId
                    )

                    Log.i(
                        "PaidDueFinancialSync",
                        "CASHIER_UPLOAD_ACKNOWLEDGED=$uploaded INBOUND_APPLIED=$applied"
                    )
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
