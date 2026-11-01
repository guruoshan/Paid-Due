package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.ExpenseSupportingDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the register's expense supporting-document UI workflow.
 *
 * This coordinator only stages URIs and reads/attaches supporting documents.
 * Expense financial commands remain owned by RegisterViewModel.
 */
internal class RegisterExpenseSupportingDocumentCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val loadSupportingDocuments: suspend (String) -> List<ExpenseSupportingDocument>,
    private val attachSupportingDocument: suspend (String, String) -> Unit
) {
    fun refreshExpenseSupportingDocuments(expenseId: String) {
        if (expenseId.isBlank() || uiState.value.expenseDetails?.expenseId != expenseId) return
        uiState.value = uiState.value.copy(expenseSupportingDocumentsLoading = true)
        scope.launch {
            try {
                val documents = loadSupportingDocuments(expenseId)
                if (uiState.value.expenseDetails?.expenseId == expenseId) {
                    uiState.value = uiState.value.copy(
                        expenseSupportingDocuments = documents,
                        expenseSupportingDocumentsLoading = false
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (uiState.value.expenseDetails?.expenseId == expenseId) {
                    uiState.value = uiState.value.copy(
                        expenseSupportingDocumentsLoading = false,
                        errorMessage = UiText.Dynamic(
                            error.message ?: "Unable to load supporting documents."
                        )
                    )
                }
            }
        }
    }

    fun attachSupportingDocumentToExpense(expenseId: String, sourceUri: String) {
        if (
            expenseId.isBlank() ||
            sourceUri.isBlank() ||
            uiState.value.expenseDetails?.expenseId != expenseId
        ) return

        uiState.value = uiState.value.copy(
            expenseSupportingDocumentAttaching = true,
            errorMessage = null
        )
        scope.launch {
            try {
                attachSupportingDocument(expenseId, sourceUri)
                val documents = loadSupportingDocuments(expenseId)
                if (uiState.value.expenseDetails?.expenseId == expenseId) {
                    uiState.value = uiState.value.copy(
                        expenseSupportingDocuments = documents,
                        expenseSupportingDocumentAttaching = false,
                        infoMessage = UiText.Dynamic("Supporting document attached.")
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (uiState.value.expenseDetails?.expenseId == expenseId) {
                    uiState.value = uiState.value.copy(
                        expenseSupportingDocumentAttaching = false,
                        errorMessage = UiText.Dynamic(
                            "Expense remains saved. Supporting document could not be attached. " +
                                (error.message ?: "")
                        )
                    )
                }
            }
        }
    }

    fun stageExpenseSupportingDocument(sourceUri: String) {
        if (sourceUri.isBlank() || uiState.value.isExecuting) return
        val current = uiState.value.stagedExpenseSupportingDocumentUris
        if (sourceUri in current) return
        uiState.value = uiState.value.copy(
            stagedExpenseSupportingDocumentUris = current + sourceUri
        )
    }

    fun removeStagedExpenseSupportingDocument(sourceUri: String) {
        if (uiState.value.isExecuting) return
        uiState.value = uiState.value.copy(
            stagedExpenseSupportingDocumentUris =
                uiState.value.stagedExpenseSupportingDocumentUris.filterNot { it == sourceUri }
        )
    }
}
