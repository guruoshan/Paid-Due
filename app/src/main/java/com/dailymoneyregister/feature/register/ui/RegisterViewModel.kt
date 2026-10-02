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
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
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

import com.dailymoneyregister.core.model.tax.TaxRateParser

data class RefundToCouponDraft(
    val transactionId: String,
    val amountInput: String,
    val amountMinor: Long,
    val reason: String?
)

data class RegisterUiState(
    val calculatorState: CalculatorState = CalculatorState(),
    val draftState: DraftState = DraftState(),
    val inFlightSaleCommand: CashSaleCommand? = null,
    val inFlightOnlineSaleCommand: OnlineSaleCommand? = null,
    val inFlightCouponSaleCommand: CouponSaleCommand? = null,
    val inFlightCouponTopUpCommand: CouponTopUpCommand? = null,
    val inFlightCreditSaleCommand: CreditSaleCommand? = null,
    val isCreditSaleOpen: Boolean = false,
    val creditSaleShareName: String? = null,
    val creditSaleSharePhone: String? = null,
    val creditInvoicePurchaseOccurredAt: Long? = null,
    val creditInvoiceGivenMinor: Long? = null,
    val creditInvoicePurchaseOutstandingMinor: Long? = null,
    val creditInvoiceTotalOutstandingMinor: Long? = null,
    val creditInvoiceCurrencyCode: String? = null,
    val creditInvoiceDecimalPlaces: Int? = null,
    val isCouponSaleOpen: Boolean = false,
    val couponCustomers: List<CouponSaleCustomerWallet> = emptyList(),
    val couponSearching: Boolean = false,
    val couponSaleResult: CouponSaleResult? = null,
    val couponTopUpResult: CouponTopUpResult? = null,
    val isCouponWalletOpen: Boolean = false,
    val couponWalletLoading: Boolean = false,
    val couponWalletDetails: CouponWalletDetails? = null,
    val inFlightCouponUserCommand: CouponAuthorizedUserCommand? = null,
    val inFlightCouponRedemptionCommand: CouponRedemptionCommand? = null,
    val couponRedemptionResult: CouponRedemptionResult? = null,
    val refundToCouponDraft: RefundToCouponDraft? = null,
    val inFlightRefundToCouponCommand: RefundCommand? = null,
    val refundToCouponResult: RefundExecutionResult? = null,
    val manualDescription: String = "",
    val isItemizedBillDialogOpen: Boolean = false,
    val isTaxDialogOpen: Boolean = false,
    val isShippingPackingDialogOpen: Boolean = false,
    val isAddItemDialogOpen: Boolean = false,
    /** Non-null while Add Item is open in EDIT mode for this existing item id. */
    val editingItemId: String? = null,
    val isRecentTransactionsExpanded: Boolean = false,
    val transactionDetailsLoading: Boolean = false,
    val transactionDetails: TransactionDetailsReadModel? = null,
    val lastCompletedTransaction: TransactionEntity? = null,
    val isExecuting: Boolean = false,
    val isOnlinePaymentOpen: Boolean = false,
    val onlineQr: MerchantQrSelection? = null,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null
)

internal fun clearedRegisterDraft(branch: BranchEntity?): DraftState = DraftState(
    taxRateBps = 0,
    isTaxEnabled = false,
    taxRoundingMode = branch?.taxRoundingMode
        ?: com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP
)

