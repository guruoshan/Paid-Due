package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns read-only transaction details navigation sessions.
 *
 * It preserves the existing transaction session-generation and destination guards.
 */
internal class RegisterDetailsSessionCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val loadTransactionDetails: suspend (String) -> TransactionDetailsReadModel?,
    private val isFinancialSessionCurrent: (
        RegisterFinancialSessionContext?,
        Long
    ) -> Boolean
) {
    private var transactionRequestGeneration: Long = 0

    fun invalidateForFinancialSessionChange() {
        transactionRequestGeneration++
    }

    fun loadSaleDetailsForWhatsApp(
        transactionId: String,
        onLoaded: (TransactionDetailsReadModel?) -> Unit
    ) {
        if (transactionId.isBlank()) {
            onLoaded(null)
            return
        }

        scope.launch {
            val details = try {
                loadTransactionDetails(transactionId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            onLoaded(details)
        }
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
