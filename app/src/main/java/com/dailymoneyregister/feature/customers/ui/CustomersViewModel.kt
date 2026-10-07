package com.dailymoneyregister.feature.customers.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.dao.CustomerSummary
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity

import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.customers.data.CustomerInvoiceRow
import com.dailymoneyregister.feature.customers.data.CustomerRepository
import com.dailymoneyregister.feature.customers.data.InvoiceLinkBackfillRepository
import com.dailymoneyregister.feature.invoice.data.InvoiceRepository
import com.dailymoneyregister.feature.invoice.pdf.InvoicePdfRenderer
import com.dailymoneyregister.feature.invoice.pdf.InvoiceSharing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class CustomerListFilter {
    ALL,
    PAYMENTS,
    CREDIT,
    COUPONS,
    INVOICES
}

data class CustomersUiState(
    val isLoading: Boolean = true,
    val customers: List<CustomerSummary> = emptyList(),
    val query: String = "",
    val selectedFilter: CustomerListFilter = CustomerListFilter.ALL,
    val error: UiText? = null,
    /** Older invoices that are not linked to any customer yet. */
    val unlinkedCount: Int = 0,
    val canLinkOlderInvoices: Boolean = false,
    val showLinkConfirm: Boolean = false,
    val isLinking: Boolean = false,
    val linkResult: UiText? = null,
    val linkError: UiText? = null
) {
    /**
     * Read-only Customers projection.
     *
     * BOARDLOCK:
     * - ALL: every customer.
     * - CREDIT: customer has authoritative Credit ledger evidence.
     * - COUPONS: customer has an authoritative Coupon wallet.
     * - INVOICES: customer has at least one linked invoice.
     *
     * Search is applied after the selected category filter.
     * No financial state is recalculated or copied here.
     */
    val visible: List<CustomerSummary>
        get() {
            val filtered =
                when (selectedFilter) {
                    CustomerListFilter.ALL ->
                        customers

                    CustomerListFilter.PAYMENTS ->
                        customers.filter { it.hasPayments }

                    CustomerListFilter.CREDIT ->
                        customers.filter { it.hasCredit }

                    CustomerListFilter.COUPONS ->
                        customers.filter { it.hasCoupon }

                    CustomerListFilter.INVOICES ->
                        customers.filter { it.invoiceCount > 0 }
                }

            val text = query.trim()

            if (text.isEmpty()) {
                return filtered
            }

            val digits = text.filter(Char::isDigit)

            return filtered.filter { customer ->
                customer.name.contains(text, ignoreCase = true) ||
                    (
                        digits.isNotEmpty() &&
                            customer.phone
                                .filter(Char::isDigit)
                                .contains(digits)
                    )
            }
        }

    val showLinkBanner: Boolean
        get() = canLinkOlderInvoices && unlinkedCount > 0
}

@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repository: CustomerRepository,
    private val backfillRepository: InvoiceLinkBackfillRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomersUiState())
    val uiState: StateFlow<CustomersUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun onQueryChange(query: String) {
        if (query.length > 60) return
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun selectFilter(filter: CustomerListFilter) {
        if (_uiState.value.selectedFilter == filter) return

        _uiState.value =
            _uiState.value.copy(
                selectedFilter = filter
            )
    }

    fun reload() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val customers = repository.listCustomers()
                val canLink = InvoiceLinkBackfillRepository.canBackfill()
                val unlinked = if (canLink) countUnlinkedSafely() else 0
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    customers = customers,
                    canLinkOlderInvoices = canLink,
                    unlinkedCount = unlinked
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (denied: PermissionDeniedException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    customers = emptyList(),
                    error = UiText.Resource(R.string.customers_error_no_access)
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = UiText.Resource(
                        R.string.customers_error_generic,
                        listOf(error.message ?: "unknown error")
                    )
                )
            }
        }
    }

    private suspend fun countUnlinkedSafely(): Int = try {
        backfillRepository.countUnlinkedInvoices()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0
    }

    fun askToLinkOlderInvoices() {
        val state = _uiState.value
        if (!state.showLinkBanner || state.isLinking) return
        _uiState.value = state.copy(showLinkConfirm = true, linkResult = null, linkError = null)
    }

    fun dismissLinkConfirm() {
        if (_uiState.value.isLinking) return
        _uiState.value = _uiState.value.copy(showLinkConfirm = false)
    }

    fun confirmLinkOlderInvoices() {
        val state = _uiState.value
        if (state.isLinking) return
        _uiState.value = state.copy(showLinkConfirm = false, isLinking = true, linkError = null)

        viewModelScope.launch {
            try {
                val result = backfillRepository.linkOlderInvoices()
                reload()
                _uiState.value = _uiState.value.copy(
                    isLinking = false,
                    linkResult = UiText.Plural(
                        R.plurals.customers_link_result,
                        result.linked.toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                        listOf(result.linked, result.customersCreated, result.skipped)
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLinking = false,
                    linkError = UiText.Resource(
                        R.string.customers_link_error,
                        listOf(error.message ?: "unknown error")
                    )
                )
            }
        }
    }
}