@HiltViewModel
class RegisterViewModel internal constructor(
    private val observeToday: (String, String) -> Flow<List<RecentFinancialActivity>>,
    private val executeSale: suspend (CashSaleCommand, UserAccountEntity, UserBranchMembershipEntity?) -> CommandResult<TransactionEntity>,
    private val deviceId: () -> String,
    private val executeOnlineSale: suspend (OnlineSaleCommand) -> CommandResult<TransactionEntity> = { error("Online sale is not configured") },
    private val loadOnlineQr: suspend (String) -> MerchantQrSelection? = { null },
    private val submitCreditSale: suspend (CreditSaleCommand) -> CommandResult<TransactionEntity> = { error("Credit Sale unavailable") },
    private val loadCreditInvoice:
        suspend (CreditSaleCommand) ->
            com.dailymoneyregister.feature.register.data.RegisterRepository.CreditInvoiceDetails =
        {
            error("Credit invoice unavailable")
        },
    private val submitCouponSale: suspend (CouponSaleCommand) -> CommandResult<CouponSaleResult> = { error("Coupon Sale unavailable") },
        private val findCouponCustomers: suspend (String) -> List<CouponSaleCustomerWallet> = { emptyList() },
    private val loadCouponWalletDetails: suspend (String, String) -> CouponWalletDetails? = { _, _ -> null },
    private val submitCouponTopUp: suspend (CouponTopUpCommand) -> CommandResult<CouponTopUpResult> = { error("Coupon Top Up unavailable") },
    private val submitCouponUser: suspend (CouponAuthorizedUserCommand) -> CommandResult<RegisterRepository.CouponAuthorizedUserDetails> = { error("Coupon authorization unavailable") },
    private val submitCouponRedemption: suspend (CouponRedemptionCommand) -> CommandResult<CouponRedemptionResult> = { error("Coupon redemption unavailable") },
    private val productRepository: ProductRepository? = null,
    private val loadTransactionDetails: suspend (String) -> TransactionDetailsReadModel? = { null },
    private val submitAmountCorrection: suspend (AmountCorrectionCommand) -> CommandResult<AmountCorrectionExecutionResult> = {
        error("Amount Correction unavailable")
    },
    private val submitRefund: suspend (RefundCommand) -> CommandResult<RefundExecutionResult> = {
        error("Refund unavailable")
    },
    private val submitVoid: suspend (VoidCommand) -> CommandResult<VoidExecutionResult> = {
        error("Void unavailable")
    },
    private val submitPaymentMethodChange: suspend (PaymentMethodChangeCommand) -> CommandResult<PaymentMethodChangeExecutionResult> = {
        error("Payment Method Change unavailable")
    }
) : ViewModel() {

    @Inject
    constructor(
        registerRepository: RegisterRepository,
        deviceIdentityProvider: DeviceIdentityProvider,
        merchantQrRepository: MerchantQrRepository,
        productRepository: ProductRepository
    ) : this(
        registerRepository::observeTodayTransactions,
        registerRepository::executeCashSale,
        { deviceIdentityProvider.deviceId },
        registerRepository::executeOnlineSale,
        { branchId -> merchantQrRepository.getActive(branchId) },
        registerRepository::executeCreditSale,
        registerRepository::getCreditInvoiceDetails,
        registerRepository::executeCouponSale,
        registerRepository::searchCouponSaleCustomers,
        registerRepository::getCouponWalletDetails,
        registerRepository::executeCouponTopUp,
        registerRepository::executeCouponAuthorizedUser,
        registerRepository::executeCouponRedemption,
        productRepository,
        registerRepository::getTransactionDetails,
        registerRepository::executeAmountCorrection,
        registerRepository::executeRefund,
        registerRepository::executeVoid,
        registerRepository::executePaymentMethodChange
    )

    private val initialSession = SessionManager.currentSession.value
    private var financialContext = initialSession.toRegisterFinancialSessionContext()
    internal var financialSessionGeneration: Long = 0
        private set

    /*
     * UI-only request generation.
     *
     * This is not durable financial state. It prevents an older asynchronous
     * Transaction Details read from reopening or overwriting a newer/closed view.
     */
    private var transactionDetailsRequestGeneration: Long = 0

    val activeBusiness: BusinessEntity? get() = SessionManager.activeBusiness
    val activeBranch: BranchEntity? get() = SessionManager.activeBranch
    val activeUser: UserAccountEntity? get() = SessionManager.activeUser
    val activeMembership: UserBranchMembershipEntity? get() = SessionManager.activeMembership

    val canRefundFromDetails: Boolean
        get() = isPermitted(PermissionAction.REFUND)

    val canVoidFromDetails: Boolean
        get() = isPermitted(PermissionAction.VOID)

    val canChangePaymentMethodFromDetails: Boolean
        get() = isPermitted(PermissionAction.CHANGE_PAYMENT_METHOD)

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
        transactionDetailsRequestGeneration++
        calculatorEngine = CalculatorEngine(decimalPlaces)
        _uiState.value = resetRegisterUiStateForSession(_uiState.value, sessionSnapshot).copy(
            transactionDetailsLoading = false,
            transactionDetails = null,
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
    fun toggleAddItemDialog(isOpen: Boolean) {
        _uiState.value = _uiState.value.copy(
            isAddItemDialogOpen = isOpen,
            editingItemId = if (isOpen) _uiState.value.editingItemId else null
        )
    }
    fun toggleRecentTransactionsExpanded() {
        _uiState.value = _uiState.value.copy(isRecentTransactionsExpanded = !_uiState.value.isRecentTransactionsExpanded)
    }
    fun clearInfoMessage() { _uiState.value = _uiState.value.copy(infoMessage = null) }
    /**
     * Loads the authoritative read-only lifetime projection for one transaction.
     *
     * This function does not mutate financial state and does not use Today's Recent
     * list as the Transaction Details source.
     */
    fun openTransactionDetails(transactionId: String) {
        if (transactionId.isBlank()) return

        val session = SessionManager.currentSession.value

        if (
            synchronizeFinancialSession(session) ||
            session == null ||
            _uiState.value.transactionDetailsLoading
        ) {
            return
        }

        val context = financialContext
        val financialGeneration = financialSessionGeneration

        transactionDetailsRequestGeneration++
        val requestGeneration = transactionDetailsRequestGeneration

        _uiState.value = _uiState.value.copy(
            transactionDetailsLoading = true,
            transactionDetails = null
        )

        viewModelScope.launch {
            val details =
                try {
                    loadTransactionDetails(transactionId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }

            if (
                requestGeneration != transactionDetailsRequestGeneration ||
                !shouldApplyFinancialResultToRegister(
                    context,
                    SessionManager.currentSession.value,
                    financialGeneration,
                    financialSessionGeneration
                )
            ) {
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                transactionDetailsLoading = false,
                transactionDetails = details
            )
        }
    }

    /**
     * Closes only the read-only Transaction Details projection.
     * No transaction or financial record is changed.
     */

    fun executeAmountCorrectionFromDetails(
        correctedAmountInput: String,
        reason: String?
    ) {
        val session =
            SessionManager.currentSession.value

        if (
            synchronizeFinancialSession(session) ||
            session == null
        ) {
            return
        }

        val state = _uiState.value
        val details = state.transactionDetails ?: return

        if (state.isExecuting) {
            return
        }

        val corrected =
            MoneyMinorUnits
                .parse(
                    correctedAmountInput,
                    decimalPlaces
                )
                .getOrNull()

        if (
            corrected == null ||
            corrected.amount < 0L
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_correct_amount_invalid
                        )
                )
            return
        }

        if (
            corrected.amount ==
                details.financial.currentAmount.amount
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_correct_amount_same
                        )
                )
            return
        }

        val latest =
            details.financial.latestActivityAt

        val occurredAt =
            if (latest < Long.MAX_VALUE) {
                maxOf(
                    System.currentTimeMillis(),
                    latest + 1L
                )
            } else {
                System.currentTimeMillis()
            }

        val command =
            AmountCorrectionCommand(
                operationId =
                    OperationId.generate(),
                businessId =
                    session.business.id,
                branchId =
                    session.branch.id,
                subjectId =
                    details.financial.transaction.id,
                actorUserId =
                    session.user.id,
                deviceId =
                    deviceId(),
                occurredAt =
                    occurredAt,
                correctedAmountMinor =
                    corrected.amount,
                reason =
                    reason
                        ?.trim()
                        ?.takeIf {
                            it.isNotEmpty()
                        }
            )

        val context =
            financialContext

        val generation =
            financialSessionGeneration

        _uiState.value =
            state.copy(
                isExecuting = true,
                errorMessage = null,
                infoMessage = null
            )

        viewModelScope.launch {

            val result =
                try {
                    submitAmountCorrection(command)
                } catch (
                    error: Exception
                ) {
                    if (
                        error is
                            kotlinx.coroutines.CancellationException
                    ) {
                        throw error
                    }

                    CommandResult.Error(error)
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

            when (result) {

                is CommandResult.Success -> {

                    val refreshed =
                        try {
                            loadTransactionDetails(
                                command.subjectId
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

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            transactionDetails = refreshed,
                            errorMessage = null,
                            infoMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_saved
                                )
                        )
                }

                is CommandResult.PermissionDenied -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_permission
                                )
                        )
                }

                is CommandResult.Conflict -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_conflict
                                )
                        )
                }

                is CommandResult.Error -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_failed
                                )
                        )
                }
            }
        }
    }
    fun beginRefundToCouponFromDetails(
        amountInput: String,
        reason: String?
    ) {
        val session =
            SessionManager.currentSession.value

        if (
            synchronizeFinancialSession(session) ||
            session == null
        ) return

        val state =
            _uiState.value

        val details =
            state.transactionDetails
                ?: return

        if (state.isExecuting) return

        val amount =
            MoneyMinorUnits
                .parse(
                    amountInput,
                    decimalPlaces
                )
                .getOrNull()

        if (
            amount == null ||
            !amount.isPositive ||
            amount >
            details.financial.netAmount
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_refund_invalid
                        )
                )

            return
        }
