package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.OnlineSaleCommand
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns cash and online sale submission lifecycles.
 *
 * RegisterViewModel remains the owner of RegisterUiState and financial-session
 * synchronization. Customer selection and draft composition remain separate.
 * Durable commands are retained in RegisterUiState and reused for uncertain retries.
 */
internal class RegisterSaleExecutionCoordinator(
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
    private val submitCashSale: suspend (
        CashSaleCommand,
        UserAccountEntity,
        UserBranchMembershipEntity?
    ) -> CommandResult<TransactionEntity>,
    private val loadOnlineQr: suspend (String) -> MerchantQrSelection?,
    private val submitOnlineSale: suspend (OnlineSaleCommand) -> CommandResult<TransactionEntity>
) {
    fun executeCashSale() {
        if (uiState.value.overlay is RegisterOverlay.Coupon || uiState.value.isExecuting) return
        val sessionSnapshot = currentSession()
        val sessionChanged = synchronizeFinancialSession(sessionSnapshot)
        val state = uiState.value

        if (sessionSnapshot == null) {
            uiState.value = state.copy(errorMessage = UiText.Resource(R.string.sale_session_missing))
            return
        }
        // This Cash press must never execute money entered under the old context.
        if (sessionChanged) return
        val branch = sessionSnapshot.branch
        val user = sessionSnapshot.user
        val membership = sessionSnapshot.membership
        val executionContext = financialContext()
        val executionGeneration = financialSessionGeneration()

        if (!state.draftState.grandTotal.isPositive) {
            uiState.value = state.copy(errorMessage = UiText.Resource(R.string.sale_total_zero))
            return
        }

        // Reuse the durable command on retry; otherwise freeze the current sale intent.
        val command = state.inFlightSaleCommand ?: run {
            val now = System.currentTimeMillis()
            CashSaleCommand(
                operationId = OperationId.generate(),
                businessId = sessionSnapshot.business.id,
                branchId = branch.id,
                actorUserId = user.id,
                transactionIdVal = TransactionId.generate(),
                draftState = state.draftState,
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = deviceId(),
                occurredAt = now,
                customerName = state.saleCustomerName.trim().ifBlank { null },
                customerPhone = state.saleCustomerPhone.trim().ifBlank { null }
            )
        }

        uiState.value = state.copy(isExecuting = true, inFlightSaleCommand = command, errorMessage = null)

        scope.launch {
            val result = submitCashSale(command, user, membership)
            if (!isFinancialSessionCurrent(
                    executionContext,
                    currentSession(),
                    executionGeneration,
                    financialSessionGeneration()
                )
            ) return@launch
            when (result) {
                is CommandResult.Success -> {
                    // The committed transaction is authoritative; project its exact result.
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        inFlightSaleCommand = null,
                        lastCompletedTransaction = result.data
                    )
                }
                is CommandResult.Conflict -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Dynamic(result.message)
                    )
                }
                is CommandResult.PermissionDenied -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Dynamic(result.message)
                    )
                }
                is CommandResult.Error -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = result.exception.message?.let { UiText.Dynamic(it) }
                            ?: UiText.Resource(R.string.cash_sale_failed)
                    )
                }
            }
        }
    }

    fun openOnlinePayment() {
        if (uiState.value.overlay is RegisterOverlay.Coupon || uiState.value.isExecuting) return
        val session = currentSession()
        val state = uiState.value
        if (session == null) {
            uiState.value = state.copy(errorMessage = UiText.Resource(R.string.sale_session_missing))
            return
        }
        if (!state.draftState.grandTotal.isPositive) {
            uiState.value = state.copy(errorMessage = UiText.Resource(R.string.sale_total_zero))
            return
        }
        uiState.value = state.copy(
            overlay = RegisterOverlay.OnlinePayment,
            onlineQr = null,
            errorMessage = null
        )
        scope.launch {
            try {
                val qr = loadOnlineQr(session.branch.id)
                if (uiState.value.overlay != RegisterOverlay.OnlinePayment) return@launch
                uiState.value = uiState.value.copy(onlineQr = qr, errorMessage = null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // QR is a prerequisite for online payment; failure never submits a sale.
                if (uiState.value.overlay == RegisterOverlay.OnlinePayment) {
                    uiState.value = uiState.value.copy(
                        onlineQr = null,
                        errorMessage = UiText.Dynamic(error.message ?: "Could not load online payment QR.")
                    )
                }
            }
        }
    }

    fun cancelOnlinePayment() {
        if (uiState.value.isExecuting) return
        if (uiState.value.overlay != RegisterOverlay.OnlinePayment) return
        uiState.value = uiState.value.copy(overlay = null, onlineQr = null)
    }

    fun executeOnlineSale() {
        val sessionSnapshot = currentSession()
        val sessionChanged = synchronizeFinancialSession(sessionSnapshot)
        val state = uiState.value
        if (sessionSnapshot == null || sessionChanged || state.isExecuting) return
        if (!state.draftState.grandTotal.isPositive) return

        val executionContext = financialContext()
        val executionGeneration = financialSessionGeneration()
        val command = state.inFlightOnlineSaleCommand ?: OnlineSaleCommand(
            operationId = OperationId.generate(),
            businessId = sessionSnapshot.business.id,
            branchId = sessionSnapshot.branch.id,
            actorUserId = sessionSnapshot.user.id,
            transactionIdVal = TransactionId.generate(),
            draftState = state.draftState,
            currencyCode = sessionSnapshot.branch.currencyCode,
            currencyDecimalPlaces = sessionSnapshot.branch.currencyDecimalPlaces,
            deviceId = deviceId(),
            occurredAt = System.currentTimeMillis(),
            customerName = state.saleCustomerName.trim().ifBlank { null },
            customerPhone = state.saleCustomerPhone.trim().ifBlank { null }
        )
        uiState.value = state.copy(
            isExecuting = true,
            inFlightOnlineSaleCommand = command,
            errorMessage = null
        )
        scope.launch {
            val result = submitOnlineSale(command)
            if (!isFinancialSessionCurrent(
                    executionContext,
                    currentSession(),
                    executionGeneration,
                    financialSessionGeneration()
                )
            ) return@launch
            when (result) {
                is CommandResult.Success -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    overlay = null,
                    onlineQr = null,
                    inFlightOnlineSaleCommand = null,
                    lastCompletedTransaction = result.data
                )
                is CommandResult.Conflict -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.PermissionDenied -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.Error -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Online sale failed.")
                )
            }
        }
    }
}
