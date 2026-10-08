package com.dailymoneyregister.feature.admin.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.UiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.dailymoneyregister.core.model.ids.UserId
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.feature.admin.data.CashierLifecycleRepository
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository.DirectoryEntry
import com.dailymoneyregister.feature.admin.data.CashierPermissionsConfig
import com.dailymoneyregister.feature.admin.data.CreateCashierParams
import com.dailymoneyregister.feature.admin.data.StaffTarget
import com.dailymoneyregister.feature.admin.data.UpdateCashierParams
import com.dailymoneyregister.feature.invite.data.CreatedInvite
import com.dailymoneyregister.feature.invite.data.InviteService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which staff dialog (other than "create") is open. */
sealed interface StaffDialogState {
    object None : StaffDialogState

    data class EditDetails(
        val userId: String,
        val name: String,
        val phone: String,
        val language: String
    ) : StaffDialogState

    data class EditPermissions(
        val userId: String,
        val name: String,
        val permissions: CashierPermissionsConfig
    ) : StaffDialogState

    data class ResetPin(
        val userId: String,
        val name: String,
        val pin: String = ""
    ) : StaffDialogState

    data class ConfirmDisable(val userId: String, val name: String) : StaffDialogState

    data class ConfirmEnable(val userId: String, val name: String) : StaffDialogState
}

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
    val dialog: StaffDialogState = StaffDialogState.None,
    val isExecuting: Boolean = false,
    val errorMessage: UiText? = null,
    val successMessage: UiText? = null
)

data class CashierInviteUiState(
    val isDialogOpen: Boolean = false,
    val displayName: String = "",
    val phone: PhoneNumberInputValue = PhoneNumberInputValue("OM"),
    val permissions: Map<String, Boolean> = invitePermissionDefaults(),
    val isExecuting: Boolean = false,
    val createdInvite: CreatedInvite? = null,
    val errorMessage: UiText? = null
)

private fun invitePermissionDefaults(): Map<String, Boolean> = mapOf(
    CashierPermissionKey.CREATE_CREDIT_SALE.name to false,
    CashierPermissionKey.REFUND.name to false,
    CashierPermissionKey.VOID.name to false
)