_uiState.value =
            state.copy(
                isCouponSaleOpen = true,
                couponCustomers = emptyList(),
                couponSearching = false,
                couponSaleResult = null,
                couponTopUpResult = null,
                couponRedemptionResult = null,
                refundToCouponDraft =
                    RefundToCouponDraft(
                        transactionId =
                            details.financial.transaction.id,
                        amountInput =
                            amountInput.trim(),
                        amountMinor =
                            amount.amount,
                        reason =
                            normalizedReason(reason)
                    ),
                inFlightRefundToCouponCommand = null,
                refundToCouponResult = null,
                couponWalletDetails = null,
                couponWalletLoading = false,
                isCouponWalletOpen = false,
                errorMessage = null,
                infoMessage = null,
                onlineQr = null
            )
    }

    fun confirmRefundToCoupon(
        customer: CustomerEntity?,
        name: String,
        phone: String,
        additionalUsers: List<String>
    ) {
        val session =
            SessionManager.currentSession.value

        if (
            synchronizeFinancialSession(session) ||
            session == null
        ) return

        val state =
            _uiState.value

        val draft =
            state.refundToCouponDraft
                ?: return

        val details =
            state.transactionDetails
                ?: return

        if (
            !state.isCouponSaleOpen ||
            state.isExecuting ||
            state.refundToCouponResult != null
        ) return

        if (
            details.financial.transaction.id !=
            draft.transactionId
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "Refund transaction changed. Reopen Refund."
                        )
                )

            return
        }
