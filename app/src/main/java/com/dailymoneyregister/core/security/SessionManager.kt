package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveSession(
    val business: BusinessEntity,
    val branch: BranchEntity,
    val user: UserAccountEntity,
    val membership: UserBranchMembershipEntity?,
    val requiresPinChange: Boolean = false,
    val isOfflineLease: Boolean = false
)

fun ActiveSession.canUseFinancialRegister(): Boolean = !requiresPinChange

object SessionManager {
    private val _currentSession = MutableStateFlow<ActiveSession?>(null)
    val currentSession: StateFlow<ActiveSession?> = _currentSession.asStateFlow()
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun setSession(session: ActiveSession) {
        _currentSession.value = session
        _isLocked.value = false
    }

    fun lockSession() {
        if (_currentSession.value != null) _isLocked.value = true
    }

    fun unlockSession() {
        _isLocked.value = false
    }

    fun clearSession() {
        _currentSession.value = null
        _isLocked.value = false
    }

    val activeUser: UserAccountEntity? get() = _currentSession.value?.user
    val activeBranch: BranchEntity? get() = _currentSession.value?.branch
    val activeBusiness: BusinessEntity? get() = _currentSession.value?.business
    val activeMembership: UserBranchMembershipEntity? get() = _currentSession.value?.membership
}