@HiltViewModel
class CashierManagementViewModel @Inject constructor(
    private val repository: CashierManagementRepository,
    private val lifecycleRepository: CashierLifecycleRepository,
    private val inviteService: InviteService
) : ViewModel() {

    private val businessId = SessionManager.activeBusiness?.id ?: ""
    private val branchId = SessionManager.activeBranch?.id ?: ""
    private val adminId = SessionManager.activeUser?.id ?: ""
    private val branchCountryCode = SessionManager.activeBranch?.countryCode ?: "OM"

    val cashiers: StateFlow<List<DirectoryEntry>> = repository.observeCashiers(businessId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(CashierManagementUiState())
    val uiState: StateFlow<CashierManagementUiState> = _uiState.asStateFlow()

    private val _inviteState = MutableStateFlow(
        CashierInviteUiState(phone = PhoneNumberInputValue(branchCountryCode))
    )
    val inviteState: StateFlow<CashierInviteUiState> = _inviteState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.refreshCloudDirectory().onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = UiText.Dynamic(error.message ?: "Cloud employee directory unavailable; showing offline staff")
                )
            }
        }
    }

    fun openInviteDialog() {
        _inviteState.value = CashierInviteUiState(
            isDialogOpen = true,
            phone = PhoneNumberInputValue(branchCountryCode)
        )
    }

    fun onInviteNameChanged(value: String) {
        _inviteState.value = _inviteState.value.copy(displayName = value, errorMessage = null)
    }

    fun onInvitePhoneChanged(value: PhoneNumberInputValue) {
        _inviteState.value = _inviteState.value.copy(phone = value, errorMessage = null)
    }

    fun onInvitePermissionChanged(key: CashierPermissionKey, value: Boolean) {
        if (key.name !in _inviteState.value.permissions) return
        _inviteState.value = _inviteState.value.copy(
            permissions = _inviteState.value.permissions + (key.name to value)
        )
    }

    fun createInvite() {
        val state = _inviteState.value
        if (state.displayName.isBlank() || state.phone.rawNumber.isBlank()) {
            _inviteState.value = state.copy(errorMessage = UiText.Resource(R.string.cashier_name_required))
            return
        }
        val canonicalPhone = runCatching {
            state.phone.normalizedNumber()
        }.getOrElse { error ->
            _inviteState.value = state.copy(
                errorMessage = UiText.Dynamic(error.message ?: "Invalid WhatsApp / Mobile Number")
            )
            return
        }

        _inviteState.value = state.copy(isExecuting = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val created = inviteService.createInvite(
                    businessId = businessId,
                    branchId = branchId,
                    displayName = state.displayName,
                    canonicalPhone = canonicalPhone,
                    permissions = state.permissions
                )
                _inviteState.value = _inviteState.value.copy(
                    isExecuting = false,
                    createdInvite = created
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _inviteState.value = _inviteState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(error.message ?: "Unable to create invite")
                )
            }
        }
    }

    fun clearInvite() {
        _inviteState.value = CashierInviteUiState(phone = PhoneNumberInputValue(branchCountryCode))
    }

    private fun target(cashierUserId: String) = StaffTarget(
        businessId = businessId,
        branchId = branchId,
        adminUserId = adminId,
        cashierUserId = cashierUserId
    )

    // ------------------------------------------------------------------
    // Create (existing behaviour). The permission switches are now reset every
    // time the dialog opens so one cashier's choices never carry to the next.
    // ------------------------------------------------------------------

    fun openCreateDialog() {
        _uiState.value = _uiState.value.copy(
            isCreateDialogOpen = true,
            dialog = StaffDialogState.None,
            newCashierName = "",
            newCashierUsername = "",
            newCashierPhone = "",
            newCashierPin = "",
            newCashierLanguage = "en",
            canCreateCreditSale = false,
            canUseCoupon = false,
            canRefund = false,
            canVoid = false,
            canCorrectAmount = false,
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

    // ------------------------------------------------------------------
    // Open / close the staff dialogs
    // ------------------------------------------------------------------

    fun dismissStaffDialog() {
        _uiState.value = _uiState.value.copy(
            dialog = StaffDialogState.None,
            errorMessage = null
        )
    }

    fun openEditDetails(cashier: DirectoryEntry) {
        val local = cashier.localAccount ?: return remoteEditUnavailable()
        viewModelScope.launch {
            val snapshot = lifecycleRepository.getEditSnapshot(businessId, branchId, local.id)
            _uiState.value = if (snapshot == null) {
                _uiState.value.copy(errorMessage = UiText.Resource(R.string.staff_cashier_unavailable))
            } else {
                _uiState.value.copy(
                    isCreateDialogOpen = false,
                    dialog = StaffDialogState.EditDetails(
                        userId = snapshot.userId,
                        name = snapshot.displayName,
                        phone = snapshot.phoneInput,
                        language = snapshot.languageCode
                    ),
                    errorMessage = null,
                    successMessage = null
                )
            }
        }
    }

    fun openEditPermissions(cashier: DirectoryEntry) {
        val local = cashier.localAccount ?: return remoteEditUnavailable()
        viewModelScope.launch {
            val snapshot = lifecycleRepository.getEditSnapshot(businessId, branchId, local.id)
            _uiState.value = if (snapshot == null) {
                _uiState.value.copy(errorMessage = UiText.Resource(R.string.staff_cashier_unavailable))
            } else {
                _uiState.value.copy(
                    isCreateDialogOpen = false,
                    dialog = StaffDialogState.EditPermissions(
                        userId = snapshot.userId,
                        name = snapshot.displayName,
                        permissions = snapshot.permissions
                    ),
                    errorMessage = null,
                    successMessage = null
                )
            }
        }
    }

    fun openResetPin(cashier: DirectoryEntry) {
        if (cashier.localAccount == null) return remoteEditUnavailable()
        _uiState.value = _uiState.value.copy(
            isCreateDialogOpen = false,
            dialog = StaffDialogState.ResetPin(cashier.id, cashier.displayName),
            errorMessage = null,
            successMessage = null
        )
    }

    fun openDisableConfirm(cashier: DirectoryEntry) {
        if (cashier.localAccount == null) return remoteEditUnavailable()
        _uiState.value = _uiState.value.copy(
            isCreateDialogOpen = false,
            dialog = StaffDialogState.ConfirmDisable(cashier.id, cashier.displayName),
            errorMessage = null,
            successMessage = null
        )
    }

    fun openEnableConfirm(cashier: DirectoryEntry) {
        if (cashier.localAccount == null) return remoteEditUnavailable()
        _uiState.value = _uiState.value.copy(
            isCreateDialogOpen = false,
            dialog = StaffDialogState.ConfirmEnable(cashier.id, cashier.displayName),
            errorMessage = null,
            successMessage = null
        )
    }

    private fun remoteEditUnavailable() {
        _uiState.value = _uiState.value.copy(
            errorMessage = UiText.Dynamic("Cloud cashier changes require an authorized server operation")
        )
    }

    // ------------------------------------------------------------------
    // Field edits inside the dialogs
    // ------------------------------------------------------------------

    fun onEditNameChanged(value: String) = updateDialog<StaffDialogState.EditDetails> { it.copy(name = value) }

    fun onEditPhoneChanged(value: String) = updateDialog<StaffDialogState.EditDetails> { it.copy(phone = value.trim()) }

    fun onEditLanguageChanged(value: String) = updateDialog<StaffDialogState.EditDetails> { it.copy(language = value) }

    fun onEditPermissionChanged(key: CashierPermissionKey, value: Boolean) =
        updateDialog<StaffDialogState.EditPermissions> {
            it.copy(permissions = it.permissions.withPermission(key, value))
        }

    fun onResetPinChanged(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() }) {
            updateDialog<StaffDialogState.ResetPin> { it.copy(pin = value) }
        }
    }

    private inline fun <reified T : StaffDialogState> updateDialog(transform: (T) -> T) {
        val current = _uiState.value.dialog
        if (current is T) {
            _uiState.value = _uiState.value.copy(dialog = transform(current))
        }
    }

    // ------------------------------------------------------------------
    // Execute the five lifecycle actions
    // ------------------------------------------------------------------

    fun saveEditDetails() {
        val d = _uiState.value.dialog as? StaffDialogState.EditDetails ?: return
        if (d.name.isBlank() || d.phone.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = UiText.Resource(R.string.cashier_name_required))
            return
        }
        runLifecycle(UiText.Resource(R.string.staff_cashier_updated, listOf(d.name.trim()))) {
            lifecycleRepository.updateCashier(
                UpdateCashierParams(
                    target = target(d.userId),
                    displayName = d.name,
                    phone = d.phone,
                    preferredLanguage = d.language
                )
            )
        }
    }

    fun saveEditPermissions() {
        val d = _uiState.value.dialog as? StaffDialogState.EditPermissions ?: return
        runLifecycle(UiText.Resource(R.string.staff_permissions_updated, listOf(d.name))) {
            lifecycleRepository.updateCashierPermissions(target(d.userId), d.permissions)
        }
    }

    fun saveResetPin() {
        val d = _uiState.value.dialog as? StaffDialogState.ResetPin ?: return
        if (!PinHasher.isValidPin(d.pin)) {
            _uiState.value = _uiState.value.copy(errorMessage = UiText.Resource(R.string.cashier_pin_invalid))
            return
        }
        runLifecycle(UiText.Resource(R.string.staff_pin_reset_done, listOf(d.name))) {
            lifecycleRepository.resetCashierPin(target(d.userId), d.pin)
        }
    }

    fun confirmDisable() {
        val d = _uiState.value.dialog as? StaffDialogState.ConfirmDisable ?: return
        runLifecycle(UiText.Resource(R.string.staff_cashier_disabled, listOf(d.name))) {
            lifecycleRepository.disableCashier(target(d.userId))
        }
    }

    fun confirmEnable() {
        val d = _uiState.value.dialog as? StaffDialogState.ConfirmEnable ?: return
        runLifecycle(UiText.Resource(R.string.staff_cashier_enabled, listOf(d.name))) {
            lifecycleRepository.enableCashier(target(d.userId))
        }
    }

    /**
     * Runs one lifecycle command. A fresh Operation ID is generated per tap and the
     * isExecuting guard blocks double taps while a command is in flight.
     */
    private fun runLifecycle(
        successMessage: UiText,
        block: suspend () -> CommandResult<UserId>
    ) {
        if (_uiState.value.isExecuting) return
        _uiState.value = _uiState.value.copy(isExecuting = true, errorMessage = null)

        viewModelScope.launch {
            val result: CommandResult<UserId> = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CommandResult.Error(error)
            }

            _uiState.value = when (result) {
                is CommandResult.Success -> _uiState.value.copy(
                    isExecuting = false,
                    dialog = StaffDialogState.None,
                    errorMessage = null,
                    successMessage = successMessage
                )
                is CommandResult.Conflict -> _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.PermissionDenied -> _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.Error -> _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = result.exception.message?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.cashier_creation_failed)
                )
                else -> _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Resource(R.string.cashier_creation_failed)
                )
            }
        }
    }
}