val destination =
            try {
                com.dailymoneyregister.feature.register.data
                    .RefundCouponDestination(
                        customerId =
                            customer?.id
                                ?: IdGenerator.newId(),
                        customerName =
                            customer?.name
                                ?: name.trim(),
                        canonicalPhone =
                            customer?.canonicalPhone
                                ?: phone.trim(),
                        createCustomer =
                            customer == null,
                        additionalUserNames =
                            additionalUsers
                                .map {
                                    it.trim()
                                }
                                .filter {
                                    it.isNotBlank()
                                }
                                .distinct()
                    )
            } catch (error: Exception) {
                _uiState.value =
                    state.copy(
                        errorMessage =
                            UiText.Dynamic(
                                error.message
                                    ?: "Check Refund Coupon customer"
                            )
                    )

                return
            }

        val command =
            state.inFlightRefundToCouponCommand
                ?: RefundCommand(
                    operationId =
                        OperationId.generate(),
                    businessId =
                        session.business.id,
                    branchId =
                        session.branch.id,
                    subjectId =
                        draft.transactionId,
                    actorUserId =
                        session.user.id,
                    deviceId =
                        deviceId(),
                    occurredAt =
                        nextDetailsActionTime(
                            details
                        ),
                    refundAmountMinor =
                        draft.amountMinor,
                    destinationCategory =
                        PaymentCategory.COUPON,
                    destinationChannel =
                        PaymentChannel.COUPON_WALLET,
                    reason =
                        draft.reason,
                    couponDestination =
                        destination
                )

        val context =
            financialContext

        val generation =
            financialSessionGeneration

        _uiState.value =
            state.copy(
                isExecuting = true,
                inFlightRefundToCouponCommand =
                    command,
                errorMessage = null,
                infoMessage = null
            )

        viewModelScope.launch {

            val result =
                try {
                    submitRefund(command)
                } catch (error: Exception) {
                    if (
                        error is
                        kotlinx.coroutines
                            .CancellationException
                    ) throw error

                    CommandResult.Error(error)
                }

            if (
                !shouldApplyFinancialResultToRegister(
                    context,
                    SessionManager.currentSession.value,
                    generation,
                    financialSessionGeneration
                )
            ) return@launch

            when (result) {

                is CommandResult.Success -> {

                    val refreshedDetails =
                        try {
                            loadTransactionDetails(
                                draft.transactionId
                            )
                        } catch (error: Exception) {
                            if (
                                error is
                                kotlinx.coroutines
                                    .CancellationException
                            ) throw error

                            null
                        }

                    val refreshedWallet =
                        try {
                            loadCouponWalletDetails(
                                destination.customerId,
                                currencyCode
                            )
                        } catch (error: Exception) {
                            if (
                                error is
                                kotlinx.coroutines
                                    .CancellationException
                            ) throw error

                            null
                        }

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightRefundToCouponCommand = null,
                            refundToCouponResult =
                                result.data,
                            transactionDetails =
                                refreshedDetails
                                    ?: _uiState.value
                                        .transactionDetails,
                            couponWalletDetails =
                                refreshedWallet,
                            couponWalletLoading = false,
                            errorMessage = null,
                            infoMessage =
                                UiText.Resource(
                                    R.string.tx_refund_saved
                                )
                        )
                }

                is CommandResult.PermissionDenied -> {

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightRefundToCouponCommand = null,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_refund_permission
                                )
                        )
                }

                is CommandResult.Conflict -> {

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_lifecycle_conflict
                                )
                        )
                }

                is CommandResult.Error -> {

                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            inFlightRefundToCouponCommand =
                                command,
                            errorMessage =
                                UiText.Dynamic(
                                    result.exception.message
                                        ?: "Refund to Coupon failed; retry the same request"
                                )
                        )
                }
            }
        }
    }

    fun executeRefundFromDetails(
        amountInput: String,
        destination: PaymentCategory,
        reason: String?
    ) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return

        val state = _uiState.value
        val details = state.transactionDetails ?: return
        if (state.isExecuting) return

        val amount = MoneyMinorUnits.parse(amountInput, decimalPlaces).getOrNull()
        if (amount == null || !amount.isPositive || amount > details.financial.netAmount) {
            _uiState.value = state.copy(
                errorMessage = UiText.Resource(R.string.tx_refund_invalid)
            )
            return
        }

        val creditReturnRoute =
            creditReturnUiRoute(
                paymentCategory =
                    details.financial.currentPaymentCategory,
                paymentChannel =
                    details.financial.currentPaymentChannel,
                creditRepaymentAllocationCount =
                    details.creditRepaymentAllocations.size
            )

        if (
            creditReturnRoute ==
                CreditReturnUiRoute.INVALID_CREDIT
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "This Credit transaction cannot be returned because its payment channel does not reconcile with the authoritative Credit account."
                        )
                )

            return
        }
