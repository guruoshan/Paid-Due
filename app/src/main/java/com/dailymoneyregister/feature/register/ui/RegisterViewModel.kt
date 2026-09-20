package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.UiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.currency.CurrencyRegistry
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.CalculatorEngine
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.register.domain.KeypadKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.dailymoneyregister.core.model.tax.TaxRateParser

data class RegisterUiState(
    val calculatorState: CalculatorState = CalculatorState(),
    val draftState: DraftState = DraftState(),
    val inFlightSaleCommand: CashSaleCommand? = null,
    val manualDescription: String = "",
    val isItemizedBillDialogOpen: Boolean = false,
    val isTaxDialogOpen: Boolean = false,
    val isShippingPackingDialogOpen: Boolean = false,
    val isAddItemDialogOpen: Boolean = false,
    val isRecentTransactionsExpanded: Boolean = false,
    val lastCompletedTransaction: TransactionEntity? = null,
    val isExecuting: Boolean = false,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null
)

internal fun clearedRegisterDraft(branch: BranchEntity?): DraftState = DraftState(
    taxRateBps = branch?.defaultTaxRateBps ?: 0,
    isTaxEnabled = false,
    taxRoundingMode = branch?.taxRoundingMode
        ?: com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP
)

@HiltViewModel
class RegisterViewModel internal constructor(
    private val observeToday: (String, String) -> Flow<List<FinancialTransactionView>>,
    private val executeSale: suspend (CashSaleCommand, UserAccountEntity, UserBranchMembershipEntity?) -> CommandResult<TransactionEntity>,
    private val deviceId: () -> String
) : ViewModel() {

    @Inject
    constructor(
        registerRepository: RegisterRepository,
        deviceIdentityProvider: DeviceIdentityProvider
    ) : this(
        registerRepository::observeTodayTransactions,
        registerRepository::executeCashSale,
        { deviceIdentityProvider.deviceId }
    )

    private val initialSession = SessionManager.currentSession.value
    private var financialContext = initialSession.toRegisterFinancialSessionContext()
    internal var financialSessionGeneration: Long = 0
        private set

    val activeBusiness: BusinessEntity? get() = SessionManager.activeBusiness
    val activeBranch: BranchEntity? get() = SessionManager.activeBranch
    val activeUser: UserAccountEntity? get() = SessionManager.activeUser
    val activeMembership: UserBranchMembershipEntity? get() = SessionManager.activeMembership

    private val decimalPlaces: Int
        get() = financialContext?.currencyDecimalPlaces ?: 3

    val currencySymbol: String
        get() = activeBranch?.currencySymbol ?: (activeBranch?.currencyCode ?: "OMR")

    val currencyCode: String
        get() = activeBranch?.currencyCode ?: "OMR"

    private var calculatorEngine = CalculatorEngine(decimalPlaces)

    private val _uiState = MutableStateFlow(
        resetRegisterUiStateForSession(RegisterUiState(), initialSession)
    )
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    val recentTransactions: StateFlow<List<FinancialTransactionView>> =
        observeRecentForSession(SessionManager.currentSession) { branchId, timezoneId ->
            observeToday(
                branchId,
                timezoneId
            )
        }.stateIn(
            viewModelScope,
            registerRecentSharingStarted,
            emptyList()
        )

    init {
        viewModelScope.launch {
            SessionManager.currentSession.collect { sessionSnapshot ->
                synchronizeFinancialSession(sessionSnapshot)
            }
        }
    }

    // Called on the UI thread by both the session observer and financial actions.
    // Generation belongs only to this live Register, never to a durable command.
    private fun synchronizeFinancialSession(sessionSnapshot: ActiveSession?): Boolean {
        val nextContext = sessionSnapshot.toRegisterFinancialSessionContext()
        if (nextContext == financialContext) return false
        financialContext = nextContext
        financialSessionGeneration++
        calculatorEngine = CalculatorEngine(decimalPlaces)
        _uiState.value = resetRegisterUiStateForSession(_uiState.value, sessionSnapshot).copy(
            infoMessage = if (sessionSnapshot == null) null
                else UiText.Resource(R.string.register_financial_session_reset)
        )
        return true
    }

    // Keypad Handler
    fun onKeypadPress(key: KeypadKey) {
        val sessionChanged = synchronizeFinancialSession(SessionManager.currentSession.value)
        val nextCalcState = calculatorEngine.processKey(_uiState.value.calculatorState, key)
        val nextDraftState = _uiState.value.draftState.copy(
            manualAmount = nextCalcState.evaluatedAmount
        )
        _uiState.value = _uiState.value.copy(
            calculatorState = nextCalcState,
            draftState = nextDraftState,
            errorMessage = null,
            infoMessage = if (sessionChanged) _uiState.value.infoMessage else null
        )
    }

    fun onManualDescriptionChanged(desc: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        _uiState.value = _uiState.value.copy(
            manualDescription = desc,
            draftState = _uiState.value.draftState.copy(manualDescription = desc.ifBlank { null })
        )
    }

    // Dialog Toggles
    fun toggleTaxDialog(isOpen: Boolean) { _uiState.value = _uiState.value.copy(isTaxDialogOpen = isOpen) }
    fun toggleShippingPackingDialog(isOpen: Boolean) { _uiState.value = _uiState.value.copy(isShippingPackingDialogOpen = isOpen) }
    fun toggleItemizedBillDialog(isOpen: Boolean) { _uiState.value = _uiState.value.copy(isItemizedBillDialogOpen = isOpen) }
    fun toggleAddItemDialog(isOpen: Boolean) { _uiState.value = _uiState.value.copy(isAddItemDialogOpen = isOpen) }
    fun toggleRecentTransactionsExpanded() {
        _uiState.value = _uiState.value.copy(isRecentTransactionsExpanded = !_uiState.value.isRecentTransactionsExpanded)
    }
    fun clearInfoMessage() { _uiState.value = _uiState.value.copy(infoMessage = null) }
    fun clearErrorMessage() { _uiState.value = _uiState.value.copy(errorMessage = null) }

    /** Resets only the unsaved register draft; no repository or DAO is touched. */
    fun clearDraft() {
        val sessionSnapshot = SessionManager.currentSession.value
        if (synchronizeFinancialSession(sessionSnapshot)) return
        val branch = sessionSnapshot?.branch
        _uiState.value = RegisterUiState(
            calculatorState = CalculatorState(),
            draftState = clearedRegisterDraft(branch),
            isRecentTransactionsExpanded = _uiState.value.isRecentTransactionsExpanded
        )
    }

    // Tax Configuration
    fun setTaxEnabled(enabled: Boolean) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        val updatedDraft = _uiState.value.draftState.copy(isTaxEnabled = enabled)
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    fun updateTaxRatePercent(percentStr: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        val bps = TaxRateParser.parseToBpsOrNull(percentStr) ?: return
        val updatedDraft = _uiState.value.draftState.copy(
            taxRateBps = bps,
            isTaxEnabled = bps > 0
        )
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    // Shipping & Packing Configuration
    fun updateTxShippingPacking(shippingText: String, packingText: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        val shipping = MoneyMinorUnits.parse(shippingText, decimalPlaces).getOrElse { return }
        val packing = MoneyMinorUnits.parse(packingText, decimalPlaces).getOrElse { return }
        if (shipping.isNegative || packing.isNegative) return

        val updatedDraft = _uiState.value.draftState.copy(
            txShipping = shipping,
            txPacking = packing
        )
        _uiState.value = _uiState.value.copy(
            draftState = updatedDraft,
            isShippingPackingDialogOpen = false
        )
    }

    // Itemized Bill Operations
    fun addItem(
        name: String,
        priceText: String,
        quantity: Int,
        description: String? = null,
        packingText: String = "0",
        shippingText: String = "0"
    ) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        if (name.isBlank() || quantity <= 0) return
        val unitPrice = MoneyMinorUnits.parse(priceText, decimalPlaces).getOrElse { return }
        val packing = MoneyMinorUnits.parse(packingText, decimalPlaces).getOrElse { return }
        val shipping = MoneyMinorUnits.parse(shippingText, decimalPlaces).getOrElse { return }
        if (unitPrice.isNegative || packing.isNegative || shipping.isNegative) return

        val newItem = DraftItem(
            id = IdGenerator.newId(),
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            unitPrice = unitPrice,
            quantity = quantity,
            itemPackingCharge = packing,
            itemShippingCharge = shipping
        )

        val currentItems = _uiState.value.draftState.items.toMutableList()
        currentItems.add(newItem)

        val updatedDraft = _uiState.value.draftState.copy(items = currentItems)
        _uiState.value = _uiState.value.copy(
            draftState = updatedDraft,
            isAddItemDialogOpen = false
        )
    }

    fun removeItem(itemId: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        val currentItems = _uiState.value.draftState.items.filterNot { it.id == itemId }
        val updatedDraft = _uiState.value.draftState.copy(items = currentItems)
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    fun updateItemQuantity(itemId: String, newQty: Int) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        if (newQty <= 0) {
            removeItem(itemId)
            return
        }
        val currentItems = _uiState.value.draftState.items.map { item ->
            if (item.id == itemId) item.copy(quantity = newQty) else item
        }
        val updatedDraft = _uiState.value.draftState.copy(items = currentItems)
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    // Payment Trigger: CASH
    fun executeCashSale() {
        val sessionSnapshot = SessionManager.currentSession.value
        val sessionChanged = synchronizeFinancialSession(sessionSnapshot)
        val s = _uiState.value

        if (sessionSnapshot == null) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.sale_session_missing))
            return
        }
        // This Cash press must never execute money entered under the old context.
        if (sessionChanged) return
        val branch = sessionSnapshot.branch
        val user = sessionSnapshot.user
        val membership = sessionSnapshot.membership
        val executionContext = financialContext
        val executionGeneration = financialSessionGeneration

        if (!s.draftState.grandTotal.isPositive) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.sale_total_zero))
            return
        }

        // Reuse existing inFlightSaleCommand if retrying, or freeze new intent on confirmation
        val command = s.inFlightSaleCommand ?: run {
            val now = System.currentTimeMillis()
            CashSaleCommand(
                operationId = OperationId.generate(),
                businessId = sessionSnapshot.business.id,
                branchId = branch.id,
                actorUserId = user.id,
                transactionIdVal = TransactionId.generate(),
                draftState = s.draftState,
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = deviceId(),
                occurredAt = now
            )
        }

        _uiState.value = s.copy(isExecuting = true, inFlightSaleCommand = command, errorMessage = null)

        viewModelScope.launch {
            val result = executeSale(command, user, membership)
            if (!shouldApplyFinancialResultToRegister(
                    executionContext,
                    SessionManager.currentSession.value,
                    executionGeneration,
                    financialSessionGeneration
                )) return@launch
            when (result) {
                is CommandResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        inFlightSaleCommand = null,
                        lastCompletedTransaction = result.data
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
                        errorMessage = result.exception.message?.let { UiText.Dynamic(it) } ?: UiText.Resource(R.string.cash_sale_failed)
                    )
                }
            }
        }
    }

    // Upcoming Phase Payment Trigger: Online, Credit, Coupon
    fun triggerDisabledPaymentMethod(methodName: String) {
        _uiState.value = _uiState.value.copy(
            infoMessage = UiText.Resource(R.string.upcoming_payment_msg, listOf(methodName))
        )
    }

    // Success Panel Action: Reset for New Sale
    fun startNewSale() {
        val sessionSnapshot = SessionManager.currentSession.value
        if (synchronizeFinancialSession(sessionSnapshot)) return
        val branch = sessionSnapshot?.branch
        _uiState.value = RegisterUiState(
            calculatorState = CalculatorState(),
            draftState = DraftState(
                taxRateBps = branch?.defaultTaxRateBps ?: 0,
                isTaxEnabled = false, // Tax must NOT be auto-applied on reset
                taxRoundingMode = branch?.taxRoundingMode ?: com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP
            ),
            inFlightSaleCommand = null,
            lastCompletedTransaction = null
        )
    }
}