/** Sum of a customer's invoice totals in one currency. */
data class TotalInvoiced(val minor: Long, val currencyCode: String, val decimalPlaces: Int)

data class CustomerDetailUiState(
    val isLoading: Boolean = true,
    val customer: CustomerEntity? = null,
    val transactions: List<FinancialTransactionView> = emptyList(),
    val expenses: List<com.dailymoneyregister.feature.register.data.ExpenseReadProjection> = emptyList(),
    val creditHistory: List<com.dailymoneyregister.feature.customers.data.CustomerCreditHistoryRow> =
        emptyList(),
    val invoices: List<CustomerInvoiceRow> = emptyList(),
    val notFound: Boolean = false,
    val error: UiText? = null,
    /** The invoice whose share options are open, if any. */
    val selectedInvoice: CustomerInvoiceRow? = null,
    val isPreparingShare: Boolean = false,
    val shareError: UiText? = null,

    // CREDIT_1D_B1_RECEIVE_PAYMENT
    val creditOutstandingMinor: Long = 0L,
    val creditCurrencyCode: String? = null,
    val creditDecimalPlaces: Int = 3,

    // CUSTOMER_WHATSAPP_V1
    // Authoritative business identity captured from the active session.
    val businessName: String? = null,

    // Read-only projection from the authoritative Coupon wallet/ledger.
    val couponWalletDetails: RegisterRepository.CouponWalletDetails? = null,

    val isReceivePaymentOpen: Boolean = false,
    val repaymentAmount: String = "",
    val repaymentMethod: PaymentCategory = PaymentCategory.CASH,

    val isSubmittingRepayment: Boolean = false,
    val repaymentError: String? = null,
    val repaymentSuccess: String? = null,

    /*
     * If execution outcome is uncertain, retain the exact durable
     * command so Retry uses the same operation identity.
     */
    val inFlightRepaymentCommand: CreditRepaymentCommand? = null
) {
    /**
     * Sum of all invoice totals. Null when there are no invoices, when any
     * total could not be read, or when invoices use different currencies.
     */
    val totalInvoiced: TotalInvoiced?
        get() {
            if (invoices.isEmpty()) return null
            if (invoices.any { it.totalMinor == null || it.currencyCode == null || it.decimalPlaces == null }) return null
            val currencies = invoices.map { it.currencyCode }.distinct()
            val places = invoices.map { it.decimalPlaces }.distinct()
            if (currencies.size != 1 || places.size != 1) return null
            val sum = try {
                invoices.fold(0L) { acc, row -> Math.addExact(acc, row.totalMinor!!) }
            } catch (_: ArithmeticException) {
                return null
            }
            return TotalInvoiced(sum, currencies.first()!!, places.first()!!)
        }
}

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    private val repository: CustomerRepository,
    private val invoiceRepository: InvoiceRepository,

    // CREDIT_1D_B1_RECEIVE_PAYMENT
    private val registerRepository: RegisterRepository,
    private val deviceIdentityProvider: DeviceIdentityProvider,

    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val customerId: String? = savedStateHandle.get<String>("customerId")

    private val _uiState = MutableStateFlow(CustomerDetailUiState())
    val uiState: StateFlow<CustomerDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val id = customerId
        if (id == null) {
            _uiState.value = CustomerDetailUiState(isLoading = false, notFound = true)
            return
        }
        _uiState.value = CustomerDetailUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val history = repository.getHistory(id)

                val session =
                    SessionManager.currentSession.value

                val outstanding =
                    if (
                        history != null &&
                        session != null
                    ) {
                        registerRepository
                            .getCustomerCreditOutstanding(
                                history.customer.id,
                                session.branch.currencyCode
                            )
                    } else {
                        0L
                    }

                val couponWalletDetails =
                    if (history != null && session != null) {
                        registerRepository.getCouponWalletDetails(
                            history.customer.id,
                            session.branch.currencyCode
                        )
                    } else {
                        null
                    }

                _uiState.value = if (history == null) {
                    CustomerDetailUiState(isLoading = false, notFound = true)
                } else {
                    CustomerDetailUiState(
                        isLoading = false,
                        customer = history.customer,
                        transactions = history.transactions,
                        expenses = history.expenses,
                        creditHistory = history.creditHistory,
                        invoices = history.invoices,

                        creditOutstandingMinor =
                            outstanding,

                        creditCurrencyCode =
                            session?.branch?.currencyCode,

                        creditDecimalPlaces =
                            session?.branch
                                ?.currencyDecimalPlaces
                                ?: 3,

                        businessName =
                            session
                                ?.takeIf {
                                    history.customer.businessId ==
                                        it.business.id
                                }
                                ?.business
                                ?.name,

                        couponWalletDetails = couponWalletDetails
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (denied: PermissionDeniedException) {
                _uiState.value = CustomerDetailUiState(
                    isLoading = false,
                    error = UiText.Resource(R.string.customers_error_no_access)
                )
            } catch (error: Exception) {
                _uiState.value = CustomerDetailUiState(
                    isLoading = false,
                    error = UiText.Resource(
                        R.string.customers_error_generic,
                        listOf(error.message ?: "unknown error")
                    )
                )
            }
        }
    }

    // CREDIT_1D_B1_RECEIVE_PAYMENT

    fun openReceivePayment() {
        val state = _uiState.value

        if (
            state.customer == null ||
            state.creditOutstandingMinor <= 0L ||
            state.isSubmittingRepayment
        ) {
            return
        }

        _uiState.value =
            state.copy(
                isReceivePaymentOpen = true,
                repaymentAmount = "",
                repaymentMethod = PaymentCategory.CASH,
                repaymentError = null,
                repaymentSuccess = null,
                inFlightRepaymentCommand = null
            )
    }

    fun closeReceivePayment() {
        val state = _uiState.value

        if (state.isSubmittingRepayment) {
            return
        }

        _uiState.value =
            state.copy(
                isReceivePaymentOpen = false,
                repaymentAmount = "",
                repaymentError = null,
                inFlightRepaymentCommand = null
            )
    }

    fun onRepaymentAmountChange(
        value: String
    ) {
        val state = _uiState.value

        if (state.isSubmittingRepayment) {
            return
        }

        if (value.length > 24) {
            return
        }

        if (
            value.isNotEmpty() &&
            !value.matches(
                Regex("""^[0-9]*([.][0-9]*)?$""")
            )
        ) {
            return
        }

        _uiState.value =
            state.copy(
                repaymentAmount = value,
                repaymentError = null,
                repaymentSuccess = null,
                inFlightRepaymentCommand = null
            )
    }

    fun selectRepaymentMethod(
        method: PaymentCategory
    ) {
        val state = _uiState.value

        if (state.isSubmittingRepayment) {
            return
        }

        if (
            method != PaymentCategory.CASH &&
            method != PaymentCategory.ONLINE
        ) {
            return
        }

        _uiState.value =
            state.copy(
                repaymentMethod = method,
                repaymentError = null,
                repaymentSuccess = null,
                inFlightRepaymentCommand = null
            )
    }

    fun confirmReceivePayment() {
        val state = _uiState.value
        val customer =
            state.customer ?: return

        if (
            !state.isReceivePaymentOpen ||
            state.isSubmittingRepayment
        ) {
            return
        }

        val session =
            SessionManager.currentSession.value

        if (session == null) {
            _uiState.value =
                state.copy(
                    repaymentError =
                        "No active session."
                )

            return
        }

        if (
            customer.businessId !=
                session.business.id
        ) {
            _uiState.value =
                state.copy(
                    repaymentError =
                        "Customer does not belong to the active business."
                )

            return
        }

        val amountMinor =
            try {
                MoneyMinorUnits.parse(
                    state.repaymentAmount,
                    state.creditDecimalPlaces
                )
                    .getOrThrow()
                    .amount
            } catch (error: Exception) {
                _uiState.value =
                    state.copy(
                        repaymentError =
                            "Enter a valid repayment amount."
                    )

                return
            }

        if (amountMinor <= 0L) {
            _uiState.value =
                state.copy(
                    repaymentError =
                        "Repayment amount must be greater than zero."
                )

            return
        }

        if (
            amountMinor >
                state.creditOutstandingMinor
        ) {
            _uiState.value =
                state.copy(
                    repaymentError =
                        "Repayment cannot exceed outstanding Credit."
                )

            return
        }

        val category =
            state.repaymentMethod

        val channel =
            when (category) {

                PaymentCategory.CASH ->
                    PaymentChannel.CASH_TENDER

                PaymentCategory.ONLINE ->
                    PaymentChannel.MANUAL_QR

                else -> {
                    _uiState.value =
                        state.copy(
                            repaymentError =
                                "Unsupported repayment method."
                        )

                    return
                }
            }

        /*
         * If the previous attempt returned an uncertain Error,
         * retry the SAME command.
         *
         * Otherwise create a new durable operation identity.
         */
        val command =
            state.inFlightRepaymentCommand
                ?: CreditRepaymentCommand(
                    operationId =
                        OperationId.generate(),

                    businessId =
                        session.business.id,

                    branchId =
                        session.branch.id,

                    actorUserId =
                        session.user.id,

                    repaymentId =
                        IdGenerator.newId(),

                    customerId =
                        customer.id,

                    amountMinor =
                        amountMinor,

                    currencyCode =
                        session.branch.currencyCode,

                    paymentCategory =
                        category,

                    paymentChannel =
                        channel,

                    deviceId =
                        deviceIdentityProvider.deviceId,

                    occurredAt =
                        System.currentTimeMillis()
                )

        _uiState.value =
            state.copy(
                isSubmittingRepayment =
                    true,

                repaymentError =
                    null,

                repaymentSuccess =
                    null,

                inFlightRepaymentCommand =
                    command
            )

        viewModelScope.launch {

            val result =
                try {
                    registerRepository
                        .executeCreditRepayment(
                            command
                        )
                } catch (
                    cancelled:
                        CancellationException
                ) {
                    throw cancelled
                } catch (
                    error:
                        Exception
                ) {
                    CommandResult.Error(
                        error
                    )
                }

            when (result) {

                is CommandResult.Success -> {

                    val refreshedCreditHistory =
                        _uiState.value.customer
                            ?.let { customer ->
                                repository
                                    .getHistory(customer.id)
                                    ?.creditHistory
                            }
                            ?: _uiState.value.creditHistory

                    _uiState.value =
                        _uiState.value.copy(
                            creditHistory =
                                refreshedCreditHistory,
                            creditOutstandingMinor =
                                result.data
                                    .outstandingAfterMinor,

                            isSubmittingRepayment =
                                false,

                            isReceivePaymentOpen =
                                false,

                            repaymentAmount =
                                "",

                            repaymentError =
                                null,

                            repaymentSuccess =
                                "Payment received successfully.",

                            inFlightRepaymentCommand =
                                null
                        )
                }

                is CommandResult.PermissionDenied -> {

                    _uiState.value =
                        _uiState.value.copy(
                            isSubmittingRepayment =
                                false,

                            inFlightRepaymentCommand =
                                null,

                            repaymentError =
                                result.message
                        )
                }

                is CommandResult.Conflict -> {

                    _uiState.value =
                        _uiState.value.copy(
                            isSubmittingRepayment =
                                false,

                            inFlightRepaymentCommand =
                                null,

                            repaymentError =
                                result.message
                        )
                }

                is CommandResult.Error -> {

                    /*
                     * CommandExecutor terminalizes execution failures as FAILED.
                     *
                     * A FAILED Operation ID must never be submitted again.
                     * Keep the durable failed operation for audit history, but
                     * release the UI's in-flight command. A later cashier press
                     * is a new intent and therefore receives a new operationId
                     * and repaymentId.
                     */
                    _uiState.value =
                        _uiState.value.copy(
                            isSubmittingRepayment =
                                false,

                            inFlightRepaymentCommand =
                                null,

                            repaymentError =
                                result.exception.message
                                    ?: "Credit repayment failed. Try the payment again."
                        )
                }
            }
        }
    }

    // ---- Re-sharing an invoice from the history ------------------------------

    fun openInvoice(row: CustomerInvoiceRow) {
        _uiState.value = _uiState.value.copy(selectedInvoice = row, shareError = null)
    }

    fun closeInvoice() {
        if (_uiState.value.isPreparingShare) return
        _uiState.value = _uiState.value.copy(selectedInvoice = null, shareError = null)
    }

    fun sharePdf(context: Context) {
        launchShare(context) { file, _ -> InvoiceSharing.sharePdf(context, file) }
    }

    fun shareWhatsApp(context: Context) {
        launchShare(context) { file, invoice -> InvoiceSharing.shareWhatsApp(context, invoice, file) }
    }

    /**
     * Re-renders the PDF from the invoice's frozen snapshot, so what is shared
     * always matches what was saved. Only invoices linked to THIS customer are shared.
     */
    private fun launchShare(
        context: Context,
        onReady: (java.io.File, com.dailymoneyregister.feature.invoice.data.IssuedInvoice) -> Unit
    ) {
        val row = _uiState.value.selectedInvoice ?: return
        val id = customerId ?: return
        if (_uiState.value.isPreparingShare) return

        _uiState.value = _uiState.value.copy(isPreparingShare = true, shareError = null)
        viewModelScope.launch {
            try {
                val invoice = invoiceRepository.getInvoice(row.documentId)
                if (invoice == null || invoice.document.customerId != id) {
                    _uiState.value = _uiState.value.copy(
                        shareError = UiText.Resource(R.string.customers_share_error, listOf("invoice not found"))
                    )
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    InvoicePdfRenderer.render(context.applicationContext, invoice)
                }
                onReady(file, invoice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    shareError = UiText.Resource(
                        R.string.customers_share_error,
                        listOf(error.message ?: "pdf render failed")
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isPreparingShare = false)
            }
        }
    }
}

