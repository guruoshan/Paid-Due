package com.dailymoneyregister.feature.register.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterDetailsSessionCoordinatorTest {

    @Test
    fun `closing expense details invalidates an outstanding read`() = runTest {
        val result = CompletableDeferred<com.dailymoneyregister.feature.register.data.ExpenseReadProjection?>()
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            RegisterUiState(destination = RegisterDestination.History)
        )
        val coordinator = RegisterDetailsSessionCoordinator(
            uiState = state,
            scope = backgroundScope,
            loadExpenseDetails = { result.await() },
            loadTransactionDetails = { null },
            isFinancialSessionCurrent = { _, _ -> true }
        )

        coordinator.openExpenseDetails("expense-1", null, 0L)
        runCurrent()
        assertTrue(state.value.expenseDetailsLoading)

        coordinator.closeExpenseDetails()
        result.complete(null)
        runCurrent()

        assertEquals(RegisterDestination.History, state.value.destination)
        assertFalse(state.value.expenseDetailsLoading)
        assertEquals(null, state.value.expenseDetails)
    }

    @Test
    fun `financial session invalidation prevents stale expense result from applying`() = runTest {
        val result = CompletableDeferred<com.dailymoneyregister.feature.register.data.ExpenseReadProjection?>()
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            RegisterUiState(destination = RegisterDestination.History)
        )
        val coordinator = RegisterDetailsSessionCoordinator(
            uiState = state,
            scope = backgroundScope,
            loadExpenseDetails = { result.await() },
            loadTransactionDetails = { null },
            isFinancialSessionCurrent = { _, _ -> true }
        )

        coordinator.openExpenseDetails("expense-1", null, 0L)
        runCurrent()
        coordinator.invalidateForFinancialSessionChange()
        result.complete(null)
        runCurrent()

        assertEquals(
            RegisterDestination.ExpenseDetails("expense-1", DetailsOrigin.HISTORY),
            state.value.destination
        )
        assertTrue(state.value.expenseDetailsLoading)
    }

    @Test
    fun `closing transaction details returns to the original destination`() = runTest {
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            RegisterUiState(destination = RegisterDestination.History)
        )
        val coordinator = RegisterDetailsSessionCoordinator(
            uiState = state,
            scope = backgroundScope,
            loadExpenseDetails = { null },
            loadTransactionDetails = { null },
            isFinancialSessionCurrent = { _, _ -> true }
        )

        coordinator.openTransactionDetails("transaction-1", null, 0L)
        runCurrent()

        assertEquals(
            RegisterDestination.History,
            state.value.destination
        )
        assertFalse(state.value.transactionDetailsLoading)
    }

    @Test
    fun `session validator rejects a completed transaction read`() = runTest {
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            RegisterUiState(destination = RegisterDestination.History)
        )
        val coordinator = RegisterDetailsSessionCoordinator(
            uiState = state,
            scope = backgroundScope,
            loadExpenseDetails = { null },
            loadTransactionDetails = { null },
            isFinancialSessionCurrent = { _, _ -> false }
        )

        coordinator.openTransactionDetails("transaction-1", null, 0L)
        runCurrent()

        assertEquals(
            RegisterDestination.TransactionDetails("transaction-1", DetailsOrigin.HISTORY),
            state.value.destination
        )
        assertTrue(state.value.transactionDetailsLoading)
    }
}
