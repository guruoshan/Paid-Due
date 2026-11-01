package com.dailymoneyregister.feature.register.ui

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class RegisterExpenseSupportingDocumentCoordinatorTest {
    @Test
    fun stagingIgnoresBlankAndDuplicateUris() = runTest {
        val state = MutableStateFlow(RegisterUiState())
        val coordinator = RegisterExpenseSupportingDocumentCoordinator(
            uiState = state,
            scope = this,
            loadSupportingDocuments = { emptyList() },
            attachSupportingDocument = { _, _ -> }
        )

        coordinator.stageExpenseSupportingDocument("")
        coordinator.stageExpenseSupportingDocument("content://expense/1")
        coordinator.stageExpenseSupportingDocument("content://expense/1")

        assertEquals(
            listOf("content://expense/1"),
            state.value.stagedExpenseSupportingDocumentUris
        )
    }

    @Test
    fun stagedUrisCannotChangeWhileExpenseIsExecuting() = runTest {
        val state = MutableStateFlow(RegisterUiState(isExecuting = true))
        val coordinator = RegisterExpenseSupportingDocumentCoordinator(
            uiState = state,
            scope = this,
            loadSupportingDocuments = { emptyList() },
            attachSupportingDocument = { _, _ -> }
        )

        coordinator.stageExpenseSupportingDocument("content://expense/1")
        coordinator.removeStagedExpenseSupportingDocument("content://expense/1")

        assertEquals(emptyList<String>(), state.value.stagedExpenseSupportingDocumentUris)
    }

    @Test
    fun removingStagedUriLeavesOtherUrisInOrder() = runTest {
        val state = MutableStateFlow(
            RegisterUiState(
                stagedExpenseSupportingDocumentUris = listOf(
                    "content://expense/1",
                    "content://expense/2",
                    "content://expense/3"
                )
            )
        )
        val coordinator = RegisterExpenseSupportingDocumentCoordinator(
            uiState = state,
            scope = this,
            loadSupportingDocuments = { emptyList() },
            attachSupportingDocument = { _, _ -> }
        )

        coordinator.removeStagedExpenseSupportingDocument("content://expense/2")

        assertEquals(
            listOf("content://expense/1", "content://expense/3"),
            state.value.stagedExpenseSupportingDocumentUris
        )
    }
}
