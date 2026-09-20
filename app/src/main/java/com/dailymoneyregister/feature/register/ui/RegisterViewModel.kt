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
import kotlinx.coroutines.flow.SharingStarted
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
class RegisterViewModel @Inject constructor(
    private val registerRepository: RegisterRepository,
    private val deviceIdentityProvider: DeviceIdentityProvider
) : ViewModel() {

    val activeBusiness: BusinessEntity? get() = SessionManager.activeBusiness
    val activeBranch: BranchEntity? get() = SessionManager.activeBranch
    val activeUser: UserAccountEntity? get() = SessionManager.activeUser
    val activeMembership: UserBranchMembershipEntity? get() = SessionManager.activeMembership

    private val decimalPlaces: Int
        get() = activeBranch?.currencyDecimalPlaces ?: 3

    val currencySymbol: String
        get() = activeBranch?.currencySymbol ?: (activeBranch?.currencyCode ?: "OMR")

    val currencyCode: String
        get() = activeBranch?.currencyCode ?: "OMR"

    private var calculatorEngine = CalculatorEngine(decimalPlaces)

    private val _uiState = MutableStateFlow(
        RegisterUiState(
            calculatorState = CalculatorState(),
            draftState = DraftState(
                taxRateBps = activeBranch?.defaultTaxRateBps ?: 0,
                isTaxEnabled = false, // Tax must NOT be auto-applied unless explicitly enabled
                taxRoundingMode = activeBranch?.taxRoundingMode ?: com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP
            )
        )
    )
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    val recentTransactions: StateFlow<List<FinancialTransactionView>> =
        registerRepository.observeTodayTransactions(
            activeBranch?.id ?: "",
            activeBranch?.timezoneId ?: "Asia/Muscat"
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        updateCalculatorEngine()
    }

    private fun updateCalculatorEngine() {
        calculatorEngine = CalculatorEngine(decimalPlaces)
    }

    // Keypad Handler
    fun onKeypadPress(key: KeypadKey) {
        val nextCalcState = calculatorEngine.processKey(_uiState.value.calculatorState, key)
        val nextDraftState = _uiState.value.draftState.copy(
            manualAmount = nextCalcState.evaluatedAmount
        )
        _uiState.value = _uiState.value.copy(
            calculatorState = nextCalcState,
            draftState = nextDraftState,
            errorMessage = null,
            infoMessage = null
        )
    }

    fun onManualDescriptionChanged(desc: String) {
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
        val branch = activeBranch
        _uiState.value = RegisterUiState(
            calculatorState = CalculatorState(),
            draftState = clearedRegisterDraft(branch),
            isRecentTransactionsExpanded = _uiState.value.isRecentTransactionsExpanded
        )
    }

    // Tax Configuration
    fun setTaxEnabled(enabled: Boolean) {
        val updatedDraft = _uiState.value.draftState.copy(isTaxEnabled = enabled)
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    fun updateTaxRatePercent(percentStr: String) {
        val bps = TaxRateParser.parseToBpsOrNull(percentStr) ?: return
        val updatedDraft = _uiState.value.draftState.copy(
            taxRateBps = bps,
            isTaxEnabled = bps > 0
        )
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    // Shipping & Packing Configuration
    fun updateTxShippingPacking(shippingText: String, packingText: String) {
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
        val currentItems = _uiState.value.draftState.items.filterNot { it.id == itemId }
        val updatedDraft = _uiState.value.draftState.copy(items = currentItems)
        _uiState.value = _uiState.value.copy(draftState = updatedDraft)
    }

    fun updateItemQuantity(itemId: String, newQty: Int) {
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
        val s = _uiState.value
        val branch = activeBranch
        val user = activeUser
        val membership = activeMembership

        if (branch == null || user == null) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.sale_session_missing))
            return
        }

        if (!s.draftState.grandTotal.isPositive) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.sale_total_zero))
            return
        }

        // Reuse existing inFlightSaleCommand if retrying, or freeze new intent on confirmation
        val command = s.inFlightSaleCommand ?: run {
            val now = System.currentTimeMillis()
            CashSaleCommand(
                operationId = OperationId.generate(),
                businessId = branch.businessId,
                branchId = branch.id,
                actorUserId = user.id,
                transactionIdVal = TransactionId.generate(),
                draftState = s.draftState,
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = deviceIdentityProvider.deviceId,
                occurredAt = now
            )
        }

        _uiState.value = s.copy(isExecuting = true, inFlightSaleCommand = command, errorMessage = null)

        viewModelScope.launch {
            when (val result = registerRepository.executeCashSale(command, user, membership)) {
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
        val branch = activeBranch
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
