package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Credit sale modal and its durable submission lifecycle.
 *
 * RegisterViewModel remains the owner of RegisterUiState and financial-session
 * synchronization. Customer selection and draft composition remain separate.
 * Invoice details are communication-only evidence loaded after the authoritative
 * sale submission; a failed read never changes or retries the sale.
 */
internal class RegisterCreditSaleCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val currentSession: () -> ActiveSession?,
    private val synchronizeFinancialSession: (ActiveSession?) -> Boolean,
    private val financialContext: () -> RegisterFinancialSessionContext?,
    private val financialSessionGeneration: () -> Long,
    private val isFinancialSessionCurrent: (
        RegisterFinancialSessionContext?,
        ActiveSession?,
        Long,
        Long
    ) -> Boolean,
    private val deviceId: () -> String,
    private val submitCreditSale: suspend (CreditSaleCommand) ->
        CommandResult<com.dailymoneyregister.core.database.entity.TransactionEntity>,
    private val loadCreditInvoice: suspend (CreditSaleCommand) ->
        RegisterRepository.CreditInvoiceDetails
) {
    fun openCreditSale() {
        val state = uiState.value
        if (state.isExecuting) return

        if (state.draftState.grandTotal.amount <= 0L) {
            uiState.value = state.copy(
                errorMessage = UiText.Dynamic("Enter a sale amount before selecting Credit.")
            )
            return
        }

        uiState.value = state.copy(
            overlay = RegisterOverlay.CreditSale,
            errorMessage = null
        )
    }

    fun closeCreditSale() {
        val state = uiState.value
        if (state.overlay != RegisterOverlay.CreditSale || state.isExecuting) return

        uiState.value = state.copy(
            overlay = null,
            errorMessage = null
        )
    }

    fun confirmCreditSale(
        customer: CustomerEntity?,
        name: String,
        phone: String
    ) {
        val sessionSnapshot = currentSession()
        val sessionChanged = synchronizeFinancialSession(sessionSnapshot)
        val state = uiState.value

        if (
            sessionSnapshot == null ||
            sessionChanged ||
            state.overlay != RegisterOverlay.CreditSale ||
            state.isExecuting ||
            state.lastCompletedTransaction != null
        ) return

        if (!state.draftState.grandTotal.isPositive) {
            uiState.value = state.copy(
                errorMessage = UiText.Dynamic("Enter a sale amount before confirming Credit.")
            )
            return
        }

        val resolvedName = customer?.name?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim()
        val resolvedPhone = customer?.canonicalPhone?.trim()?.takeIf { it.isNotEmpty() } ?: phone.trim()

        if (resolvedName.isBlank() || resolvedPhone.isBlank()) {
            uiState.value = state.copy(
                errorMessage = UiText.Dynamic("Customer name and phone number are required.")
            )
            return
        }

        val command = state.inFlightCreditSaleCommand ?: CreditSaleCommand(
            operationId = OperationId.generate(),
            businessId = sessionSnapshot.business.id,
            branchId = sessionSnapshot.branch.id,
            actorUserId = sessionSnapshot.user.id,
            transactionIdVal = TransactionId.generate(),
            customerPhone = resolvedPhone,
            customerName = resolvedName,
            draftState = state.draftState,
            currencyCode = sessionSnapshot.branch.currencyCode,
            currencyDecimalPlaces = sessionSnapshot.branch.currencyDecimalPlaces,
            deviceId = deviceId(),
            occurredAt = System.currentTimeMillis()
        )

        val executionContext = financialContext()
        val executionGeneration = financialSessionGeneration()

        uiState.value = state.copy(
            isExecuting = true,
            inFlightCreditSaleCommand = command,
            errorMessage = null,
            infoMessage = null
        )

        scope.launch {
            val result = try {
                submitCreditSale(command)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                CommandResult.Error(error)
            }

            if (
                !isFinancialSessionCurrent(
                    executionContext,
                    currentSession(),
                    executionGeneration,
                    financialSessionGeneration()
                )
            ) return@launch

            when (result) {
                is CommandResult.Success -> {
                    // Submission is authoritative. Invoice loading is a
                    // read-only, best-effort projection and cannot cause a
                    // second submission or reinterpret the completed sale.
                    val invoice = try {
                        loadCreditInvoice(command)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }

                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        overlay = null,
                        inFlightCreditSaleCommand = null,
                        couponCustomers = emptyList(),
                        couponSearching = false,
                        creditSaleShareName = command.customerName,
                        creditSaleSharePhone = command.customerPhone,
                        creditInvoicePurchaseOccurredAt = invoice?.purchaseOccurredAt,
                        creditInvoiceGivenMinor = invoice?.creditGivenMinor,
                        creditInvoicePurchaseOutstandingMinor = invoice?.purchaseOutstandingMinor,
                        creditInvoiceTotalOutstandingMinor = invoice?.totalOutstandingMinor,
                        creditInvoiceCurrencyCode = invoice?.currencyCode,
                        creditInvoiceDecimalPlaces = invoice?.currencyDecimalPlaces,
                        lastCompletedTransaction = result.data,
                        errorMessage = null
                    )
                }

                is CommandResult.PermissionDenied -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        inFlightCreditSaleCommand = null,
                        errorMessage = UiText.Dynamic(result.message)
                    )
                }

                is CommandResult.Conflict -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Dynamic(result.message)
                    )
                }

                is CommandResult.Error -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        // Preserve the exact durable command after an
                        // uncertain result so a retry reuses its identity.
                        inFlightCreditSaleCommand = command,
                        errorMessage = UiText.Dynamic(
                            result.exception.message ?: "Credit sale failed. Retry the same sale."
                        )
                    )
                }
            }
        }
    }
}
