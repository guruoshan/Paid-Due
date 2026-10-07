package com.dailymoneyregister.feature.register.ui

import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.domain.KeypadKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterPresentationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: RegisterViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionManager.setSession(testSession())
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        SessionManager.clearSession()
        Dispatchers.resetMain()
    }

    @Test
    fun `opening an overlay replaces the previous overlay without clearing draft`() {
        viewModel = model()
        viewModel.onKeypadPress(KeypadKey.Digit('7'))
        val draft = viewModel.uiState.value.draftState

        viewModel.toggleItemizedBillDialog(true)
        viewModel.toggleTaxDialog(true)

        assertEquals(RegisterOverlay.Tax, viewModel.uiState.value.overlay)
        assertEquals(draft, viewModel.uiState.value.draftState)
    }

    @Test
    fun `child overlay closes to itemized workflow and keeps form state`() {
        viewModel = model()
        viewModel.onManualDescriptionChanged("keep this draft")
        viewModel.toggleItemizedBillDialog(true)
        viewModel.toggleAddItemDialog(true)

        assertEquals(
            RegisterOverlay.AddItem(returnToItemizedBill = true),
            viewModel.uiState.value.overlay
        )

        viewModel.toggleAddItemDialog(false)

        assertEquals(RegisterOverlay.ItemizedBill, viewModel.uiState.value.overlay)
        assertEquals("keep this draft", viewModel.uiState.value.manualDescription)
    }

    @Test
    fun `register history and both details routes preserve register draft and back origin`() =
        runTest(dispatcher) {
            viewModel = model()
            viewModel.onKeypadPress(KeypadKey.Digit('4'))
            val draft = viewModel.uiState.value.draftState

            viewModel.openHistory()
            assertEquals(RegisterDestination.History, viewModel.uiState.value.destination)

            viewModel.openTransactionDetails("tx-1")
            assertEquals(
                RegisterDestination.TransactionDetails("tx-1", DetailsOrigin.HISTORY),
                viewModel.uiState.value.destination
            )
            viewModel.closeTransactionDetails()
            assertEquals(RegisterDestination.History, viewModel.uiState.value.destination)

            viewModel.openExpenseDetails("expense-1")
            assertEquals(
                RegisterDestination.ExpenseDetails("expense-1", DetailsOrigin.HISTORY),
                viewModel.uiState.value.destination
            )
            viewModel.closeExpenseDetails()
            assertEquals(RegisterDestination.History, viewModel.uiState.value.destination)

            viewModel.closeHistory()
            assertEquals(RegisterDestination.Register, viewModel.uiState.value.destination)
            assertEquals(draft, viewModel.uiState.value.draftState)
        }

    @Test
    fun `overlay changes preserve an in flight financial command identity`() = runTest(dispatcher) {
        val completion = CompletableDeferred<CommandResult<com.dailymoneyregister.core.database.entity.TransactionEntity>>()
        viewModel = model(executeSale = { completion.await() })
        viewModel.onKeypadPress(KeypadKey.Digit('9'))

        viewModel.executeCashSale()
        runCurrent()
        val command = viewModel.uiState.value.inFlightSaleCommand
        assertNotNull(command)

        viewModel.toggleTaxDialog(true)

        assertSame(command, viewModel.uiState.value.inFlightSaleCommand)
        assertEquals(RegisterOverlay.Tax, viewModel.uiState.value.overlay)

        completion.complete(CommandResult.Error(IllegalStateException("retry")))
        runCurrent()
        assertSame(command, viewModel.uiState.value.inFlightSaleCommand)
        assertNull(viewModel.uiState.value.lastCompletedTransaction)
    }

    private fun model(
        executeSale: suspend () -> CommandResult<com.dailymoneyregister.core.database.entity.TransactionEntity> = {
            error("Sale must not run")
        }
    ): RegisterViewModel =
        com.dailymoneyregister.feature.register.ui.testRegisterViewModel(
            observeToday = { _, _ -> flowOf(emptyList()) },
            executeSale = { _, _, _ -> executeSale() },
            deviceId = { "device" },
            loadTransactionDetails = { null },
            loadExpenseDetails = { null }
        )

    private fun testSession(): ActiveSession {
        val business = BusinessEntity("business", "Business", 1L, 1L)
        val branch = BranchEntity(
            id = "branch",
            businessId = business.id,
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 1L,
            updatedAt = 1L
        )
        val user = UserAccountEntity(
            id = "user",
            businessId = business.id,
            displayName = "Cashier",
            username = "cashier",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 1L,
            updatedAt = 1L
        )
        return ActiveSession(business, branch, user, null)
    }
}
