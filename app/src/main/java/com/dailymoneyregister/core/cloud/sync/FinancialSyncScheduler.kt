package com.dailymoneyregister.core.cloud.sync

import android.util.Log
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinancialSyncScheduler @Inject constructor(
    private val syncEngine: FinancialSyncEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun startAutoSync() {
        if (started) return
        started = true

        scope.launch {
            SessionManager.currentSession.collectLatest { session ->
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

    private suspend fun executeSync(
        role: UserRole,
        businessId: String,
        membershipId: String
    ) {
        try {
            when (role) {
                UserRole.ADMIN -> {
                    syncEngine.syncOutbound(membershipId)
                    syncEngine.syncInbound(businessId)
                }

                UserRole.CASHIER -> {
                    if (CashierCloudSession.tokenFor(membershipId) == null) {
                        Log.w(
                            "PaidDueFinancialSync",
                            "CASHIER_CLOUD_SESSION_REQUIRED_PENDING_UPLOAD"
                        )
                        return
                    }

                    val uploaded = syncEngine.syncOutbound(membershipId)

                    Log.i(
                        "PaidDueFinancialSync",
                        "CASHIER_UPLOAD_ACKNOWLEDGED=$uploaded"
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
