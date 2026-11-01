package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns read-only transaction and expense details navigation sessions.
 *
 * It preserves the existing session-generation and destination guards. It does
 * not execute or modify financial operations.
 */
internal class RegisterDetailsSessionCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val loadExpenseDetails: suspend (String) -> ExpenseReadProjection?,
    private val loadTransactionDetails: suspend (String) -> TransactionDetailsReadModel?,
    private val isFinancialSessionCurrent: (
        RegisterFinancialSessionContext?,
        Long
    ) -> Boolean
) {
    private var expenseRequestGeneration: Long = 0
    private var transactionRequestGeneration: Long = 0

    fun invalidateForFinancialSessionChange() {
        expenseRequestGeneration++
        transactionRequestGeneration++
    }

    fun openHistory() {
        uiState.value = uiState.value.copy(
            destination = RegisterDestination.History,
            overlay = null,
            transactionDetailsLoading = false,
            transactionDetails = null,
            expenseDetailsLoading = false,
            expenseDetails = null
        )
    }

    fun openExpenseDetails(
        expenseId: String,
        financialContext: RegisterFinancialSessionContext?,
        financialGeneration: Long
    ) {
        if (expenseId.isBlank()) return

        expenseRequestGeneration++
        val requestGeneration = expenseRequestGeneration
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
                requestGeneration != expenseRequestGeneration ||
                (uiState.value.destination as? RegisterDestination.ExpenseDetails)
                    ?.expenseId != expenseId ||
                !isFinancialSessionCurrent(financialContext, financialGeneration)
            ) return@launch

            val destination =
                uiState.value.destination as RegisterDestination.ExpenseDetails
            uiState.value = if (details == null) {
                uiState.value.copy(
                    destination = destination.backDestination(),
                    expenseDetailsLoading = false,
                    expenseDetails = null
                )
            } else {
                uiState.value.copy(
                    expenseDetailsLoading = false,
                    expenseDetails = details
                )
            }
        }
    }

    fun closeExpenseDetails() {
        expenseRequestGeneration++
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

    fun openTransactionDetails(
        transactionId: String,
        financialContext: RegisterFinancialSessionContext?,
        financialGeneration: Long
    ) {
        if (transactionId.isBlank()) return

        transactionRequestGeneration++
        val requestGeneration = transactionRequestGeneration
        val origin = uiState.value.destination.detailsOrigin()
        uiState.value = uiState.value.copy(
            destination = RegisterDestination.TransactionDetails(transactionId, origin),
            overlay = null,
            transactionDetailsLoading = true,
            transactionDetails = null,
            expenseDetailsLoading = false,
            expenseDetails = null
        )

        scope.launch {
            val details = try {
                loadTransactionDetails(transactionId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }

            if (
                requestGeneration != transactionRequestGeneration ||
                (uiState.value.destination as? RegisterDestination.TransactionDetails)
                    ?.transactionId != transactionId ||
                !isFinancialSessionCurrent(financialContext, financialGeneration)
            ) return@launch

            val destination =
                uiState.value.destination as RegisterDestination.TransactionDetails
            uiState.value = if (details == null) {
                uiState.value.copy(
                    destination = destination.backDestination(),
                    transactionDetailsLoading = false,
                    transactionDetails = null
                )
            } else {
                uiState.value.copy(
                    transactionDetailsLoading = false,
                    transactionDetails = details
                )
            }
        }
    }

    fun closeTransactionDetails() {
        transactionRequestGeneration++
        val destination = uiState.value.destination
        if (destination !is RegisterDestination.TransactionDetails) return
        uiState.value = uiState.value.copy(
            destination = destination.backDestination(),
            transactionDetailsLoading = false,
            transactionDetails = null
        )
    }
}
