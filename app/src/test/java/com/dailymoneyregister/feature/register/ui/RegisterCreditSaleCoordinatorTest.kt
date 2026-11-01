package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
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
class RegisterCreditSaleCoordinatorTest {
    @Test
    fun `successful credit sale projects authoritative transaction and invoice`() = runTest {
        val state = creditState()
        var submitted: CreditSaleCommand? = null
        var invoiceLoads = 0
        val coordinator = coordinator(state, backgroundScope, submit = {
            submitted = it
            CommandResult.Success(transaction())
        }, loadInvoice = {
            invoiceLoads++
            invoice()
        })

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()

        assertNotNull(submitted)
        assertEquals("Customer", state.value.creditSaleShareName)
        assertEquals("+96890000000", state.value.creditSaleSharePhone)
        assertEquals(1_000L, state.value.creditInvoiceGivenMinor)
        assertEquals(transaction(), state.value.lastCompletedTransaction)
        assertEquals(1, invoiceLoads)
        assertFalse(state.value.isExecuting)
        assertNull(state.value.inFlightCreditSaleCommand)
        assertNull(state.value.overlay)
    }

    @Test
    fun `validation failure does not submit a credit sale`() = runTest {
        val state = creditState()
        var submissions = 0
        val coordinator = coordinator(state, backgroundScope, submit = {
            submissions++
            CommandResult.Success(transaction())
        })

        coordinator.confirmCreditSale(null, " ", "")
        runCurrent()

        assertEquals(0, submissions)
        assertFalse(state.value.isExecuting)
        assertEquals(
            UiText.Dynamic("Customer name and phone number are required."),
            state.value.errorMessage
        )
    }

    @Test
    fun `session change during execution rejects stale result projection`() = runTest {
        val state = creditState()
        val pending = CompletableDeferred<CommandResult<TransactionEntity>>()
        var current = session()
        var generation = 3L
        val coordinator = coordinator(
            state, backgroundScope,
            currentSession = { current },
            generation = { generation },
            submit = { pending.await() }
        )

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()
        val before = state.value
        current = session("branch-2")
        generation++
        pending.complete(CommandResult.Success(transaction()))
        runCurrent()

        assertSame(before, state.value)
        assertTrue(state.value.isExecuting)
        assertNotNull(state.value.inFlightCreditSaleCommand)
        assertNull(state.value.lastCompletedTransaction)
    }

