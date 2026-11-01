package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterExpenseDetailsCoordinatorTest {

    @Test
    fun `opening expense details initializes session and loads projection`() = runTest {
        val result = CompletableDeferred<ExpenseReadProjection?>()
        val state = MutableStateFlow(RegisterUiState(destination = RegisterDestination.History))
        val coordinator = coordinator(state, backgroundScope) { result.await() }

        coordinator.openExpenseDetails("expense-1", null, 0L)
        runCurrent()
        assertTrue(state.value.expenseDetailsLoading)
        assertEquals(
            RegisterDestination.ExpenseDetails("expense-1", DetailsOrigin.HISTORY),
            state.value.destination
        )

        result.complete(null)
        runCurrent()
        assertEquals(RegisterDestination.History, state.value.destination)
        assertFalse(state.value.expenseDetailsLoading)
    }

    @Test
    fun `closing expense details resets state and invalidates pending read`() = runTest {
        val result = CompletableDeferred<ExpenseReadProjection?>()
        val state = MutableStateFlow(RegisterUiState(destination = RegisterDestination.History))
        val coordinator = coordinator(state, backgroundScope) { result.await() }

        coordinator.openExpenseDetails("expense-1", null, 0L)
        runCurrent()
        coordinator.closeExpenseDetails()
        result.complete(null)
        runCurrent()

        assertEquals(RegisterDestination.History, state.value.destination)
        assertFalse(state.value.expenseDetailsLoading)
        assertEquals(null, state.value.expenseDetails)
        assertTrue(state.value.expenseSupportingDocuments.isEmpty())
        assertFalse(state.value.expenseSupportingDocumentsLoading)
        assertFalse(state.value.expenseSupportingDocumentAttaching)
    }

    @Test
    fun `financial session invalidation prevents stale expense read from applying`() = runTest {
        val result = CompletableDeferred<ExpenseReadProjection?>()
        val state = MutableStateFlow(RegisterUiState(destination = RegisterDestination.History))
        val coordinator = coordinator(state, backgroundScope) { result.await() }

        coordinator.openExpenseDetails("expense-1", null, 0L)
        runCurrent()
        coordinator.invalidateForFinancialSessionChange()
        result.complete(null)
        runCurrent()

        assertTrue(state.value.expenseDetailsLoading)
        assertEquals(
            RegisterDestination.ExpenseDetails("expense-1", DetailsOrigin.HISTORY),
            state.value.destination
        )
    }

    @Test
    fun `blank expense id cannot create a detail session`() = runTest {
        val state = MutableStateFlow(RegisterUiState(destination = RegisterDestination.History))
        val coordinator = coordinator(state, backgroundScope) { null }

        coordinator.openExpenseDetails(" ", null, 0L)
        runCurrent()

        assertEquals(RegisterDestination.History, state.value.destination)
        assertFalse(state.value.expenseDetailsLoading)
    }

    private fun coordinator(
        state: MutableStateFlow<RegisterUiState>,
        scope: CoroutineScope,
        load: suspend () -> ExpenseReadProjection?
    ) = RegisterExpenseDetailsCoordinator(
        uiState = state,
        scope = scope,
        loadExpenseDetails = { load() },
        currentSession = { null },
        decimalPlaces = { 3 },
        deviceId = { "test-device" },
        submitExpenseLifecycle = {
            CommandResult.Error(UnsupportedOperationException("not used in session tests"))
        },
        isFinancialSessionCurrent = { _, _ -> true }
    )
}
