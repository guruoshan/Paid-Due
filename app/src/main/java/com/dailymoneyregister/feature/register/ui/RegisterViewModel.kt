package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.financial.RecentFinancialActivity
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
import com.dailymoneyregister.feature.register.data.OnlineSaleCommand
import com.dailymoneyregister.feature.register.data.CouponSaleCommand
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.ExpenseCommandFactory
import com.dailymoneyregister.feature.register.data.ExpenseCommandResult
import com.dailymoneyregister.feature.register.data.CouponSaleResult
import com.dailymoneyregister.feature.register.data.CouponSaleCustomerWallet
import com.dailymoneyregister.feature.register.data.CouponTopUpCommand
import com.dailymoneyregister.feature.register.data.CouponTopUpResult
import com.dailymoneyregister.feature.register.data.CouponAuthorizedUserCommand
import com.dailymoneyregister.feature.register.data.CouponRedemptionCommand
import com.dailymoneyregister.feature.register.data.CouponRedemptionResult
import com.dailymoneyregister.feature.register.data.RegisterRepository.CouponWalletDetails
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import com.dailymoneyregister.feature.register.data.ExpenseSupportingDocument
import com.dailymoneyregister.feature.register.data.ExpenseSupportingDocumentRepository
import com.dailymoneyregister.feature.register.data.AmountCorrectionCommand
import com.dailymoneyregister.feature.register.data.AmountCorrectionExecutionResult
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeCommand
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeExecutionResult
import com.dailymoneyregister.feature.register.data.RefundCommand
import com.dailymoneyregister.feature.register.data.RefundExecutionResult
import com.dailymoneyregister.feature.register.data.VoidCommand
import com.dailymoneyregister.feature.register.data.VoidExecutionResult
import com.dailymoneyregister.feature.settings.data.MerchantQrRepository
import com.dailymoneyregister.feature.register.data.ProductRepository
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
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

import com.dailymoneyregister.feature.register.data.RegisterShiftReport
import com.dailymoneyregister.feature.register.data.RegisterShiftCloseReportSnapshot
import com.dailymoneyregister.feature.register.data.RegisterShiftResult
import com.dailymoneyregister.feature.register.data.CountRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.CloseRegisterShiftCommand

data class RefundToCouponDraft(
    val transactionId: String,
    val amountInput: String,
    val amountMinor: Long,
    val reason: String?
)

data class RegisterUiState(
    val destination: RegisterDestination = RegisterDestination.Register,
    val overlay: RegisterOverlay? = null,
    val calculatorState: CalculatorState = CalculatorState(),
    val draftState: DraftState = DraftState(),
    val inFlightSaleCommand: CashSaleCommand? = null,
    val inFlightOnlineSaleCommand: OnlineSaleCommand? = null,
    val saleCustomerName: String = "",
    val saleCustomerPhone: String = "",
    val saleCustomerLookupLoading: Boolean = false,
    val saleCustomerLookupResult: CustomerEntity? = null,
    val saleCustomerLookupCompletedPhone: String? = null,
    val inFlightCouponSaleCommand: CouponSaleCommand? = null,
    val inFlightCouponTopUpCommand: CouponTopUpCommand? = null,
    val inFlightCreditSaleCommand: CreditSaleCommand? = null,
    val creditSaleShareName: String? = null,
    val creditSaleSharePhone: String? = null,
    val creditInvoicePurchaseOccurredAt: Long? = null,
    val creditInvoiceGivenMinor: Long? = null,
    val creditInvoicePurchaseOutstandingMinor: Long? = null,
    val creditInvoiceTotalOutstandingMinor: Long? = null,
    val creditInvoiceCurrencyCode: String? = null,
    val creditInvoiceDecimalPlaces: Int? = null,
    val couponCustomers: List<CouponSaleCustomerWallet> = emptyList(),
    val couponSearching: Boolean = false,
    val couponSaleResult: CouponSaleResult? = null,
    val couponTopUpResult: CouponTopUpResult? = null,
    val couponWalletLoading: Boolean = false,
    val couponWalletDetails: CouponWalletDetails? = null,
    val inFlightCouponUserCommand: CouponAuthorizedUserCommand? = null,
    val inFlightCouponRedemptionCommand: CouponRedemptionCommand? = null,
    val couponRedemptionResult: CouponRedemptionResult? = null,
    val refundToCouponDraft: RefundToCouponDraft? = null,
    val inFlightRefundToCouponCommand: RefundCommand? = null,
    val refundToCouponResult: RefundExecutionResult? = null,
    val manualDescription: String = "",
    /** Non-null while Add Item is open in EDIT mode for this existing item id. */
    val editingItemId: String? = null,
    val isRecentTransactionsExpanded: Boolean = false,
    val transactionDetailsLoading: Boolean = false,
    val transactionDetails: TransactionDetailsReadModel? = null,
    val expenseDetailsLoading: Boolean = false,
    val expenseDetails: ExpenseReadProjection? = null,
    val expenseSupportingDocuments: List<ExpenseSupportingDocument> = emptyList(),
    val expenseSupportingDocumentsLoading: Boolean = false,
    val stagedExpenseSupportingDocumentUris: List<String> = emptyList(),
    val expenseSupportingDocumentAttaching: Boolean = false,
    val lastCompletedTransaction: TransactionEntity? = null,
    val isExecuting: Boolean = false,
    val onlineQr: MerchantQrSelection? = null,
    val expenseLoading: Boolean = false,
    val expensePayeeLookupLoading: Boolean = false,
    val expensePayeeLookupPhone: String? = null,
    val expensePayeeLookupResult: CustomerEntity? = null,    val todayExpenses: List<com.dailymoneyregister.core.database.entity.ExpenseEntity> = emptyList(),
    val inFlightExpenseCommand: ExpenseCommand? = null,
    val dayClosingLoading: Boolean = false,
    val dayClosingReport: RegisterShiftReport? = null,
    val dayClosingActualCash: String = "",
    val dayClosingReview: Boolean = false,
    val dayClosingResult: DayClosingResultUi? = null,
    val inFlightShiftCountCommand: CountRegisterShiftCommand? = null,
    val inFlightShiftCloseCommand: CloseRegisterShiftCommand? = null,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null
)

