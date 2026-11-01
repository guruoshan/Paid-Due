package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.ExpenseLifecycleCommand
import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the expense-details UI session and its correction/void actions.
 * Shared financial-session synchronization remains owned by RegisterViewModel.
 */
internal class RegisterExpenseDetailsCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val loadExpenseDetails: suspend (String) -> ExpenseReadProjection?,
    private val currentSession: () -> ActiveSession?,
    private val decimalPlaces: () -> Int,
    private val deviceId: () -> String,
    private val submitExpenseLifecycle: suspend (ExpenseLifecycleCommand) -> CommandResult<com.dailymoneyregister.core.database.entity.ExpenseEntity>,
    private val isFinancialSessionCurrent: (RegisterFinancialSessionContext?, Long) -> Boolean
) {
    private var requestGeneration: Long = 0

    fun invalidateForFinancialSessionChange() {
        requestGeneration++
    }

    fun openExpenseDetails(
        expenseId: String,
        financialContext: RegisterFinancialSessionContext?,
        financialGeneration: Long
    ) {
        if (expenseId.isBlank()) return

        requestGeneration++
        val request = requestGeneration
        val origin = uiState.value.destination.detailsOrigin()
        uiState.value = uiState.value.copy(
            destination = RegisterDestination.ExpenseDetails(expenseId, origin),
            overlay = null,
            expenseDetailsLoading = true,
            expenseDetails = null,
            transactionDetailsLoading = false,
            transactionDetails = null
        )

        scope.launch {
            val details = try {
                loadExpenseDetails(expenseId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }

            if (
                request != requestGeneration ||
                (uiState.value.destination as? RegisterDestination.ExpenseDetails)?.expenseId != expenseId ||
                !isFinancialSessionCurrent(financialContext, financialGeneration)
            ) return@launch

            val destination = uiState.value.destination as RegisterDestination.ExpenseDetails
            uiState.value = if (details == null) {
                uiState.value.copy(
                    destination = destination.backDestination(),
                    expenseDetailsLoading = false,
                    expenseDetails = null
                )
            } else {
                uiState.value.copy(expenseDetailsLoading = false, expenseDetails = details)
            }
        }
    }

    fun closeExpenseDetails() {
        requestGeneration++
        val destination = uiState.value.destination
        if (destination !is RegisterDestination.ExpenseDetails) return
        uiState.value = uiState.value.copy(
            destination = destination.backDestination(),
            expenseDetailsLoading = false,
            expenseDetails = null,
            expenseSupportingDocuments = emptyList(),
            expenseSupportingDocumentsLoading = false,
            expenseSupportingDocumentAttaching = false
        )
    }

    fun correctExpense(correctedAmountInput: String, reason: String) =
        submitExpenseLifecycle(ExpenseLifecycleCommand.Action.CORRECT, correctedAmountInput, reason)

    fun voidExpense(reason: String) =
        submitExpenseLifecycle(ExpenseLifecycleCommand.Action.VOID, null, reason)

    private fun submitExpenseLifecycle(
        action: ExpenseLifecycleCommand.Action,
        correctedAmountInput: String?,
        reason: String
    ) {
        val session = currentSession() ?: return
        if (session.user.role != UserRole.ADMIN) {
            uiState.value = uiState.value.copy(
                errorMessage = UiText.Dynamic("Only Admin accounts can correct or void expenses.")
            )
            return
        }
        val details = uiState.value.expenseDetails ?: return
        if (uiState.value.isExecuting) return
        val normalizedReason = reason.trim()
        if (normalizedReason.isBlank()) {
            uiState.value = uiState.value.copy(
                errorMessage = UiText.Dynamic("Enter a reason for this expense change.")
            )
            return
        }
        val correctedAmount = if (action == ExpenseLifecycleCommand.Action.CORRECT) {
            correctedAmountInput?.let { MoneyMinorUnits.parse(it, decimalPlaces()).getOrNull()?.amount }
        } else null
        if (
            action == ExpenseLifecycleCommand.Action.CORRECT &&
            (correctedAmount == null || correctedAmount <= 0L || correctedAmount == details.amount.amount)
        ) {
            uiState.value = uiState.value.copy(
                errorMessage = UiText.Dynamic("Enter a positive amount different from the current expense amount.")
            )
            return
        }
        val command = ExpenseLifecycleCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = maxOf(System.currentTimeMillis(), details.occurredAt + 1L),
            expenseId = details.expenseId,
            action = action,
            correctedAmountMinor = correctedAmount,
            reason = normalizedReason
        )
        uiState.value = uiState.value.copy(isExecuting = true, errorMessage = null, infoMessage = null)
        scope.launch {
            when (val result = submitExpenseLifecycle(command)) {
                is CommandResult.Success -> {
                    val refreshed = runCatching { loadExpenseDetails(details.expenseId) }.getOrNull()
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        expenseDetails = refreshed ?: uiState.value.expenseDetails,
                        infoMessage = UiText.Dynamic(
                            if (action == ExpenseLifecycleCommand.Action.CORRECT) {
                                "Expense correction saved and queued for sync."
                            } else {
                                "Expense void saved and queued for sync."
                            }
                        ),
                        errorMessage = null
                    )
                }
                is CommandResult.PermissionDenied -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.Conflict -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message)
                )
                is CommandResult.Error -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Expense lifecycle operation failed.")
                )
            }
        }
    }
}
