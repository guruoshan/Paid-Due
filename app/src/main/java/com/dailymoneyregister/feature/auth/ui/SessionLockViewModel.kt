package com.dailymoneyregister.feature.auth.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.cloud.sync.CashierCloudSession
import com.dailymoneyregister.core.cloud.sync.RemoteFinancialSyncRepository
import com.dailymoneyregister.core.cloud.sync.SessionAuthorizationResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.PasswordHasher
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SessionLockViewModel @Inject constructor(
    private val database: DmrDatabase,
    private val trustedSessionStore: TrustedSessionStore,
    private val remoteFinancialSyncRepository: RemoteFinancialSyncRepository,
    @ApplicationContext context: Context
) : ViewModel() {
    private val unlockPrefs = context.getSharedPreferences("session_unlock_policy", Context.MODE_PRIVATE)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private data class UnlockValidation(
        val result: SessionAuthorizationResult,
        val session: com.dailymoneyregister.core.security.ActiveSession
    )

    fun unlock(secret: String) = unlockInternal(secret, biometricAuthorized = false)

    fun unlockWithBiometric() = unlockInternal(secret = null, biometricAuthorized = true)

    private fun unlockInternal(secret: String?, biometricAuthorized: Boolean) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                val session = SessionManager.currentSession.value
                if (session == null) {
                    trustedSessionStore.clear()
                    SessionManager.clearSession()
                    fail("No active session. Sign in again.")
                    return@launch
                }

                // Local credential verification is a hard gate. Any local database
                // or hashing failure fails closed; it must never trigger offline fallback.
                val user = try {
                    database.userDao().getUserById(session.user.id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail("Unable to verify the local account. Retry or sign in again.")
                    return@launch
                } ?: run {
                    trustedSessionStore.clear()
                    SessionManager.clearSession()
                    fail("Local account is unavailable. Sign in again.")
                    return@launch
                }

                val valid = if (biometricAuthorized) {
                    true
                } else {
                    try {
                        withContext(Dispatchers.Default) {
                            val suppliedSecret = secret ?: ""
                            val salt = user.credentialSalt.orEmpty()
                            when (user.role) {
                                UserRole.ADMIN -> PasswordHasher.verifyPassword(suppliedSecret, salt, user.credentialHash)
                                UserRole.CASHIER -> PinHasher.verifyPin(suppliedSecret, salt, user.credentialHash)
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
                val failureKey = "unlock:${session.business.id}:${session.user.id}"
                if (!valid) {
                    val failures = unlockPrefs.getInt(failureKey, 0) + 1
                    unlockPrefs.edit().putInt(failureKey, failures).commit()
                    if (failures >= 5) {
                        // Five consecutive failed unlocks require full sign-in again.
                        trustedSessionStore.clear()
                        SessionManager.clearSession()
                        unlockPrefs.edit().remove(failureKey).commit()
                        fail("Five failed unlock attempts. Sign in again.")
                    } else {
                        fail("Incorrect password or PIN. ${5 - failures} attempt(s) remain.")
                    }
                    return@launch
                }
                unlockPrefs.edit().remove(failureKey).commit()

                // Only server-validation failures may enter the bounded offline path.
                val validation = try {
                    when (user.role) {
                        UserRole.ADMIN -> UnlockValidation(
                            remoteFinancialSyncRepository.validateAdminSession(
                                expectedMembershipId = session.membership?.id.orEmpty(),
                                expectedBusinessId = session.business.id,
                                expectedBranchId = session.branch.id,
                                expectedLocalUserId = session.user.id
                            ),
                            session
                        )
                        UserRole.CASHIER -> validateCashier(session)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    UnlockValidation(SessionAuthorizationResult.UNAVAILABLE, session)
                }

                when (validation.result) {
                    SessionAuthorizationResult.AUTHORIZED -> {
                        if (!biometricAuthorized) {
                            runCatching { com.dailymoneyregister.core.security.SessionBiometricKey.recoverAfterCredentialUnlock() }
                        }
                        trustedSessionStore.markServerVerified()
                        SessionManager.setSession(validation.session.copy(isOfflineLease = false))
                    }
                    SessionAuthorizationResult.REJECTED -> {
                        trustedSessionStore.clear()
                        SessionManager.clearSession()
                        fail("The server rejected this session. Sign in again.")
                    }
                    SessionAuthorizationResult.UNAVAILABLE -> {
                        if (!trustedSessionStore.isOfflineLeaseValid()) {
                            // Do not leave the user trapped behind a lock screen that can
                            // never be satisfied by an expired offline lease.
                            trustedSessionStore.clear()
                            SessionManager.clearSession()
                            fail("Offline access has expired. Sign in again when the service is available.")
                        } else {
                            if (!biometricAuthorized) {
                                runCatching { com.dailymoneyregister.core.security.SessionBiometricKey.recoverAfterCredentialUnlock() }
                            }
                            SessionManager.setSession(validation.session.copy(isOfflineLease = true))
                        }
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun recordBiometricFailure() {
        val session = SessionManager.currentSession.value ?: return
        val key = "unlock:${session.business.id}:${session.user.id}"
        val failures = unlockPrefs.getInt(key, 0) + 1
        unlockPrefs.edit().putInt(key, failures).commit()
        if (failures >= 5) {
            trustedSessionStore.clear()
            SessionManager.clearSession()
            unlockPrefs.edit().remove(key).commit()
            fail("Five failed unlock attempts. Sign in again.")
        } else {
            fail("Biometric not recognized. ${5 - failures} attempt(s) remain.")
        }
    }

    private suspend fun validateCashier(
        session: com.dailymoneyregister.core.security.ActiveSession
    ): UnlockValidation {
        val membership = session.membership ?: return UnlockValidation(SessionAuthorizationResult.REJECTED, session)
        // An absent/expired device token is not evidence that the server revoked
        // the membership. A valid identity-bound offline lease may still unlock.
        if (CashierCloudSession.tokenFor(membership.id) == null) {
            return UnlockValidation(SessionAuthorizationResult.UNAVAILABLE, session)
        }
        return try {
            val metadata = CashierCloudSession.fetchMetadata(membership.id)
            if (
                metadata.optString("membership_id") != membership.id ||
                metadata.optString("business_id") != session.business.id ||
                metadata.optString("branch_id") != session.branch.id ||
                metadata.optString("role") != "CASHIER"
            ) return UnlockValidation(SessionAuthorizationResult.REJECTED, session)

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
            database.userDao().updateMembership(refreshed)
            UnlockValidation(SessionAuthorizationResult.AUTHORIZED, session.copy(membership = refreshed))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            UnlockValidation(SessionAuthorizationResult.REJECTED, session)
        } catch (_: Exception) {
            UnlockValidation(SessionAuthorizationResult.UNAVAILABLE, session)
        }
    }

    private fun fail(message: String) {
        _error.value = message
    }
}