@HiltViewModel
class RegisterViewModel internal constructor(
    private val saleDependencies: RegisterSaleDependencies,
    private val couponDependencies: RegisterCouponDependencies,
    private val transactionDependencies: RegisterTransactionLifecycleDependencies,
    private val expenseDependencies: RegisterExpenseDependencies,
    private val shiftDependencies: RegisterShiftDependencies
) : ViewModel() {

    @Inject
    constructor(
        registerRepository: RegisterRepository,
        deviceIdentityProvider: DeviceIdentityProvider,
        merchantQrRepository: MerchantQrRepository,
        productRepository: ProductRepository,
        expenseSupportingDocumentRepository: ExpenseSupportingDocumentRepository
    ) : this(
        saleDependencies = RegisterSaleDependencies(
            observeToday = registerRepository::observeTodayTransactions,
            executeSale = registerRepository::executeCashSale,
            deviceId = { deviceIdentityProvider.deviceId },
            findSaleCustomerByPhone = registerRepository::findSaleCustomerByPhone,
            executeOnlineSale = registerRepository::executeOnlineSale,
            loadOnlineQr = { branchId -> merchantQrRepository.getActive(branchId) },
            submitCreditSale = registerRepository::executeCreditSale,
            loadCreditInvoice = registerRepository::getCreditInvoiceDetails,
            rememberProduct = { branchId, name, description, unitPriceMinor -> productRepository.remember(branchId, name, description, unitPriceMinor) },
            searchProducts = { branchId, query -> productRepository.search(branchId, query) }
        ),
        couponDependencies = RegisterCouponDependencies(
            submitCouponSale = registerRepository::executeCouponSale,
            findCouponCustomers = registerRepository::searchCouponSaleCustomers,
            loadCouponWalletDetails = registerRepository::getCouponWalletDetails,
            submitCouponTopUp = registerRepository::executeCouponTopUp,
            submitCouponUser = registerRepository::executeCouponAuthorizedUser,
            submitCouponRedemption = registerRepository::executeCouponRedemption
        ),
        transactionDependencies = RegisterTransactionLifecycleDependencies(
            loadTransactionDetails = registerRepository::getTransactionDetails,
            loadSaleItems = registerRepository::getTransactionItems,
            submitAmountCorrection = registerRepository::executeAmountCorrection,
            submitRefund = registerRepository::executeRefund,
            submitVoid = registerRepository::executeVoid,
            submitPaymentMethodChange = registerRepository::executePaymentMethodChange
        ),
        expenseDependencies = RegisterExpenseDependencies(
            submitExpense = registerRepository::executeExpense,
            loadExpensesForPeriod = registerRepository::getExpensesForPeriod,
            loadExpenseDetails = registerRepository::getExpenseDetails,
            loadExpenseSupportingDocuments = expenseSupportingDocumentRepository::getForExpense,
            attachExpenseSupportingDocument = { expenseId, sourceUri ->
                expenseSupportingDocumentRepository.attach(expenseId = expenseId, sourceUri = sourceUri)
                Unit
                },
            submitExpenseLifecycle = registerRepository::executeExpenseLifecycle
        ),
        shiftDependencies = RegisterShiftDependencies(
            loadCurrentRegisterShiftReport = registerRepository::getCurrentRegisterShiftReport,
            submitRegisterShiftCount = registerRepository::countRegisterShift,
            submitRegisterShiftClose = registerRepository::closeRegisterShift
        )
    )

    // BOARDLOCK Bundle D: thin wiring aliases only.
    private val observeToday get() = saleDependencies.observeToday
    private val executeSale get() = saleDependencies.executeSale
    private val deviceId get() = saleDependencies.deviceId
    private val findSaleCustomerByPhone get() = saleDependencies.findSaleCustomerByPhone
    private val executeOnlineSale get() = saleDependencies.executeOnlineSale
    private val loadOnlineQr get() = saleDependencies.loadOnlineQr
    private val submitCreditSale get() = saleDependencies.submitCreditSale
    private val loadCreditInvoice get() = saleDependencies.loadCreditInvoice
    private val submitCouponSale get() = couponDependencies.submitCouponSale
    private val findCouponCustomers get() = couponDependencies.findCouponCustomers
    private val loadCouponWalletDetails get() = couponDependencies.loadCouponWalletDetails
    private val submitCouponTopUp get() = couponDependencies.submitCouponTopUp
    private val submitCouponUser get() = couponDependencies.submitCouponUser
    private val submitCouponRedemption get() = couponDependencies.submitCouponRedemption
    private val loadTransactionDetails get() = transactionDependencies.loadTransactionDetails
    private val loadSaleItems get() = transactionDependencies.loadSaleItems
    private val submitAmountCorrection get() = transactionDependencies.submitAmountCorrection
    private val submitRefund get() = transactionDependencies.submitRefund
    private val submitVoid get() = transactionDependencies.submitVoid
    private val submitPaymentMethodChange get() = transactionDependencies.submitPaymentMethodChange
    private val submitExpense get() = expenseDependencies.submitExpense
    private val loadExpensesForPeriod get() = expenseDependencies.loadExpensesForPeriod
    private val loadExpenseDetails get() = expenseDependencies.loadExpenseDetails
    private val loadExpenseSupportingDocuments get() = expenseDependencies.loadExpenseSupportingDocuments
    private val attachExpenseSupportingDocument get() = expenseDependencies.attachExpenseSupportingDocument
    private val loadCurrentRegisterShiftReport get() = shiftDependencies.loadCurrentRegisterShiftReport
    private val submitRegisterShiftCount get() = shiftDependencies.submitRegisterShiftCount
    private val submitRegisterShiftClose get() = shiftDependencies.submitRegisterShiftClose
    private val rememberProductByBranch get() = saleDependencies.rememberProduct
    private val searchProductsByBranch get() = saleDependencies.searchProducts

    private val initialSession = SessionManager.currentSession.value
    private var financialContext = initialSession.toRegisterFinancialSessionContext()
    internal var financialSessionGeneration: Long = 0
        private set

    val activeBusiness: BusinessEntity? get() = SessionManager.activeBusiness
    val activeBranch: BranchEntity? get() = SessionManager.activeBranch
    val activeUser: UserAccountEntity? get() = SessionManager.activeUser
    val activeMembership: UserBranchMembershipEntity? get() = SessionManager.activeMembership

    /** Observable session source for all UI-changing branch, currency, and permission values. */
    val activeSession: StateFlow<ActiveSession?> = SessionManager.currentSession

    val canRefundFromDetails: Boolean
        get() = isPermitted(PermissionAction.REFUND)

    val canVoidFromDetails: Boolean
        get() = isPermitted(PermissionAction.VOID)

    val canChangePaymentMethodFromDetails: Boolean
        get() = isPermitted(PermissionAction.CHANGE_PAYMENT_METHOD)

    val canCorrectAmountFromDetails: Boolean
        get() = isPermitted(PermissionAction.CORRECT_AMOUNT)

    private fun isPermitted(action: PermissionAction): Boolean =
        activeUser?.let { PermissionChecker.isPermitted(it, activeMembership, action) } == true

    private val decimalPlaces: Int
        get() = financialContext?.currencyDecimalPlaces ?: 3

    val currencySymbol: String
        get() = activeBranch?.currencySymbol ?: (activeBranch?.currencyCode ?: "OMR")

    val currencyCode: String
        get() = activeBranch?.currencyCode ?: "OMR"

    val merchantCountryCode: String
        get() = activeBranch?.countryCode ?: "OM"

    private var calculatorEngine = CalculatorEngine(decimalPlaces)

    private val _uiState = MutableStateFlow(
        resetRegisterUiStateForSession(RegisterUiState(), initialSession)
    )
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    private val draftCompositionCoordinator = RegisterDraftCompositionCoordinator(
        uiState = _uiState
    )

    private val customerSelectionCoordinator = RegisterCustomerSelectionCoordinator(
        uiState = _uiState,
        scope = viewModelScope,
        currentSession = { SessionManager.currentSession.value },
        findSaleCustomerByPhone = findSaleCustomerByPhone,
        findCouponCustomers = findCouponCustomers,
        synchronizeFinancialSession = ::synchronizeFinancialSession,
        financialContext = { financialContext },
        financialSessionGeneration = { financialSessionGeneration },
        isFinancialSessionCurrent = { context, session, generation, currentGeneration ->
            shouldApplyFinancialResultToRegister(context, session, generation, currentGeneration)
        }
    )

    private val expenseSupportingDocumentCoordinator =
        RegisterExpenseSupportingDocumentCoordinator(
            uiState = _uiState,
            scope = viewModelScope,
            loadSupportingDocuments = loadExpenseSupportingDocuments,
            attachSupportingDocument = attachExpenseSupportingDocument
        )

    private val detailsSessionCoordinator = RegisterDetailsSessionCoordinator(
        uiState = _uiState,
        scope = viewModelScope,
        loadTransactionDetails = loadTransactionDetails,
        isFinancialSessionCurrent = { context, generation ->
            shouldApplyFinancialResultToRegister(
                context,
                SessionManager.currentSession.value,
                generation,
                financialSessionGeneration
            )
        }
    )

    private val expenseDetailsCoordinator = RegisterExpenseDetailsCoordinator(
        uiState = _uiState,
        scope = viewModelScope,
        loadExpenseDetails = loadExpenseDetails,
        currentSession = { SessionManager.currentSession.value },
        decimalPlaces = { decimalPlaces },
        deviceId = deviceId,
        submitExpenseLifecycle = expenseDependencies.submitExpenseLifecycle,
        isFinancialSessionCurrent = { context, generation ->
            shouldApplyFinancialResultToRegister(
                context,
                SessionManager.currentSession.value,
                generation,
                financialSessionGeneration
            )
        }
    )

    private val transactionDetailsActionCoordinator = RegisterTransactionDetailsActionCoordinator(
        uiState = _uiState,
        scope = viewModelScope,
        currentSession = { SessionManager.currentSession.value },
        decimalPlaces = { decimalPlaces },
        deviceId = deviceId,
        financialContext = { financialContext },
        financialSessionGeneration = { financialSessionGeneration },
        synchronizeFinancialSession = ::synchronizeFinancialSession,
        isFinancialSessionCurrent = { context, session, generation, currentGeneration ->
            shouldApplyFinancialResultToRegister(context, session, generation, currentGeneration)
        },
        loadTransactionDetails = loadTransactionDetails,
        loadCouponWalletDetails = loadCouponWalletDetails,
        currencyCode = { currencyCode },
        submitAmountCorrection = submitAmountCorrection,
        submitRefund = submitRefund,
        submitVoid = submitVoid,
        submitPaymentMethodChange = submitPaymentMethodChange
    )
    val recentTransactions: StateFlow<List<RecentFinancialActivity>> =
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
        detailsSessionCoordinator.invalidateForFinancialSessionChange()
        expenseDetailsCoordinator.invalidateForFinancialSessionChange()
        calculatorEngine = CalculatorEngine(decimalPlaces)
        _uiState.value = resetRegisterUiStateForSession(_uiState.value, sessionSnapshot).copy(
            transactionDetailsLoading = false,
            transactionDetails = null,
            expenseDetailsLoading = false,
            expenseDetails = null,
            infoMessage = if (sessionSnapshot == null) null
                else UiText.Resource(R.string.register_financial_session_reset)
        )
        return true
    }

    // Keypad Handler
    fun onKeypadPress(key: KeypadKey) {
        val sessionChanged = synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.onKeypadPress(key, calculatorEngine::processKey, sessionChanged)
    }

    fun onManualDescriptionChanged(desc: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.onManualDescriptionChanged(desc)
    }

    fun openHistory() {
        detailsSessionCoordinator.openHistory()
    }

    fun closeHistory() {
        if (_uiState.value.destination != RegisterDestination.History) return
        _uiState.value = _uiState.value.copy(destination = RegisterDestination.Register)
    }

    // Register-owned modal workflows share one presentation authority.
    fun toggleTaxDialog(isOpen: Boolean) {
        if (isOpen) setOverlay(RegisterOverlay.Tax)
        else if (_uiState.value.overlay == RegisterOverlay.Tax) setOverlay(null)
    }
    fun toggleShippingPackingDialog(isOpen: Boolean) {
        val current = _uiState.value.overlay
        if (isOpen) {
            setOverlay(RegisterOverlay.ShippingPacking(current == RegisterOverlay.ItemizedBill))
        } else if (current is RegisterOverlay.ShippingPacking) {
            setOverlay(current.afterChildClosed())
        }
    }
    fun toggleItemizedBillDialog(isOpen: Boolean) {
        if (isOpen) setOverlay(RegisterOverlay.ItemizedBill)
        else if (_uiState.value.overlay == RegisterOverlay.ItemizedBill) setOverlay(null)
    }
    fun toggleAddItemDialog(isOpen: Boolean) {
        val current = _uiState.value.overlay
        if (isOpen) {
            setOverlay(RegisterOverlay.AddItem(current == RegisterOverlay.ItemizedBill))
        } else if (current is RegisterOverlay.AddItem) {
            draftCompositionCoordinator.closeAddItemDialog(current)
        }
    }

    fun openCashCustomer() = setOverlay(RegisterOverlay.CashCustomer)

    fun closeCashCustomer() {
        if (_uiState.value.overlay == RegisterOverlay.CashCustomer) setOverlay(null)
    }

    private fun setOverlay(overlay: RegisterOverlay?) {
        _uiState.value = _uiState.value.copy(overlay = overlay)
    }
    fun toggleRecentTransactionsExpanded() {
        _uiState.value = _uiState.value.copy(isRecentTransactionsExpanded = !_uiState.value.isRecentTransactionsExpanded)
    }
    fun clearInfoMessage() { _uiState.value = _uiState.value.copy(infoMessage = null) }
    /**
     * Loads one authoritative persisted Expense projection.
     *
     * This is a read-only UI action. It does not create, modify, void,
     * correct, or otherwise mutate financial evidence.
     */
    fun openExpenseDetails(expenseId: String) {
        if (expenseId.isBlank()) return
        val session = SessionManager.currentSession.value
        if (
            synchronizeFinancialSession(session) ||
            session == null ||
            _uiState.value.expenseDetailsLoading
        ) return
        expenseDetailsCoordinator.openExpenseDetails(
            expenseId = expenseId,
            financialContext = financialContext,
            financialGeneration = financialSessionGeneration
        )
    }

    /**
     * Closes only the read-only Expense Details projection.
     * Financial state is untouched.
     */
    fun refreshExpenseSupportingDocuments(expenseId: String) =
        expenseSupportingDocumentCoordinator.refreshExpenseSupportingDocuments(expenseId)

    fun attachSupportingDocumentToExpense(expenseId: String, sourceUri: String) =
        expenseSupportingDocumentCoordinator.attachSupportingDocumentToExpense(expenseId, sourceUri)

    fun correctExpenseFromDetails(correctedAmountInput: String, reason: String) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        expenseDetailsCoordinator.correctExpense(correctedAmountInput, reason)
    }

    fun voidExpenseFromDetails(reason: String) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        expenseDetailsCoordinator.voidExpense(reason)
    }

    fun closeExpenseDetails() {
        expenseDetailsCoordinator.closeExpenseDetails()
    }

    /** Loads the saved transaction, items, and funding evidence for sharing. */
    fun loadSaleDetailsForWhatsApp(
        transactionId: String,
        onLoaded: (TransactionDetailsReadModel?) -> Unit
    ) = detailsSessionCoordinator.loadSaleDetailsForWhatsApp(transactionId, onLoaded)

    fun openTransactionDetails(transactionId: String) {
        if (transactionId.isBlank()) return
        val session = SessionManager.currentSession.value
        if (
            synchronizeFinancialSession(session) ||
            session == null ||
            _uiState.value.transactionDetailsLoading
        ) return
        detailsSessionCoordinator.openTransactionDetails(
            transactionId = transactionId,
            financialContext = financialContext,
            financialGeneration = financialSessionGeneration
        )
    }

    /**
     * Closes only the read-only Transaction Details projection.
     * No transaction or financial record is changed.
     */

    fun executeAmountCorrectionFromDetails(correctedAmountInput: String, reason: String?) = transactionDetailsActionCoordinator.executeAmountCorrectionFromDetails(correctedAmountInput, reason)
    fun beginRefundToCouponFromDetails(amountInput: String, reason: String?) = transactionDetailsActionCoordinator.beginRefundToCouponFromDetails(amountInput, reason)
    fun confirmRefundToCoupon(customer: CustomerEntity?, name: String, phone: String, additionalUsers: List<String>) = transactionDetailsActionCoordinator.confirmRefundToCoupon(customer, name, phone, additionalUsers)
    fun executeRefundFromDetails(amountInput: String, destination: PaymentCategory, reason: String?) = transactionDetailsActionCoordinator.executeRefundFromDetails(amountInput, destination, reason)
    fun executeVoidFromDetails(reason: String?) = transactionDetailsActionCoordinator.executeVoidFromDetails(reason)
    fun executePaymentMethodChangeFromDetails(destination: PaymentCategory, reason: String?) = transactionDetailsActionCoordinator.executePaymentMethodChangeFromDetails(destination, reason)
    fun closeTransactionDetails() {
        detailsSessionCoordinator.closeTransactionDetails()
    }

    /** Resets only the unsaved register draft; no repository or DAO is touched. */
    fun clearDraft() {
        val sessionSnapshot = SessionManager.currentSession.value
        if (synchronizeFinancialSession(sessionSnapshot)) return
        draftCompositionCoordinator.clearDraft(sessionSnapshot?.branch)
    }

    // Tax and shipping configuration remain guarded by RegisterViewModel's session owner.
    fun setTaxEnabled(enabled: Boolean) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.setTaxEnabled(enabled)
    }

    fun updateTaxRatePercent(percentStr: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.updateTaxRatePercent(percentStr)
    }

    fun updateTxShippingPacking(shippingText: String, packingText: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.updateTxShippingPacking(shippingText, packingText, decimalPlaces)
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
        draftCompositionCoordinator.addItem(
            name, priceText, quantity, description, packingText, shippingText,
            decimalPlaces, ::rememberProduct
        )
    }

    fun editItem(
        itemId: String,
        name: String,
        priceText: String,
        quantity: Int,
        description: String? = null
    ) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.editItem(
            itemId, name, priceText, quantity, description, decimalPlaces, ::rememberProduct
        )
    }

    fun startEditingItem(itemId: String) = draftCompositionCoordinator.startEditingItem(itemId)

    fun cancelEditingItem() = draftCompositionCoordinator.cancelEditingItem()

    private fun rememberProduct(name: String, description: String?, unitPriceMinor: Long) {
        val branchId = SessionManager.activeBranch?.id ?: return
        viewModelScope.launch {
            rememberProductByBranch(branchId, name, description, unitPriceMinor)
        }
    }

    /** Prefix search over previously-used item names, for the Add Item autocomplete. */
    suspend fun searchProducts(query: String): List<ProductEntity> {
        val branchId = SessionManager.activeBranch?.id ?: return emptyList()
        return searchProductsByBranch(branchId, query)
    }

    fun removeItem(itemId: String) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.removeItem(itemId)
    }

    fun updateItemQuantity(itemId: String, newQty: Int) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        draftCompositionCoordinator.updateItemQuantity(itemId, newQty)
    }

    fun setSaleCustomerName(value: String) =
        customerSelectionCoordinator.setSaleCustomerName(value)

    fun setSaleCustomerPhone(value: String) =
        customerSelectionCoordinator.setSaleCustomerPhone(value)

    fun lookupSaleCustomerByPhone(canonicalPhone: String) =
        customerSelectionCoordinator.lookupSaleCustomerByPhone(canonicalPhone)

    fun clearSaleCustomer() = customerSelectionCoordinator.clearSaleCustomer()

    // Payment Trigger: CASH
    fun executeCashSale() {
        if (_uiState.value.overlay is RegisterOverlay.Coupon || _uiState.value.isExecuting) return
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
                occurredAt = now,
                customerName = s.saleCustomerName.trim().ifBlank { null },
                customerPhone = s.saleCustomerPhone.trim().ifBlank { null }
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

    fun openOnlinePayment() {
        if (_uiState.value.overlay is RegisterOverlay.Coupon || _uiState.value.isExecuting) return
        val session = SessionManager.currentSession.value
        val s = _uiState.value
        if (session == null) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.sale_session_missing))
            return
        }
        if (!s.draftState.grandTotal.isPositive) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.sale_total_zero))
            return
        }
        _uiState.value = s.copy(
            overlay = RegisterOverlay.OnlinePayment,
            onlineQr = null,
            errorMessage = null
        )
        viewModelScope.launch {
            val qr = loadOnlineQr(session.branch.id)
            if (_uiState.value.overlay != RegisterOverlay.OnlinePayment) return@launch
            _uiState.value = _uiState.value.copy(
                onlineQr = qr,
                errorMessage = null
            )
        }
    }

    fun cancelOnlinePayment() {
        if (_uiState.value.isExecuting) return
        if (_uiState.value.overlay != RegisterOverlay.OnlinePayment) return
        _uiState.value = _uiState.value.copy(overlay = null, onlineQr = null)
    }

    fun executeOnlineSale() {
        val sessionSnapshot = SessionManager.currentSession.value
        val sessionChanged = synchronizeFinancialSession(sessionSnapshot)
        val s = _uiState.value
        if (sessionSnapshot == null || sessionChanged || s.isExecuting) return
        if (!s.draftState.grandTotal.isPositive) return

        val executionContext = financialContext
        val executionGeneration = financialSessionGeneration
        val command = s.inFlightOnlineSaleCommand ?: OnlineSaleCommand(
            operationId = OperationId.generate(),
            businessId = sessionSnapshot.business.id,
            branchId = sessionSnapshot.branch.id,
            actorUserId = sessionSnapshot.user.id,
            transactionIdVal = TransactionId.generate(),
            draftState = s.draftState,
            currencyCode = sessionSnapshot.branch.currencyCode,
            currencyDecimalPlaces = sessionSnapshot.branch.currencyDecimalPlaces,
            deviceId = deviceId(),
            occurredAt = System.currentTimeMillis(),
            customerName = s.saleCustomerName.trim().ifBlank { null },
            customerPhone = s.saleCustomerPhone.trim().ifBlank { null }
        )
        _uiState.value = s.copy(
            isExecuting = true,
            inFlightOnlineSaleCommand = command,
            errorMessage = null
        )
        viewModelScope.launch {
            val result = executeOnlineSale(command)
            if (!shouldApplyFinancialResultToRegister(
                    executionContext,
                    SessionManager.currentSession.value,
                    executionGeneration,
                    financialSessionGeneration
                )) return@launch
            when (result) {
                is CommandResult.Success -> _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    overlay = null,
                    onlineQr = null,
                    inFlightOnlineSaleCommand = null,
                    lastCompletedTransaction = result.data
                )
                is CommandResult.Conflict -> _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.PermissionDenied -> _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.Error -> _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Online sale failed.")
                )
            }
        }
    }

    fun openCouponSale() = openCoupon(CouponDialogStartMode.ENTRY)

    fun openCouponRedemption() = openCoupon(CouponDialogStartMode.REDEMPTION)

    private fun openCoupon(startMode: CouponDialogStartMode) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null || _uiState.value.isExecuting) return
        if (_uiState.value.lastCompletedTransaction != null) return
        _uiState.value = _uiState.value.copy(
            overlay = RegisterOverlay.Coupon(startMode),
            errorMessage = null,
            onlineQr = null
        )
        val generation = financialSessionGeneration
        viewModelScope.launch {
            try {
                val qr = loadOnlineQr(session.branch.id)
                if (generation == financialSessionGeneration && _uiState.value.overlay is RegisterOverlay.Coupon &&
                    session.toRegisterFinancialSessionContext() == SessionManager.currentSession.value.toRegisterFinancialSessionContext()) {
                    _uiState.value = _uiState.value.copy(onlineQr = qr)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Cash remains available; an online purchase requires the configured QR.
            }
        }
    }

    fun closeCouponSale() {
        val s = _uiState.value

        if (
            s.overlay !is RegisterOverlay.Coupon ||
            s.isExecuting ||
            s.inFlightCouponSaleCommand != null ||
            s.inFlightCouponTopUpCommand != null ||
            s.inFlightCouponRedemptionCommand != null
        ) return

        _uiState.value =
            s.copy(
                overlay = null,
                couponCustomers = emptyList(),
                couponSaleResult = null,
                couponTopUpResult = null,
                couponRedemptionResult = null,
                refundToCouponDraft = null,
                refundToCouponResult = null,
                inFlightRefundToCouponCommand = null,
                couponWalletDetails = null,
                couponWalletLoading = false,
                onlineQr = null,
                errorMessage = null
            )
    }

    fun openCouponWallet(customerId: String, showDialog: Boolean = true) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null || _uiState.value.couponWalletLoading) return
        _uiState.value = _uiState.value.copy(
            overlay = if (showDialog) RegisterOverlay.CouponWallet else _uiState.value.overlay,
            couponWalletDetails = null,
            couponWalletLoading = true
        )
        val context = financialContext
        val generation = financialSessionGeneration
        viewModelScope.launch {
            try {
                val details = loadCouponWalletDetails(customerId, session.branch.currencyCode)
                if (shouldApplyFinancialResultToRegister(context, SessionManager.currentSession.value,
                        generation, financialSessionGeneration)) {
                    _uiState.value = _uiState.value.copy(couponWalletDetails = details, couponWalletLoading = false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation == financialSessionGeneration) _uiState.value = _uiState.value.copy(
                    couponWalletLoading = false, errorMessage = UiText.Dynamic(e.message ?: "Could not load coupon wallet"))
            }
        }
    }

    fun closeCouponWallet() {
        if (_uiState.value.isExecuting || _uiState.value.inFlightCouponUserCommand != null) return
        _uiState.value = _uiState.value.copy(
            overlay = if (_uiState.value.overlay == RegisterOverlay.CouponWallet) null else _uiState.value.overlay,
            couponWalletDetails = null,
            couponWalletLoading = false
        )
    }

    fun saveCouponUser(userId: String?, name: String, deactivate: Boolean = false) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null || _uiState.value.isExecuting) return
        val s = _uiState.value
        val details = s.couponWalletDetails ?: return
        val command = s.inFlightCouponUserCommand ?: CouponAuthorizedUserCommand(
            OperationId.generate(), session.business.id, session.branch.id, session.user.id,
            details.wallet.customerId, details.wallet.id, userId ?: IdGenerator.newId(), name.trim(),
            when {
                userId == null -> com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.ADDED
                deactivate -> com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.DEACTIVATED
                else -> com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.NAME_CORRECTED
            }, currencyCode, deviceId(), System.currentTimeMillis())
        val context = financialContext
        val generation = financialSessionGeneration
        _uiState.value = s.copy(isExecuting = true, inFlightCouponUserCommand = command, errorMessage = null)
        viewModelScope.launch {
            val result = try { submitCouponUser(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!shouldApplyFinancialResultToRegister(context, SessionManager.currentSession.value,
                    generation, financialSessionGeneration)) return@launch
            when (result) {
                is CommandResult.Success -> {
                    _uiState.value = _uiState.value.copy(isExecuting = false, inFlightCouponUserCommand = null)
                    openCouponWallet(details.wallet.customerId, s.overlay == RegisterOverlay.CouponWallet)
                }
                is CommandResult.PermissionDenied -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponUserCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponUserCommand = if (result.exception is IllegalArgumentException) null else command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Authorization failed"))
            }
        }
    }

    fun openCreditSale() {

        val state = _uiState.value


        if (state.isExecuting) {

            return

        }


        if (state.draftState.grandTotal.amount <= 0L) {

            _uiState.value = state.copy(

                errorMessage = UiText.Dynamic(

                    "Enter a sale amount before selecting Credit."

                )

            )

            return

        }


        _uiState.value = state.copy(

            overlay = RegisterOverlay.CreditSale,

            errorMessage = null

        )

    }


    fun closeCreditSale() {

        val state = _uiState.value


        if (state.overlay != RegisterOverlay.CreditSale || state.isExecuting) {

            return

        }


        _uiState.value = state.copy(

            overlay = null,

            errorMessage = null

        )

    }

    fun searchCouponCustomers(query: String) =
        customerSelectionCoordinator.searchCouponCustomers(query)

    fun confirmCouponRedemption(customer: CustomerEntity?, userId: String?, amount: String, remainder: PaymentCategory?) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        val s = _uiState.value
        if (s.isExecuting || s.overlay !is RegisterOverlay.Coupon || s.couponRedemptionResult != null) return
        val command = try {
            s.inFlightCouponRedemptionCommand ?: run {
                val details = requireNotNull(s.couponWalletDetails)
                require(customer?.id == details.wallet.customerId)
                val user = details.authorizedUsers.single { it.user.id == userId && it.isActive }
                val value = MoneyMinorUnits.parse(amount, decimalPlaces).getOrThrow().amount
                val funding = if (value == s.draftState.grandTotal.amount) null else remainder
                require(funding != PaymentCategory.ONLINE || s.onlineQr != null) { "Configure the merchant QR first" }
                CouponRedemptionCommand(OnlineSaleCommand(OperationId.generate(), session.business.id,
                    session.branch.id, session.user.id, TransactionId.generate(), s.draftState, currencyCode,
                    decimalPlaces, deviceId(), System.currentTimeMillis()), details.wallet.customerId,
                    details.wallet.id, user.user.id, user.name, value, funding).also { it.validate() }
            }
        } catch (e: Exception) {
            _uiState.value = s.copy(errorMessage = UiText.Dynamic(e.message ?: "Check coupon redemption details"))
            return
        }
        val context = financialContext
        val generation = financialSessionGeneration
        _uiState.value = s.copy(isExecuting = true, inFlightCouponRedemptionCommand = command, errorMessage = null)
        viewModelScope.launch {
            val result = try { submitCouponRedemption(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!shouldApplyFinancialResultToRegister(context, SessionManager.currentSession.value,
                    generation, financialSessionGeneration)) return@launch
            when (result) {
                is CommandResult.Success -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponRedemptionCommand = null, couponRedemptionResult = result.data,
                    lastCompletedTransaction = result.data.transaction)
                is CommandResult.PermissionDenied -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponRedemptionCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponRedemptionCommand = if (result.exception is IllegalArgumentException) null else command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Coupon redemption failed; retry the same request"))
            }
        }
    }

    fun confirmCreditSale(
        customer: com.dailymoneyregister.core.database.entity.CustomerEntity?,
        name: String,
        phone: String
    ) {
        val sessionSnapshot = SessionManager.currentSession.value
        val sessionChanged =
            synchronizeFinancialSession(sessionSnapshot)
    
        val s = _uiState.value
    
        if (
            sessionSnapshot == null ||
            sessionChanged ||
            s.overlay != RegisterOverlay.CreditSale ||
            s.isExecuting ||
            s.lastCompletedTransaction != null
        ) return
    
        if (!s.draftState.grandTotal.isPositive) {
            _uiState.value =
                s.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "Enter a sale amount before confirming Credit."
                        )
                )
            return
        }
    
        val resolvedName =
            customer?.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: name.trim()
    
        val resolvedPhone =
            customer?.canonicalPhone
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: phone.trim()
    
        if (
            resolvedName.isBlank() ||
            resolvedPhone.isBlank()
        ) {
            _uiState.value =
                s.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "Customer name and phone number are required."
                        )
                )
            return
        }
    
        val command =
            s.inFlightCreditSaleCommand
                ?: CreditSaleCommand(
                    operationId = OperationId.generate(),
                    businessId = sessionSnapshot.business.id,
                    branchId = sessionSnapshot.branch.id,
                    actorUserId = sessionSnapshot.user.id,
                    transactionIdVal = TransactionId.generate(),
                    customerPhone = resolvedPhone,
                    customerName = resolvedName,
                    draftState = s.draftState,
                    currencyCode =
                        sessionSnapshot.branch.currencyCode,
                    currencyDecimalPlaces =
                        sessionSnapshot.branch.currencyDecimalPlaces,
                    deviceId = deviceId(),
                    occurredAt = System.currentTimeMillis()
                )
    
        val executionContext =
            financialContext
    
        val executionGeneration =
            financialSessionGeneration
    
        _uiState.value =
            s.copy(
                isExecuting = true,
                inFlightCreditSaleCommand = command,
                errorMessage = null,
                infoMessage = null
            )
    
        viewModelScope.launch {
            val result =
                try {
                    submitCreditSale(command)
                } catch (error: Exception) {
                    if (
                        error is kotlinx.coroutines.CancellationException
                    ) throw error
    
                    CommandResult.Error(error)
                }
    
            if (
                !shouldApplyFinancialResultToRegister(
                    executionContext,
                    SessionManager.currentSession.value,
                    executionGeneration,
                    financialSessionGeneration
                )
            ) return@launch
    
            when (result) {
                is CommandResult.Success -> {
                    val invoice =
                        try {
                            loadCreditInvoice(command)
                        } catch (error: Exception) {
                            if (
                                error is kotlinx.coroutines.CancellationException
                            ) {
                                throw error
                            }

                            // Sale is already authoritative and saved.
                            // Sharing failure must never alter it.
                            null
                        }
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            overlay = null,
                            inFlightCreditSaleCommand = null,
                            couponCustomers = emptyList(),
                            couponSearching = false,

                            // Communication-only metadata.
                            // Populated only after authoritative Credit success.
                            creditSaleShareName =
                                command.customerName,
                            creditSaleSharePhone =
                                command.customerPhone,
                            creditInvoicePurchaseOccurredAt =
                                invoice?.purchaseOccurredAt,
                            creditInvoiceGivenMinor =
                                invoice?.creditGivenMinor,
                            creditInvoicePurchaseOutstandingMinor =
                                invoice?.purchaseOutstandingMinor,
                            creditInvoiceTotalOutstandingMinor =
                                invoice?.totalOutstandingMinor,
                            creditInvoiceCurrencyCode =
                                invoice?.currencyCode,
                            creditInvoiceDecimalPlaces =
                                invoice?.currencyDecimalPlaces,

                            lastCompletedTransaction =
                                result.data,
                            errorMessage = null
                        )
                }
    
                is CommandResult.PermissionDenied -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightCreditSaleCommand = null,
                            errorMessage =
                                UiText.Dynamic(
                                    result.message
                                )
                        )
                }
    
                is CommandResult.Conflict -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(
                                    result.message
                                )
                        )
                }
    
                is CommandResult.Error -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
    
                            // Preserve the same durable command on
                            // uncertain failure so retry remains
                            // idempotent.
                            inFlightCreditSaleCommand =
                                command,
    
                            errorMessage =
                                UiText.Dynamic(
                                    result.exception.message
                                        ?: "Credit sale failed. Retry the same sale."
                                )
                        )
                }
            }
        }
    }
        fun confirmCouponSale(customer: CustomerEntity?, name: String, phone: String, amount: String,
                          funding: PaymentCategory, additionalUsers: List<String>) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        val s = _uiState.value
        if (s.overlay !is RegisterOverlay.Coupon || s.isExecuting || s.couponSaleResult != null) return
        if (funding == PaymentCategory.ONLINE && s.onlineQr == null) return
        val command = try {
            s.inFlightCouponSaleCommand ?: CouponSaleCommand(
                OperationId.generate(), session.business.id, session.branch.id, session.user.id,
                TransactionId.generate(), customer?.id ?: IdGenerator.newId(), customer?.name ?: name.trim(),
                customer?.canonicalPhone ?: phone.trim(), customer == null,
                MoneyMinorUnits.parse(amount, decimalPlaces).getOrThrow().amount,
                currencyCode, decimalPlaces, funding, additionalUsers.map { it.trim() }.toList(),
                deviceId(), System.currentTimeMillis()).also { it.validate() }
        } catch (e: Exception) {
            _uiState.value = s.copy(errorMessage = UiText.Dynamic(e.message ?: "Check Coupon Sale details"))
            return
        }
        val context = financialContext
        val generation = financialSessionGeneration
        _uiState.value = s.copy(isExecuting = true, inFlightCouponSaleCommand = command, errorMessage = null)
        viewModelScope.launch {
            val result = try { submitCouponSale(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!shouldApplyFinancialResultToRegister(context, SessionManager.currentSession.value,
                    generation, financialSessionGeneration)) return@launch
            when (result) {
                is CommandResult.Success -> {
                    // BOARDLOCK_COUPON_SALE_WALLET_REFRESH
                    val walletDetails =
                        try {
                            loadCouponWalletDetails(
                                command.customerId,
                                command.currencyCode
                            )
                        } catch (
                            error: Exception
                        ) {
                            if (
                                error is
                                    kotlinx.coroutines.CancellationException
                            ) {
                                throw error
                            }

                            null
                        }

                    if (
                        !shouldApplyFinancialResultToRegister(
                            context,
                            SessionManager.currentSession.value,
                            generation,
                            financialSessionGeneration
                        )
                    ) {
                        return@launch
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightCouponSaleCommand = null,
                            couponSaleResult = result.data,
                            couponWalletDetails = walletDetails,
                            couponWalletLoading = false
                        )
                }
                is CommandResult.PermissionDenied -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponSaleCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponSaleCommand = if (result.exception is com.dailymoneyregister.feature.register.data.CouponSaleRejectedException) null else command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Coupon Sale failed. Retry the same purchase."))
            }
        }
    }

    fun confirmCouponTopUp(customer: CustomerEntity, walletId: String, amount: String, funding: PaymentCategory) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        val s = _uiState.value
        if (s.overlay !is RegisterOverlay.Coupon || s.isExecuting || s.couponSaleResult != null || s.couponTopUpResult != null) return
        if (funding == PaymentCategory.ONLINE && s.onlineQr == null) return
        val command = try {
            s.inFlightCouponTopUpCommand ?: CouponTopUpCommand(
                OperationId.generate(), session.business.id, session.branch.id, session.user.id,
                TransactionId.generate(), walletId, customer.id, customer.name, customer.canonicalPhone,
                MoneyMinorUnits.parse(amount, decimalPlaces).getOrThrow().amount,
                currencyCode, decimalPlaces, funding, deviceId(), System.currentTimeMillis()
            ).also { it.validate() }
        } catch (e: Exception) {
            _uiState.value = s.copy(errorMessage = UiText.Dynamic(e.message ?: "Check Coupon Top Up details"))
            return
        }
        val context = financialContext
        val generation = financialSessionGeneration
        _uiState.value = s.copy(isExecuting = true, inFlightCouponTopUpCommand = command, errorMessage = null)
        viewModelScope.launch {
            val result = try { submitCouponTopUp(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!shouldApplyFinancialResultToRegister(context, SessionManager.currentSession.value,
                    generation, financialSessionGeneration)) return@launch
            when (result) {
                is CommandResult.Success -> {
                    // BOARDLOCK_COUPON_TOPUP_WALLET_REFRESH
                    val walletDetails =
                        try {
                            loadCouponWalletDetails(
                                command.customerId,
                                command.currencyCode
                            )
                        } catch (
                            error: Exception
                        ) {
                            if (
                                error is
                                    kotlinx.coroutines.CancellationException
                            ) {
                                throw error
                            }

                            null
                        }

                    if (
                        !shouldApplyFinancialResultToRegister(
                            context,
                            SessionManager.currentSession.value,
                            generation,
                            financialSessionGeneration
                        )
                    ) {
                        return@launch
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightCouponTopUpCommand = null,
                            couponTopUpResult = result.data,
                            couponWalletDetails = walletDetails,
                            couponWalletLoading = false
                        )
                }
                is CommandResult.PermissionDenied -> _uiState.value = _uiState.value.copy(
                    isExecuting = false, inFlightCouponTopUpCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> _uiState.value = _uiState.value.copy(
                    isExecuting = false, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    inFlightCouponTopUpCommand = command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Coupon Top Up failed. Retry the same top up."))
            }
        }
    }

    fun openExpenses() {
        val session = SessionManager.currentSession.value

        if (session == null) {
            _uiState.value =
                _uiState.value.copy(
                    errorMessage = UiText.Dynamic(
                        "An authenticated register session is required."
                    )
                )
            return
        }

        if (synchronizeFinancialSession(session)) return
        if (_uiState.value.isExecuting) return

        _uiState.value =
            _uiState.value.copy(
                overlay = RegisterOverlay.Expense,
                expenseLoading = true,
                todayExpenses = emptyList(),
                errorMessage = null
            )

        refreshTodayExpenses(session)
    }

    fun closeExpenses() {
        if (_uiState.value.overlay != RegisterOverlay.Expense || _uiState.value.isExecuting) return

        _uiState.value =
            _uiState.value.copy(
                overlay = null,
                expenseLoading = false,
                todayExpenses = emptyList(),
                inFlightExpenseCommand = null,
                errorMessage = null
            )
    }

    private fun refreshTodayExpenses(
        session: ActiveSession? =
            SessionManager.currentSession.value
    ) {
        if (session == null) {
            _uiState.value =
                _uiState.value.copy(
                    expenseLoading = false
                )
            return
        }

        val generation = financialSessionGeneration
        val context = financialContext

        viewModelScope.launch {
            try {
                val zone =
                    java.time.ZoneId.of(
                        session.branch.timezoneId
                    )

                val today =
                    java.time.Instant
                        .ofEpochMilli(System.currentTimeMillis())
                        .atZone(zone)
                        .toLocalDate()

                val fromInclusive =
                    today
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()

                val toExclusive =
                    today
                        .plusDays(1)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()

                val expenses =
                    loadExpensesForPeriod(
                        session.business.id,
                        session.branch.id,
                        fromInclusive,
                        toExclusive
                    )

                if (
                    !shouldApplyFinancialResultToRegister(
                        context,
                        SessionManager.currentSession.value,
                        generation,
                        financialSessionGeneration
                    )
                ) {
                    return@launch
                }

                _uiState.value =
                    _uiState.value.copy(
                        expenseLoading = false,
                        todayExpenses = expenses
                    )
            } catch (error: Exception) {
                if (
                    error is kotlinx.coroutines.CancellationException
                ) {
                    throw error
                }

                _uiState.value =
                    _uiState.value.copy(
                        expenseLoading = false,
                        errorMessage =
                            UiText.Dynamic(
                                error.message
                                    ?: "Unable to load Expenses."
                            )
                    )
            }
        }
    }

    fun stageExpenseSupportingDocument(sourceUri: String) =
        expenseSupportingDocumentCoordinator.stageExpenseSupportingDocument(sourceUri)

    fun removeStagedExpenseSupportingDocument(sourceUri: String) =
        expenseSupportingDocumentCoordinator.removeStagedExpenseSupportingDocument(sourceUri)

    fun lookupExpensePayeeByPhone(canonicalPhone: String?) =
        customerSelectionCoordinator.lookupExpensePayeeByPhone(canonicalPhone)
    fun saveExpense(
        amountText: String,
        category: ExpenseCategory,
        paymentCategory: PaymentCategory,
        payeeName: String,
        payeePhone: String?,
        note: String,
        reference: String
    ) {
        val session =
            SessionManager.currentSession.value

        if (session == null) {
            _uiState.value =
                _uiState.value.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "An authenticated register session is required."
                        )
                )
            return
        }

        if (synchronizeFinancialSession(session)) return

        val state = _uiState.value

        if (state.overlay != RegisterOverlay.Expense || state.isExecuting) return

        val parsedAmount =
            MoneyMinorUnits.parse(
                amountText.trim(),
                session.branch.currencyDecimalPlaces
            ).getOrNull()

        if (parsedAmount == null) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "Enter a valid amount using no more than " +
                                "${session.branch.currencyDecimalPlaces} decimal places."
                        )
                )
            return
        }

        if (!parsedAmount.isPositive) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "Enter an Expense amount greater than zero."
                        )
                )
            return
        }

        val command =
            state.inFlightExpenseCommand
                ?: try {
                    ExpenseCommandFactory.createNew(
                        businessId = session.business.id,
                        branchId = session.branch.id,
                        actorUserId = session.user.id,
                        amountMinor = parsedAmount.amount,
                        currencyCode = session.branch.currencyCode,
                        category = category,
                        paymentCategory = paymentCategory,
                        payeeName = payeeName,
                        payeePhone = payeePhone,
                        reference = reference,
                        note = note,
                        deviceId = deviceId(),
                        occurredAt = System.currentTimeMillis()
                    )
                } catch (_: IllegalStateException) {
                    _uiState.value =
                        state.copy(
                            errorMessage =
                                UiText.Dynamic(
                                    "Expense payment must be Cash or Online."
                                )
                        )
                    return
                }

        val executionContext = financialContext
        val executionGeneration = financialSessionGeneration

        _uiState.value =
            state.copy(
                isExecuting = true,
                inFlightExpenseCommand = command,
                errorMessage = null
            )

        viewModelScope.launch {
            val result =
                submitExpense(command)

            if (
                !shouldApplyFinancialResultToRegister(
                    executionContext,
                    SessionManager.currentSession.value,
                    executionGeneration,
                    financialSessionGeneration
                )
            ) {
                return@launch
            }

            when (result) {
                is CommandResult.Success -> {
                    val stagedDocuments =
                        _uiState.value.stagedExpenseSupportingDocumentUris

                    var attachmentFailureCount = 0

                    for (sourceUri in stagedDocuments) {
                        try {
                            attachExpenseSupportingDocument(
                                command.expenseId,
                                sourceUri
                            )
                        } catch (error: Exception) {
                            if (
                                error is
                                    kotlinx.coroutines.CancellationException
                            ) {
                                throw error
                            }
                            attachmentFailureCount++
                        }
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightExpenseCommand = null,
                            stagedExpenseSupportingDocumentUris =
                                emptyList(),
                            infoMessage =
                                UiText.Dynamic(
                                    when {
                                        attachmentFailureCount > 0 ->
                                            "Expense saved. $attachmentFailureCount supporting document(s) could not be attached."
                                        result.isIdempotentReplay ->
                                            "Expense already saved."
                                        else ->
                                            "Expense saved."
                                    }
                                ),
                            errorMessage = null
                        )

                    refreshTodayExpenses()
                    openExpenseDetails(command.expenseId)
                }

                is CommandResult.Conflict ->
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightExpenseCommand = null,
                            errorMessage =
                                UiText.Dynamic(result.message)
                        )

                is CommandResult.PermissionDenied ->
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightExpenseCommand = null,
                            errorMessage =
                                UiText.Dynamic(result.message)
                        )

                is CommandResult.Error ->
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightExpenseCommand = command,
                            errorMessage =
                                UiText.Dynamic(
                                    result.exception.message
                                        ?: "Expense could not be saved. Retry the same Expense."
                                )
                        )
            }
        }
    }
    fun openDayClosing() {
        val session = SessionManager.currentSession.value ?: return

        if (synchronizeFinancialSession(session)) return
        if (_uiState.value.isExecuting) return

        _uiState.value =
            _uiState.value.copy(
                overlay = RegisterOverlay.DayClosing,
                dayClosingLoading = true,
                dayClosingReport = null,
                dayClosingActualCash = "",
                dayClosingReview = false,
                dayClosingResult = null,
                errorMessage = null
            )

        viewModelScope.launch {
            try {
                val report = loadCurrentRegisterShiftReport()

                _uiState.value =
                    _uiState.value.copy(
                        dayClosingLoading = false,
                        dayClosingReport = report,
                        errorMessage =
                            if (report == null) {
                                UiText.Resource(R.string.dmr20_day_no_open)
                            } else {
                                null
                            }
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error

                _uiState.value =
                    _uiState.value.copy(
                        dayClosingLoading = false,
                        errorMessage =
                            UiText.Dynamic(
                                error.message ?: "Unable to load Day Closing."
                            )
                    )
            }
        }
    }

    fun closeDayClosing() {
        if (_uiState.value.overlay != RegisterOverlay.DayClosing || _uiState.value.isExecuting) return

        _uiState.value =
            _uiState.value.copy(
                overlay = null,
                dayClosingLoading = false,
                dayClosingReport = null,
                dayClosingActualCash = "",
                dayClosingReview = false,
                dayClosingResult = null,
                errorMessage = null
            )
    }

    fun updateDayClosingActualCash(value: String) {
        if (_uiState.value.isExecuting) return
        if (_uiState.value.dayClosingReview) return
        if (_uiState.value.dayClosingResult != null) return

        val cleaned =
            value.filter { it.isDigit() || it == '.' }

        if (cleaned.count { it == '.' } > 1) return

        _uiState.value =
            _uiState.value.copy(
                dayClosingActualCash = cleaned,
                errorMessage = null
            )
    }

    fun reviewDayClosing() {
        val state = _uiState.value
        if (state.dayClosingResult != null) return
        if (state.dayClosingReport == null) return

        val entered =
            state.dayClosingActualCash.toBigDecimalOrNull()

        if (entered == null || entered.signum() < 0) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(R.string.dmr20_enter_actual_cash)
                )
            return
        }

        _uiState.value =
            state.copy(
                dayClosingReview = true,
                errorMessage = null
            )
    }

    fun backFromDayClosingReview() {
        if (_uiState.value.isExecuting) return

        _uiState.value =
            _uiState.value.copy(
                dayClosingReview = false
            )
    }

    fun confirmDayClosing() {
        val session = SessionManager.currentSession.value ?: return
        val state = _uiState.value
        val report = state.dayClosingReport ?: return

        if (state.isExecuting) return
        if (!state.dayClosingReview) return

        val entered =
            state.dayClosingActualCash.toBigDecimalOrNull()

        if (entered == null || entered.signum() < 0) {
            _uiState.value =
                state.copy(
                    dayClosingReview = false,
                    errorMessage =
                        UiText.Resource(R.string.dmr20_enter_actual_cash)
                )
            return
        }

        val multiplier =
            java.math.BigDecimal.TEN.pow(decimalPlaces)

        val countedMinor =
            try {
                entered
                    .multiply(multiplier)
                    .setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                _uiState.value =
                    state.copy(
                        dayClosingReview = false,
                        errorMessage =
                            UiText.Resource(R.string.dmr20_invalid_cash)
                    )
                return
            }

        val countCommand =
            CountRegisterShiftCommand(
                operationId = OperationId.generate(),
                businessId = session.business.id,
                branchId = session.branch.id,
                shiftId = report.shift.shiftId,
                countedCashMinor = countedMinor,
                actorUserId = session.user.id,
                deviceId = deviceId(),
                occurredAt = System.currentTimeMillis()
            )

        _uiState.value =
            state.copy(
                isExecuting = true,
                inFlightShiftCountCommand = countCommand,
                errorMessage = null
            )

        viewModelScope.launch {
            when (
                val countResult =
                    submitRegisterShiftCount(countCommand)
            ) {
                is CommandResult.Success -> {
                    val closingSession = SessionManager.currentSession.value
                    if (closingSession == null ||
                        closingSession.business.id != session.business.id ||
                        closingSession.branch.id != session.branch.id ||
                        (closingSession.user.role != UserRole.ADMIN && closingSession.user.id != session.user.id)
                    ) {
                        _uiState.value = _uiState.value.copy(
                            isExecuting = false,
                            inFlightShiftCountCommand = null,
                            inFlightShiftCloseCommand = null,
                            errorMessage = UiText.Dynamic("The signed-in identity changed during day closing. Reopen Day Closing and try again.")
                        )
                        return@launch
                    }
                    val closeOccurredAt = System.currentTimeMillis()
                    val closeCommand =
                        CloseRegisterShiftCommand(
                            operationId = OperationId.generate(),
                            businessId = closingSession.business.id,
                            branchId = closingSession.branch.id,
                            shiftId = report.shift.shiftId,
                            actorUserId = closingSession.user.id,
                            deviceId = deviceId(),
                            occurredAt = closeOccurredAt,
                            reportSnapshot = RegisterShiftCloseReportSnapshot(
                                shiftId = report.shift.shiftId.value,
                                currencyCode = closingSession.branch.currencyCode,
                                currencyDecimalPlaces = closingSession.branch.currencyDecimalPlaces,
                                currencySymbol = closingSession.branch.currencySymbol,
                                operationCount = report.operationCount,
                                salesCount = report.salesCount,
                                grossSalesMinor = report.grossSalesMinor,
                                cashSalesMinor = report.cashSalesMinor,
                                onlineSalesMinor = report.onlineSalesMinor,
                                creditGivenMinor = report.creditGivenMinor,
                                couponUsedMinor = report.couponUsedMinor,
                                cashCreditRepaymentsMinor = report.cashCreditRepaymentsMinor,
                                onlineCreditRepaymentsMinor = report.onlineCreditRepaymentsMinor,
                                couponCreditRepaymentsMinor = report.couponCreditRepaymentsMinor,
                                couponIssuedMinor = report.couponIssuedMinor,
                                couponTopUpMinor = report.couponTopUpMinor,
                                couponRefundCreditsMinor = report.couponRefundCreditsMinor,
                                cashRefundsMinor = report.cashRefundsMinor,
                                onlineRefundsMinor = report.onlineRefundsMinor,
                                cashExpensesMinor = report.cashExpensesMinor,
                                onlineExpensesMinor = report.onlineExpensesMinor,
                                cashCouponFundingMinor = report.cashCouponFundingMinor,
                                onlineCouponFundingMinor = report.onlineCouponFundingMinor,
                                cashLifecycleAdjustmentMinor = report.cashLifecycleAdjustmentMinor,
                                onlineLifecycleAdjustmentMinor = report.onlineLifecycleAdjustmentMinor,
                                refundCount = report.refundCount,
                                voidCount = report.voidCount,
                                correctionCount = report.correctionCount,
                                paymentMethodChangeCount = report.paymentMethodChangeCount,
                                expectedCashMinor = report.expectedCashMinor,
                                countedCashMinor = countedMinor,
                                differenceMinor = Math.subtractExact(countedMinor, report.expectedCashMinor),
                                cashierUserId = report.shift.actorUserId,
                                closedByUserId = closingSession.user.id,
                                closedAt = closeOccurredAt
                            )
                        )

                    _uiState.value =
                        _uiState.value.copy(
                            inFlightShiftCountCommand = null,
                            inFlightShiftCloseCommand = closeCommand
                        )

                    when (
                        val closeResult =
                            submitRegisterShiftClose(closeCommand)
                    ) {
                                                is CommandResult.Success -> {
                            val persisted =
                                closeResult.data

                            val persistedCounted =
                                persisted.countedCashMinor

                            val persistedClosedAt =
                                persisted.closedAt

                            val reconciliation =
                                if (
                                    !persisted.isOpen &&
                                    persistedCounted != null &&
                                    persistedClosedAt != null
                                ) {
                                    dayClosingReconciliation(
                                        expectedCashMinor =
                                            report.expectedCashMinor,
                                        countedCashMinor =
                                            persistedCounted
                                    )
                                } else {
                                    null
                                }

                            if (
                                reconciliation == null ||
                                persistedClosedAt == null
                            ) {
                                /*
                                 * Close already succeeded durably.
                                 * Never retry a financial close just because
                                 * presentation evidence is incomplete.
                                 */
                                _uiState.value =
                                    _uiState.value.copy(
                                        isExecuting = false,
                                        overlay = null,
                                        dayClosingLoading = false,
                                        dayClosingReport = null,
                                        dayClosingActualCash = "",
                                        dayClosingReview = false,
                                        dayClosingResult = null,
                                        inFlightShiftCountCommand = null,
                                        inFlightShiftCloseCommand = null,
                                        errorMessage = null,
                                        infoMessage =
                                            UiText.Dynamic(
                                                "Shift closed successfully."
                                            )
                                    )
                            } else {
                                val ownerId =
                                    persisted.actorUserId

                                val ownerDisplayName =
                                    if (
                                        ownerId ==
                                        closeCommand.actorUserId
                                    ) {
                                        session.user.displayName
                                    } else {
                                        ownerId
                                    }

                                val summary =
                                    DayClosingResultUi(
                                        reconciliation =
                                            reconciliation,
                                        shiftOwnerUserId =
                                            ownerId,
                                        shiftOwnerDisplayName =
                                            ownerDisplayName,
                                        closedByUserId =
                                            closeCommand.actorUserId,
                                        closedByDisplayName =
                                            session.user.displayName,
                                        closedAt =
                                            persistedClosedAt,
                                        closedAtDisplay =
                                            formatDayClosingTime(
                                                persistedClosedAt,
                                                session.branch.timezoneId
                                            )
                                    )

                                _uiState.value =
                                    _uiState.value.copy(
                                        isExecuting = false,
                                        overlay =
                                            RegisterOverlay.DayClosing,
                                        dayClosingLoading = false,
                                        dayClosingReport = report,
                                        dayClosingReview = false,
                                        dayClosingResult = summary,
                                        inFlightShiftCountCommand = null,
                                        inFlightShiftCloseCommand = null,
                                        errorMessage = null,
                                        infoMessage = null
                                    )
                            }
                        }

                        is CommandResult.Conflict ->
                            _uiState.value =
                                _uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(closeResult.message)
                                )

                        is CommandResult.PermissionDenied ->
                            _uiState.value =
                                _uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(closeResult.message)
                                )

                        is CommandResult.Error ->
                            _uiState.value =
                                _uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(
                                            closeResult.exception.message
                                                ?: "Unable to close shift."
                                        )
                                )
                    }
                }

                is CommandResult.Conflict ->
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(countResult.message)
                        )

                is CommandResult.PermissionDenied ->
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(countResult.message)
                        )

                is CommandResult.Error ->
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(
                                    countResult.exception.message
                                        ?: "Unable to save cash count."
                                )
                        )
            }
        }
    }
    // Upcoming Phase Payment Trigger: Credit
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
                taxRateBps = 0,
                isTaxEnabled = false, // Tax must NOT be auto-applied on reset
                taxRoundingMode = branch?.taxRoundingMode ?: com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP
            ),
            inFlightSaleCommand = null,
            lastCompletedTransaction = null
        )
    }
}