if (
            creditReturnRoute ==
                CreditReturnUiRoute.NON_CREDIT &&
            destination ==
                PaymentCategory.CREDIT
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_refund_invalid_destination
                        )
                )

            return
        }

        val channel =
            when (destination) {
                PaymentCategory.COUPON ->
                    PaymentChannel.COUPON_WALLET

                PaymentCategory.CASH ->
                    PaymentChannel.CASH_TENDER

                PaymentCategory.ONLINE ->
                    details.financial.currentPaymentChannel
                        ?.takeIf {
                            it ==
                                PaymentChannel.MANUAL_QR ||
                            it ==
                                PaymentChannel.SOFTPOS
                        }
                        ?: PaymentChannel.MANUAL_QR

                PaymentCategory.CREDIT ->
                    PaymentChannel.CREDIT_ACCOUNT
            }

        if (
            creditReturnRoute ==
                CreditReturnUiRoute.NON_CREDIT &&
            destination !=
                PaymentCategory.COUPON &&
            destination !=
                details.financial.currentPaymentCategory
        ) {
            _uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_refund_invalid_destination
                        )
                )

            return
        }

        val command = RefundCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            subjectId = details.financial.transaction.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = nextDetailsActionTime(details),
            refundAmountMinor = amount.amount,
            destinationCategory = destination,
            destinationChannel = channel,
            reason = normalizedReason(reason)
        )
        executeDetailsCommand(
            subjectId = command.subjectId,
            successMessage = R.string.tx_refund_saved,
            permissionMessage = R.string.tx_refund_permission,
            conflictMessage = R.string.tx_lifecycle_conflict,
            failureMessage = R.string.tx_refund_failed
        ) { submitRefund(command) }
    }

    fun executeVoidFromDetails(reason: String?) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return

        val state = _uiState.value
        val details = state.transactionDetails ?: return
        if (state.isExecuting) return

        val command = VoidCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            subjectId = details.financial.transaction.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = nextDetailsActionTime(details),
            reason = normalizedReason(reason)
        )
        executeDetailsCommand(
            subjectId = command.subjectId,
            successMessage = R.string.tx_void_saved,
            permissionMessage = R.string.tx_void_permission,
            conflictMessage = R.string.tx_lifecycle_conflict,
            failureMessage = R.string.tx_void_failed
        ) { submitVoid(command) }
    }

    fun executePaymentMethodChangeFromDetails(
        destination: PaymentCategory,
        reason: String?
    ) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return

        val state = _uiState.value
        val details = state.transactionDetails ?: return
        if (state.isExecuting) return

        val channel = when (destination) {
            PaymentCategory.CASH -> PaymentChannel.CASH_TENDER
            PaymentCategory.ONLINE -> PaymentChannel.MANUAL_QR
            else -> {
                _uiState.value = state.copy(
                    errorMessage = UiText.Resource(R.string.tx_change_payment_invalid)
                )
                return
            }
        }
        if (destination == details.financial.currentPaymentCategory) {
            _uiState.value = state.copy(
                errorMessage = UiText.Resource(R.string.tx_change_payment_same)
            )
            return
        }

        val command = PaymentMethodChangeCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            subjectId = details.financial.transaction.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = nextDetailsActionTime(details),
            newCategory = destination,
            newChannel = channel,
            reason = normalizedReason(reason)
        )
        executeDetailsCommand(
            subjectId = command.subjectId,
            successMessage = R.string.tx_change_payment_saved,
            permissionMessage = R.string.tx_change_payment_permission,
            conflictMessage = R.string.tx_lifecycle_conflict,
            failureMessage = R.string.tx_change_payment_failed
        ) { submitPaymentMethodChange(command) }
    }

    private fun nextDetailsActionTime(details: TransactionDetailsReadModel): Long =
        if (details.financial.latestActivityAt < Long.MAX_VALUE) {
            maxOf(System.currentTimeMillis(), details.financial.latestActivityAt + 1L)
        } else {
            System.currentTimeMillis()
        }

    private fun normalizedReason(reason: String?): String? =
        reason?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> executeDetailsCommand(
        subjectId: String,
        successMessage: Int,
        permissionMessage: Int,
        conflictMessage: Int,
        failureMessage: Int,
        onSuccess: (T) -> Unit = {},
        submit: suspend () -> CommandResult<T>
    ) {
        val context = financialContext
        val generation = financialSessionGeneration
        _uiState.value = _uiState.value.copy(
            isExecuting = true,
            errorMessage = null,
            infoMessage = null
        )

        viewModelScope.launch {
            val result = try {
                submit()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                CommandResult.Error(error)
            }
            if (!shouldApplyFinancialResultToRegister(
                    context,
                    SessionManager.currentSession.value,
                    generation,
                    financialSessionGeneration
                )
            ) return@launch

            when (result) {
                is CommandResult.Success -> {
                    val refreshed = try {
                        loadTransactionDetails(subjectId)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        null
                    }
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        transactionDetails = refreshed ?: _uiState.value.transactionDetails,
                        errorMessage = null,
                        infoMessage = UiText.Resource(successMessage)
                    )

                    onSuccess(result.data)
                }
                is CommandResult.PermissionDenied -> {
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Resource(permissionMessage)
                    )
                }
                is CommandResult.Conflict -> {
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Resource(conflictMessage)
                    )
                }
                is CommandResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        errorMessage = result.exception.message?.let { UiText.Dynamic(it) } ?: UiText.Resource(failureMessage)
                    )
                }
            }
        }
    }

    fun closeTransactionDetails() {
        transactionDetailsRequestGeneration++

        _uiState.value = _uiState.value.copy(
            transactionDetailsLoading = false,
            transactionDetails = null
        )
    }
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
            isAddItemDialogOpen = false,
            editingItemId = null
        )

        rememberProduct(newItem.name, newItem.description, unitPrice.amount)
    }

    /**
     * Replaces an existing draft item in place (name/price/qty/description),
     * as opposed to addItem() which always appends a new one. Used when the
     * cashier reopens an already-added item to correct it.
     */
    fun editItem(
        itemId: String,
        name: String,
        priceText: String,
        quantity: Int,
        description: String? = null
    ) {
        synchronizeFinancialSession(SessionManager.currentSession.value)
        if (name.isBlank() || quantity <= 0) return
        val unitPrice = MoneyMinorUnits.parse(priceText, decimalPlaces).getOrElse { return }
        if (unitPrice.isNegative) return

        val currentItems = _uiState.value.draftState.items.map { item ->
            if (item.id == itemId) {
                item.copy(
                    name = name.trim(),
                    description = description?.trim()?.ifBlank { null },
                    unitPrice = unitPrice,
                    quantity = quantity
                )
            } else {
                item
            }
        }

        val updatedDraft = _uiState.value.draftState.copy(items = currentItems)
        _uiState.value = _uiState.value.copy(
            draftState = updatedDraft,
            isAddItemDialogOpen = false,
            editingItemId = null
        )

        rememberProduct(name.trim(), description?.trim()?.ifBlank { null }, unitPrice.amount)
    }

    /** Opens Add Item pre-filled with an existing item's values for editing. */
    fun startEditingItem(itemId: String) {
        _uiState.value = _uiState.value.copy(
            editingItemId = itemId,
            isAddItemDialogOpen = true
        )
    }

    fun cancelEditingItem() {
        _uiState.value = _uiState.value.copy(editingItemId = null)
    }

    private fun rememberProduct(name: String, description: String?, unitPriceMinor: Long) {
        val branchId = SessionManager.activeBranch?.id ?: return
        val repository = productRepository ?: return
        viewModelScope.launch {
            repository.remember(branchId, name, description, unitPriceMinor)
        }
    }

    /** Prefix search over previously-used item names, for the Add Item autocomplete. */
    suspend fun searchProducts(query: String): List<ProductEntity> {
        val branchId = SessionManager.activeBranch?.id ?: return emptyList()
        return productRepository?.search(branchId, query) ?: emptyList()
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
        if (_uiState.value.isCouponSaleOpen || _uiState.value.isExecuting) return
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

    fun openOnlinePayment() {
        if (_uiState.value.isCouponSaleOpen || _uiState.value.isExecuting) return
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
        viewModelScope.launch {
            val qr = loadOnlineQr(session.branch.id)
            _uiState.value = _uiState.value.copy(
                isOnlinePaymentOpen = true,
                onlineQr = qr,
                errorMessage = null
            )
        }
    }

    fun cancelOnlinePayment() {
        if (_uiState.value.isExecuting) return
        _uiState.value = _uiState.value.copy(isOnlinePaymentOpen = false, onlineQr = null)
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
            occurredAt = System.currentTimeMillis()
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
                    isOnlinePaymentOpen = false,
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

    fun openCouponSale() {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null || _uiState.value.isExecuting) return
        if (_uiState.value.lastCompletedTransaction != null) return
        _uiState.value = _uiState.value.copy(isCouponSaleOpen = true, errorMessage = null, onlineQr = null)
        val generation = financialSessionGeneration
        viewModelScope.launch {
            try {
                val qr = loadOnlineQr(session.branch.id)
                if (generation == financialSessionGeneration && _uiState.value.isCouponSaleOpen &&
                    session.toRegisterFinancialSessionContext() == SessionManager.currentSession.value.toRegisterFinancialSessionContext()) {
                    _uiState.value = _uiState.value.copy(onlineQr = qr)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Cash remains available; an online purchase requires the configured QR.
            }
        }
    }

    fun openCouponRedemption() {
        openCouponSale()
    }

    fun closeCouponSale() {
        val s = _uiState.value

        if (
            s.isExecuting ||
            s.inFlightCouponSaleCommand != null ||
            s.inFlightCouponTopUpCommand != null ||
            s.inFlightCouponRedemptionCommand != null
        ) return

        _uiState.value =
            s.copy(
                isCouponSaleOpen = false,
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
        _uiState.value = _uiState.value.copy(isCouponWalletOpen = showDialog, couponWalletDetails = null, couponWalletLoading = true)
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
        _uiState.value = _uiState.value.copy(isCouponWalletOpen = false, couponWalletDetails = null, couponWalletLoading = false)
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
                    openCouponWallet(details.wallet.customerId, s.isCouponWalletOpen)
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

            isCreditSaleOpen = true,

            errorMessage = null

        )

    }


    fun closeCreditSale() {

        val state = _uiState.value


        if (state.isExecuting) {

            return

        }


        _uiState.value = state.copy(

            isCreditSaleOpen = false,

            errorMessage = null

        )

    }

    fun searchCouponCustomers(query: String) {
        if (_uiState.value.couponSearching || _uiState.value.isExecuting) return
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        val generation = financialSessionGeneration
        _uiState.value = _uiState.value.copy(couponSearching = true)
        viewModelScope.launch {
            try {
                val customers = findCouponCustomers(query)
                if (shouldApplyFinancialResultToRegister(financialContext, SessionManager.currentSession.value,
                        generation, financialSessionGeneration) &&
                        (_uiState.value.isCouponSaleOpen || _uiState.value.isCreditSaleOpen)) {
                    _uiState.value = _uiState.value.copy(couponCustomers = customers, couponSearching = false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation == financialSessionGeneration) _uiState.value = _uiState.value.copy(
                    couponSearching = false, errorMessage = UiText.Dynamic(e.message ?: "Customer search failed"))
            }
        }
    }

    fun confirmCouponRedemption(customer: CustomerEntity?, userId: String?, amount: String, remainder: PaymentCategory?) {
        val session = SessionManager.currentSession.value
        if (synchronizeFinancialSession(session) || session == null) return
        val s = _uiState.value
        if (s.isExecuting || !s.isCouponSaleOpen || s.couponRedemptionResult != null) return
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
            !s.isCreditSaleOpen ||
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
                            isCreditSaleOpen = false,
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
        if (!s.isCouponSaleOpen || s.isExecuting || s.couponSaleResult != null) return
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
                is CommandResult.Success -> _uiState.value = _uiState.value.copy(isExecuting = false,
                    inFlightCouponSaleCommand = null, couponSaleResult = result.data)
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
        if (!s.isCouponSaleOpen || s.isExecuting || s.couponSaleResult != null || s.couponTopUpResult != null) return
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
                is CommandResult.Success -> _uiState.value = _uiState.value.copy(
                    isExecuting = false, inFlightCouponTopUpCommand = null, couponTopUpResult = result.data)
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