    @Test
    fun `uncertain failure retains exact command for retry`() = runTest {
        val state = creditState()
        val submitted = mutableListOf<CreditSaleCommand>()
        val coordinator = coordinator(state, backgroundScope, submit = {
            submitted += it
            CommandResult.Error(IllegalStateException("network uncertain"))
        })

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()
        val retained = state.value.inFlightCreditSaleCommand

        assertNotNull(retained)
        assertSame(retained, submitted.single())
        assertEquals(
            UiText.Dynamic("network uncertain"),
            state.value.errorMessage
        )
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `retry reuses same command identity and does not create a new operation id`() = runTest {
        val state = creditState()
        val submitted = mutableListOf<CreditSaleCommand>()
        var attempt = 0
        val coordinator = coordinator(state, backgroundScope, submit = {
            submitted += it
            attempt++
            if (attempt == 1) CommandResult.Error(IllegalStateException("timeout"))
            else CommandResult.Success(transaction())
        })

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()
        val first = submitted.single()
        coordinator.confirmCreditSale(null, "Different name", "+96891111111")
        runCurrent()

        assertEquals(2, submitted.size)
        assertSame(first, submitted.last())
        assertEquals(first.operationId, submitted.last().operationId)
        assertEquals(first.transactionIdVal, submitted.last().transactionIdVal)
    }

    @Test
    fun `invoice loading failure never retries or reverses successful sale`() = runTest {
        val state = creditState()
        var submissions = 0
        var invoiceLoads = 0
        val completed = transaction()
        val coordinator = coordinator(state, backgroundScope, submit = {
            submissions++
            CommandResult.Success(completed)
        }, loadInvoice = {
            invoiceLoads++
            error("invoice evidence unavailable")
        })

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()

        assertEquals(1, submissions)
        assertEquals(1, invoiceLoads)
        assertSame(completed, state.value.lastCompletedTransaction)
        assertNull(state.value.inFlightCreditSaleCommand)
        assertNull(state.value.creditInvoiceGivenMinor)
        assertEquals("Customer", state.value.creditSaleShareName)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `conflict response preserves message and existing command handling`() = runTest {
        val state = creditState()
        val coordinator = coordinator(state, backgroundScope, submit = {
            CommandResult.Conflict("Credit sale conflict")
        })

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()

        assertEquals(UiText.Dynamic("Credit sale conflict"), state.value.errorMessage)
        assertNotNull(state.value.inFlightCreditSaleCommand)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `permission denied response clears command and preserves message`() = runTest {
        val state = creditState()
        val coordinator = coordinator(state, backgroundScope, submit = {
            CommandResult.PermissionDenied("Credit permission denied")
        })

        coordinator.confirmCreditSale(null, "Customer", "+96890000000")
        runCurrent()

        assertEquals(UiText.Dynamic("Credit permission denied"), state.value.errorMessage)
        assertNull(state.value.inFlightCreditSaleCommand)
        assertFalse(state.value.isExecuting)
    }

    private fun coordinator(
        state: MutableStateFlow<RegisterUiState>,
        scope: kotlinx.coroutines.CoroutineScope,
        currentSession: () -> ActiveSession? = { session() },
        generation: () -> Long = { 1L },
        submit: suspend (CreditSaleCommand) -> CommandResult<TransactionEntity>,
        loadInvoice: suspend (CreditSaleCommand) -> RegisterRepository.CreditInvoiceDetails = { invoice() }
    ) = RegisterCreditSaleCoordinator(
        uiState = state,
        scope = scope,
        currentSession = currentSession,
        synchronizeFinancialSession = { false },
        financialContext = { currentSession()?.toRegisterFinancialSessionContext() },
        financialSessionGeneration = generation,
        isFinancialSessionCurrent = { context, current, executionGeneration, currentGeneration ->
            shouldApplyFinancialResultToRegister(context, current, executionGeneration, currentGeneration)
        },
        deviceId = { "test-device" },
        submitCreditSale = submit,
        loadCreditInvoice = loadInvoice
    )

    private fun creditState() = MutableStateFlow(
        RegisterUiState(
            overlay = RegisterOverlay.CreditSale,
            draftState = DraftState(manualAmount = MoneyMinorUnits(1_000))
        )
    )

    private fun invoice() = RegisterRepository.CreditInvoiceDetails(
        customerId = "customer-1",
        customerName = "Customer",
        customerPhone = "+96890000000",
        transactionId = "credit-transaction-1",
        purchaseOccurredAt = 10L,
        creditGivenMinor = 1_000L,
        purchaseOutstandingMinor = 1_000L,
        totalOutstandingMinor = 2_000L,
        currencyCode = "OMR",
        currencyDecimalPlaces = 3
    )

    private fun transaction() = TransactionEntity(
        id = "credit-transaction-1",
        branchId = "branch-1",
        type = TransactionType.SALE,
        subtotalMinor = 1_000,
        taxRateBps = 0,
        taxableBaseMinor = 1_000,
        taxAmountMinor = 0,
        grandTotalMinor = 1_000,
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        createdByUserId = "admin-1",
        deviceId = "test-device",
        createdAt = 10L,
        displayRef = "C-1"
    )

    private fun session(branchId: String = "branch-1"): ActiveSession {
        val business = BusinessEntity("business-1", "Business", createdAt = 1L, updatedAt = 1L)
        val branch = BranchEntity(
            id = branchId,
            businessId = business.id,
            name = "Branch",
            code = "BR",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 1L,
            updatedAt = 1L
        )
        val user = UserAccountEntity(
            id = "admin-1",
            businessId = business.id,
            displayName = "Admin",
            username = "admin",
            role = UserRole.ADMIN,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 1L,
            updatedAt = 1L
        )
        return ActiveSession(business = business, branch = branch, user = user, membership = null)
    }
}
