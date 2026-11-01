package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.ExpenseCommandFactory
import com.dailymoneyregister.feature.register.data.ExpenseCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Owns expense entry execution and the register's today-expense projection.
 *
 * RegisterViewModel remains the owner of financial-session synchronization and
 * the shared RegisterUiState. Supporting documents and expense details retain
 * their existing coordinators and are invoked through narrow callbacks.
 */
internal class RegisterExpenseExecutionCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val currentSession: () -> ActiveSession?,
    private val financialContext: () -> RegisterFinancialSessionContext?,
    private val financialSessionGeneration: () -> Long,
    private val synchronizeFinancialSession: (ActiveSession?) -> Boolean,
    private val isFinancialSessionCurrent: (
        RegisterFinancialSessionContext?,
        ActiveSession?,
        Long,
        Long
    ) -> Boolean,
    private val decimalPlaces: () -> Int,
    private val deviceId: () -> String,
    private val submitExpense: suspend (ExpenseCommand) -> CommandResult<ExpenseCommandResult>,
    private val loadExpensesForPeriod: suspend (String, String, Long, Long) -> List<ExpenseEntity>,
    private val attachExpenseSupportingDocument: suspend (String, String) -> Unit,
    private val openExpenseDetails: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun openExpenses(session: ActiveSession) {
        if (uiState.value.isExecuting) return

        uiState.value = uiState.value.copy(
            overlay = RegisterOverlay.Expense,
            expenseLoading = true,
            todayExpenses = emptyList(),
            errorMessage = null
        )

        refreshTodayExpenses(session)
    }

    fun closeExpenses() {
        if (uiState.value.overlay != RegisterOverlay.Expense || uiState.value.isExecuting) return

        uiState.value = uiState.value.copy(
            overlay = null,
            expenseLoading = false,
            todayExpenses = emptyList(),
            inFlightExpenseCommand = null,
            errorMessage = null
        )
    }

    fun refreshTodayExpenses(session: ActiveSession? = currentSession()) {
        if (session == null) {
            uiState.value = uiState.value.copy(expenseLoading = false)
            return
        }

        val generation = financialSessionGeneration()
        val context = financialContext()

        scope.launch {
            try {
                val zone = ZoneId.of(session.branch.timezoneId)
                val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
                val fromInclusive = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val toExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val expenses = loadExpensesForPeriod(
                    session.business.id,
                    session.branch.id,
                    fromInclusive,
                    toExclusive
                )

                if (
                    !isFinancialSessionCurrent(
                        context,
                        currentSession(),
                        generation,
                        financialSessionGeneration()
                    )
                ) {
                    return@launch
                }

                uiState.value = uiState.value.copy(
                    expenseLoading = false,
                    todayExpenses = expenses
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error

                uiState.value = uiState.value.copy(
                    expenseLoading = false,
                    errorMessage = UiText.Dynamic(
                        error.message ?: "Unable to load Expenses."
                    )
                )
            }
        }
    }

    fun saveExpense(
        session: ActiveSession,
        amountText: String,
        category: ExpenseCategory,
        paymentCategory: com.dailymoneyregister.core.model.enums.PaymentCategory,
        payeeName: String,
        payeePhone: String?,
        note: String,
        reference: String
    ) {
        val state = uiState.value
        if (state.overlay != RegisterOverlay.Expense || state.isExecuting) return

        val parsedAmount = MoneyMinorUnits.parse(
            amountText.trim(),
            decimalPlaces()
        ).getOrNull()

        if (parsedAmount == null) {
            uiState.value = state.copy(
                errorMessage = UiText.Dynamic(
                    "Enter a valid amount using no more than " +
                        "${decimalPlaces()} decimal places."
                )
            )
            return
        }

        if (!parsedAmount.isPositive) {
            uiState.value = state.copy(
                errorMessage = UiText.Dynamic("Enter an Expense amount greater than zero.")
            )
            return
        }

        val command = state.inFlightExpenseCommand ?: try {
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
                occurredAt = now()
            )
        } catch (_: IllegalStateException) {
            uiState.value = state.copy(
                errorMessage = UiText.Dynamic("Expense payment must be Cash or Online.")
            )
            return
        }

        val executionContext = financialContext()
        val executionGeneration = financialSessionGeneration()

        uiState.value = state.copy(
            isExecuting = true,
            inFlightExpenseCommand = command,
            errorMessage = null
        )

        scope.launch {
            val result = submitExpense(command)

            if (
                !isFinancialSessionCurrent(
                    executionContext,
                    currentSession(),
                    executionGeneration,
                    financialSessionGeneration()
                )
            ) {
                return@launch
            }

            when (result) {
                is CommandResult.Success -> {
                    val stagedDocuments = uiState.value.stagedExpenseSupportingDocumentUris
                    var attachmentFailureCount = 0

                    for (sourceUri in stagedDocuments) {
                        try {
                            attachExpenseSupportingDocument(command.expenseId, sourceUri)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            attachmentFailureCount++
                        }
                    }

                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        inFlightExpenseCommand = null,
                        stagedExpenseSupportingDocumentUris = emptyList(),
                        infoMessage = UiText.Dynamic(
                            when {
                                attachmentFailureCount > 0 ->
                                    "Expense saved. $attachmentFailureCount supporting document(s) could not be attached."
                                result.isIdempotentReplay -> "Expense already saved."
                                else -> "Expense saved."
                            }
                        ),
                        errorMessage = null
                    )

                    refreshTodayExpenses()
                    openExpenseDetails(command.expenseId)
                }

                is CommandResult.Conflict ->
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        inFlightExpenseCommand = null,
                        errorMessage = UiText.Dynamic(result.message)
                    )

                is CommandResult.PermissionDenied ->
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        inFlightExpenseCommand = null,
                        errorMessage = UiText.Dynamic(result.message)
                    )

                is CommandResult.Error ->
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        inFlightExpenseCommand = command,
                        errorMessage = UiText.Dynamic(
                            result.exception.message
                                ?: "Expense could not be saved. Retry the same Expense."
                        )
                    )
            }
        }
    }
}
