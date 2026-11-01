package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.ExpenseCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterExpenseExecutionCoordinatorTest {
    @Test
    fun `valid expense save succeeds`() = runTest {
        val state = expenseState()
        var submitted: ExpenseCommand? = null
        val coordinator = coordinator(state, backgroundScope, submit = {
            submitted = it
            CommandResult.Success(result(it))
        })

        coordinator.saveExpense(session(), "12.500", ExpenseCategory.SUPPLIES,
            PaymentCategory.CASH, "Payee", null, "note", "ref")
        runCurrent()

        assertNotNull(submitted)
        assertEquals(12_500L, submitted!!.amountMinor)
        assertEquals(false, state.value.isExecuting)
        assertNull(state.value.inFlightExpenseCommand)
        assertEquals("Expense saved.", (state.value.infoMessage as UiText.Dynamic).value)
    }

    @Test
    fun `invalid amount is rejected without submitting`() = runTest {
        val state = expenseState()
        var submissions = 0
        val coordinator = coordinator(state, backgroundScope, submit = {
            submissions++
            CommandResult.Success(result(it))
        })

        coordinator.saveExpense(session(), "1.2345", ExpenseCategory.SUPPLIES,
            PaymentCategory.CASH, "", null, "", "")
        runCurrent()

        assertEquals(0, submissions)
        assertFalse(state.value.isExecuting)
        assertNotNull(state.value.errorMessage)
    }

    @Test
    fun `session change during submission invalidates result`() = runTest {
        val state = expenseState()
        val pending = CompletableDeferred<CommandResult<ExpenseCommandResult>>()
        var current = session()
        var generation = 4L
        var openedDetails = false
        val coordinator = coordinator(
            state, backgroundScope,
            currentSession = { current },
            generation = { generation },
            submit = { pending.await() },
            openDetails = { openedDetails = true }
        )

        coordinator.saveExpense(current, "2.000", ExpenseCategory.OTHER,
            PaymentCategory.CASH, "", null, "", "")
        runCurrent()
        current = session(branchId = "branch-2")
        generation++
        pending.complete(CommandResult.Success(result(state.value.inFlightExpenseCommand!!)))
        runCurrent()

        assertTrue(state.value.isExecuting)
        assertFalse(openedDetails)
        assertNotNull(state.value.inFlightExpenseCommand)
    }

    @Test
    fun `uncertain failure preserves same command for retry`() = runTest {
        val state = expenseState()
        val submitted = mutableListOf<ExpenseCommand>()
        var attempts = 0
        val coordinator = coordinator(state, backgroundScope, submit = {
            submitted += it
            attempts++
            if (attempts == 1) CommandResult.Error(IllegalStateException("network uncertain"))
            else CommandResult.Success(result(it))
        })

        coordinator.saveExpense(session(), "4.000", ExpenseCategory.OTHER,
            PaymentCategory.CASH, "", null, "", "")
        runCurrent()
        val retained = state.value.inFlightExpenseCommand
        assertNotNull(retained)
        assertSame(retained, submitted.first())

        coordinator.saveExpense(session(), "999.000", ExpenseCategory.RENT,
            PaymentCategory.ONLINE, "different", null, "", "")
        runCurrent()

        assertEquals(2, submitted.size)
        assertSame(retained, submitted.last())
        assertNull(state.value.inFlightExpenseCommand)
    }

    @Test
    fun `attachment failure is reported without undoing successful expense`() = runTest {
        val state = expenseState().also {
            it.value = it.value.copy(stagedExpenseSupportingDocumentUris = listOf("content://doc/1"))
        }
        var opened = false
        val coordinator = coordinator(
            state, backgroundScope,
            submit = { CommandResult.Success(result(it)) },
            attach = { _, _ -> error("attachment unavailable") },
            openDetails = { opened = true }
        )

        coordinator.saveExpense(session(), "5.000", ExpenseCategory.OTHER,
            PaymentCategory.CASH, "", null, "", "")
        runCurrent()

        assertFalse(state.value.isExecuting)
        assertNull(state.value.inFlightExpenseCommand)
        assertTrue((state.value.infoMessage as UiText.Dynamic).value.contains("Expense saved. 1 supporting document"))
        assertTrue(opened)
    }

    @Test
    fun `successful save refreshes today's expenses`() = runTest {
        val state = expenseState()
        var refreshCalls = 0
        val coordinator = coordinator(
            state, backgroundScope,
            submit = { CommandResult.Success(result(it)) },
            loadExpenses = { _, _, from, to ->
                refreshCalls++
                assertTrue(from < to)
                emptyList()
            }
        )

        coordinator.saveExpense(session(), "6.000", ExpenseCategory.OTHER,
            PaymentCategory.CASH, "", null, "", "")
        runCurrent()

        assertEquals(1, refreshCalls)
        assertFalse(state.value.expenseLoading)
    }

    @Test
    fun `successful save opens expense details`() = runTest {
        val state = expenseState()
        var openedId: String? = null
        val coordinator = coordinator(
            state, backgroundScope,
            submit = { CommandResult.Success(result(it)) },
            openDetails = { openedId = it }
        )

        coordinator.saveExpense(session(), "7.000", ExpenseCategory.OTHER,
            PaymentCategory.CASH, "", null, "", "")
        runCurrent()

        assertEquals("expense-1", openedId)
    }

    @Test
    fun `supporting document staging workflow remains independently available`() = runTest {
        val state = MutableStateFlow(RegisterUiState())
        val attached = mutableListOf<Pair<String, String>>()
        val documents = RegisterExpenseSupportingDocumentCoordinator(
            uiState = state,
            scope = backgroundScope,
            loadSupportingDocuments = { emptyList() },
            attachSupportingDocument = { expenseId, uri -> attached += expenseId to uri }
        )

        documents.stageExpenseSupportingDocument("content://doc/2")
        assertEquals(listOf("content://doc/2"), state.value.stagedExpenseSupportingDocumentUris)
        documents.attachSupportingDocumentToExpense("expense-2", "content://doc/2")
        runCurrent()

        assertEquals(listOf("expense-2" to "content://doc/2"), attached)
    }

    private fun coordinator(
        state: MutableStateFlow<RegisterUiState>,
        scope: kotlinx.coroutines.CoroutineScope,
        currentSession: () -> ActiveSession? = { session() },
        generation: () -> Long = { 1L },
        submit: suspend (ExpenseCommand) -> CommandResult<ExpenseCommandResult>,
        loadExpenses: suspend (String, String, Long, Long) -> List<com.dailymoneyregister.core.database.entity.ExpenseEntity> = { _, _, _, _ -> emptyList() },
        attach: suspend (String, String) -> Unit = { _, _ -> },
        openDetails: (String) -> Unit = {}
    ) = RegisterExpenseExecutionCoordinator(
        uiState = state,
        scope = scope,
        currentSession = currentSession,
        financialContext = { currentSession()?.toRegisterFinancialSessionContext() },
        financialSessionGeneration = generation,
        isFinancialSessionCurrent = { context, current, executionGeneration, currentGeneration ->
            shouldApplyFinancialResultToRegister(context, current, executionGeneration, currentGeneration)
        },
        decimalPlaces = { 3 },
        deviceId = { "test-device" },
        submitExpense = submit,
        loadExpensesForPeriod = loadExpenses,
        attachExpenseSupportingDocument = attach,
        openExpenseDetails = openDetails,
        now = { 1_735_689_600_000L }
    )

    private fun expenseState() = MutableStateFlow(
        RegisterUiState(overlay = RegisterOverlay.Expense)
    )

    private fun result(command: ExpenseCommand) = ExpenseCommandResult(
        expenseId = command.expenseId,
        operationId = command.operationId.value,
        amountMinor = command.amountMinor,
        category = command.category,
        paymentCategory = command.paymentCategory,
        paymentChannel = command.paymentChannel,
        actorUserId = command.actorUserId,
        cashShiftId = "shift-1",
        occurredAt = command.occurredAt
    )

    private fun session(branchId: String = "branch-1"): ActiveSession {
        val business = BusinessEntity(
            id = "business-1", name = "Business", createdAt = 1L, updatedAt = 1L
        )
        val branch = BranchEntity(
            id = branchId, businessId = business.id, name = "Branch", code = "BR",
            countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
            currencySymbol = "OMR", timezoneId = "Asia/Muscat", createdAt = 1L, updatedAt = 1L
        )
        val user = UserAccountEntity(
            id = "admin-1", businessId = business.id, displayName = "Admin",
            username = "admin", role = UserRole.ADMIN, authType = AuthType.PIN,
            credentialHash = "hash", credentialSalt = "salt", createdAt = 1L, updatedAt = 1L
        )
        return ActiveSession(business = business, branch = branch, user = user, membership = null)
    }
}
