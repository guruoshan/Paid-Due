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
    fun `closing transaction details invalidates an outstanding read`() = runTest {
        val result = CompletableDeferred<com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel?>()
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            RegisterUiState(destination = RegisterDestination.History)
        )
        val coordinator = RegisterDetailsSessionCoordinator(
            uiState = state,
            scope = backgroundScope,
            loadTransactionDetails = { result.await() },
            isFinancialSessionCurrent = { _, _ -> true }
        )

        coordinator.openTransactionDetails("transaction-1", null, 0L)
        runCurrent()
        assertTrue(state.value.transactionDetailsLoading)

        coordinator.closeTransactionDetails()
        result.complete(null)
        runCurrent()

        assertEquals(RegisterDestination.History, state.value.destination)
        assertFalse(state.value.transactionDetailsLoading)
        assertEquals(null, state.value.transactionDetails)
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
