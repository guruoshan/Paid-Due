package com.dailymoneyregister.feature.admin.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.UiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository
import com.dailymoneyregister.feature.admin.data.CashierPermissionsConfig
import com.dailymoneyregister.feature.admin.data.CreateCashierParams
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CashierManagementUiState(
    val isCreateDialogOpen: Boolean = false,
    val newCashierName: String = "",
    val newCashierUsername: String = "",
    val newCashierPhone: String = "",
    val newCashierPin: String = "",
    val newCashierLanguage: String = "en",
    val canCreateCreditSale: Boolean = false,
    val canUseCoupon: Boolean = false,
    val canRefund: Boolean = false,
    val canVoid: Boolean = false,
    val canCorrectAmount: Boolean = false,
    val isExecuting: Boolean = false,
    val errorMessage: UiText? = null,
    val successMessage: UiText? = null
)

@HiltViewModel
class CashierManagementViewModel @Inject constructor(
    private val repository: CashierManagementRepository
) : ViewModel() {

    private val businessId = SessionManager.activeBusiness?.id ?: ""
    private val branchId = SessionManager.activeBranch?.id ?: ""
    private val adminId = SessionManager.activeUser?.id ?: ""

    val cashiers: StateFlow<List<UserAccountEntity>> = repository.observeCashiers(businessId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(CashierManagementUiState())
    val uiState: StateFlow<CashierManagementUiState> = _uiState.asStateFlow()

    fun openCreateDialog() {
        _uiState.value = _uiState.value.copy(
            isCreateDialogOpen = true,
            newCashierName = "",
            newCashierUsername = "",
            newCashierPhone = "",
            newCashierPin = "",
            newCashierLanguage = "en",
            errorMessage = null,
            successMessage = null
        )
    }

    fun closeCreateDialog() {
        _uiState.value = _uiState.value.copy(isCreateDialogOpen = false)
    }

    fun onNameChanged(name: String) { _uiState.value = _uiState.value.copy(newCashierName = name) }
    fun onUsernameChanged(username: String) { _uiState.value = _uiState.value.copy(newCashierUsername = username.trim()) }
    fun onPhoneChanged(phone: String) { _uiState.value = _uiState.value.copy(newCashierPhone = phone.trim(), newCashierUsername = phone.trim()) }
    fun onPinChanged(pin: String) {
        if (pin.length <= 6 && pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(newCashierPin = pin)
        }
    }
    fun onLanguageChanged(lang: String) { _uiState.value = _uiState.value.copy(newCashierLanguage = lang) }

    fun toggleCreditSalePermission(value: Boolean) { _uiState.value = _uiState.value.copy(canCreateCreditSale = value) }
    fun toggleCouponPermission(value: Boolean) { _uiState.value = _uiState.value.copy(canUseCoupon = value) }
    fun toggleRefundPermission(value: Boolean) { _uiState.value = _uiState.value.copy(canRefund = value) }
    fun toggleVoidPermission(value: Boolean) { _uiState.value = _uiState.value.copy(canVoid = value) }
    fun toggleCorrectAmountPermission(value: Boolean) { _uiState.value = _uiState.value.copy(canCorrectAmount = value) }

    fun executeCreateCashier() {
        val s = _uiState.value
        if (s.newCashierName.isBlank() || s.newCashierPhone.isBlank()) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.cashier_name_required))
            return
        }
        if (!PinHasher.isValidPin(s.newCashierPin)) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.cashier_pin_invalid))
            return
        }

        val params = CreateCashierParams(
            businessId = businessId,
            branchId = branchId,
            adminUserId = adminId,
            displayName = s.newCashierName,
            username = s.newCashierUsername,
            phone = s.newCashierPhone,
            pin = s.newCashierPin,
            preferredLanguage = s.newCashierLanguage,
            permissions = CashierPermissionsConfig(
                canCreateSale = true,
                canAcceptCash = true,
                canAcceptOnline = true,
                canCreateCreditSale = s.canCreateCreditSale,
                canUseCoupon = s.canUseCoupon,
                canIssueInvoice = true,
                canIssueReceipt = true,
                canRefund = s.canRefund,
                canVoid = s.canVoid,
                canCorrectAmount = s.canCorrectAmount
            )
        )

        _uiState.value = s.copy(isExecuting = true, errorMessage = null)

        viewModelScope.launch {
            when (val result = repository.createCashier(params)) {
                is CommandResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        isCreateDialogOpen = false,
                        successMessage = UiText.Resource(R.string.cashier_created, listOf(params.displayName))
                    )
                }
                is CommandResult.Conflict -> {
                    _uiState.value = _uiState.value.copy(isExecuting = false, errorMessage = UiText.Dynamic(result.message))
                }
                is CommandResult.PermissionDenied -> {
                    _uiState.value = _uiState.value.copy(isExecuting = false, errorMessage = UiText.Dynamic(result.message))
                }
                is CommandResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        errorMessage = result.exception.message?.let { UiText.Dynamic(it) } ?: UiText.Resource(R.string.cashier_creation_failed)
                    )
                }
            }
        }
    }
}
