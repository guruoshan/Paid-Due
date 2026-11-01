package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.database.entity.MerchantQrConfigEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.MediaType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.OnlineSaleCommand
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
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
class RegisterSaleExecutionCoordinatorTest {
    @Test
    fun `successful cash sale projects authoritative transaction`() = runTest {
        val state = saleState()
        val completed = transaction()
        var submitted: CashSaleCommand? = null
        val coordinator = coordinator(state, backgroundScope, cash = { command, _, _ ->
            submitted = command
            CommandResult.Success(completed)
        })
        coordinator.executeCashSale()
        runCurrent()
        assertNotNull(submitted)
        assertSame(completed, state.value.lastCompletedTransaction)
        assertNull(state.value.inFlightSaleCommand)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `successful online sale projects transaction and clears payment state`() = runTest {
        val state = saleState().also { it.value = it.value.copy(overlay = RegisterOverlay.OnlinePayment, onlineQr = qr()) }
        val completed = transaction()
        var submitted: OnlineSaleCommand? = null
        val coordinator = coordinator(state, backgroundScope, online = {
            submitted = it
            CommandResult.Success(completed)
        })
        coordinator.executeOnlineSale()
        runCurrent()
        assertNotNull(submitted)
        assertSame(completed, state.value.lastCompletedTransaction)
        assertNull(state.value.inFlightOnlineSaleCommand)
        assertNull(state.value.onlineQr)
        assertNull(state.value.overlay)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `session change during execution rejects stale result`() = runTest {
        val state = saleState()
        val pending = CompletableDeferred<CommandResult<TransactionEntity>>()
        var current = session()
        var generation = 4L
        val coordinator = coordinator(
            state, backgroundScope,
            currentSession = { current },
            generation = { generation },
            cash = { _, _, _ -> pending.await() }
        )
        coordinator.executeCashSale()
        runCurrent()
        val before = state.value
        current = session("branch-2")
        generation++
        pending.complete(CommandResult.Success(transaction()))
        runCurrent()
        assertSame(before, state.value)
        assertTrue(state.value.isExecuting)
        assertNotNull(state.value.inFlightSaleCommand)
        assertNull(state.value.lastCompletedTransaction)
    }

    @Test
    fun `retry preserves cash command identity`() = runTest {
        val state = saleState()
        val submitted = mutableListOf<CashSaleCommand>()
        var attempt = 0
        val coordinator = coordinator(state, backgroundScope, cash = { command, _, _ ->
            submitted += command
            attempt++
            if (attempt == 1) CommandResult.Error(IllegalStateException("uncertain"))
            else CommandResult.Success(transaction())
        })
        coordinator.executeCashSale()
        runCurrent()
        val retained = state.value.inFlightSaleCommand
        coordinator.executeCashSale()
        runCurrent()
        assertEquals(2, submitted.size)
        assertSame(retained, submitted.last())
        assertEquals(submitted.first().operationId, submitted.last().operationId)
        assertEquals(submitted.first().transactionIdVal, submitted.last().transactionIdVal)
    }

    @Test
    fun `retry reuses existing online command`() = runTest {
        val state = saleState()
        val submitted = mutableListOf<OnlineSaleCommand>()
        var attempt = 0
        val coordinator = coordinator(state, backgroundScope, online = { command ->
            submitted += command
            attempt++
            if (attempt == 1) CommandResult.Error(IllegalStateException("uncertain"))
            else CommandResult.Success(transaction())
        })
        coordinator.executeOnlineSale()
        runCurrent()
        val retained = state.value.inFlightOnlineSaleCommand
        coordinator.executeOnlineSale()
        runCurrent()
        assertEquals(2, submitted.size)
        assertSame(retained, submitted.last())
        assertEquals(submitted.first().operationId, submitted.last().operationId)
        assertEquals(submitted.first().transactionIdVal, submitted.last().transactionIdVal)
    }

    @Test
    fun `QR loading failure does not create or submit a transaction`() = runTest {
        val state = saleState()
        var submissions = 0
        val coordinator = coordinator(
            state, backgroundScope,
            loadQr = { error("QR unavailable") },
            online = {
                submissions++
                CommandResult.Success(transaction())
            }
        )
        coordinator.openOnlinePayment()
        runCurrent()
        assertEquals(0, submissions)
        assertNull(state.value.onlineQr)
        assertEquals(UiText.Dynamic("QR unavailable"), state.value.errorMessage)
        assertEquals(RegisterOverlay.OnlinePayment, state.value.overlay)
        assertNull(state.value.lastCompletedTransaction)
    }

    @Test
    fun `online payment cancellation closes overlay and clears QR`() = runTest {
        val state = saleState().also { it.value = it.value.copy(overlay = RegisterOverlay.OnlinePayment, onlineQr = qr()) }
        val coordinator = coordinator(state, backgroundScope)
        coordinator.cancelOnlinePayment()
        assertNull(state.value.overlay)
        assertNull(state.value.onlineQr)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `cash conflict preserves command and response message`() = runTest {
        val state = saleState()
        val coordinator = coordinator(state, backgroundScope, cash = { _, _, _ ->
            CommandResult.Conflict("Sale conflict")
        })
        coordinator.executeCashSale()
        runCurrent()
        assertEquals(UiText.Dynamic("Sale conflict"), state.value.errorMessage)
        assertNotNull(state.value.inFlightSaleCommand)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `online permission denial preserves command and response message`() = runTest {
        val state = saleState()
        val coordinator = coordinator(state, backgroundScope, online = {
            CommandResult.PermissionDenied("Online sale denied")
        })
        coordinator.executeOnlineSale()
        runCurrent()
        assertEquals(UiText.Dynamic("Online sale denied"), state.value.errorMessage)
        assertNotNull(state.value.inFlightOnlineSaleCommand)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun `sale success remains authoritative in result projection`() = runTest {
        val state = saleState()
        val committed = transaction()
        val coordinator = coordinator(state, backgroundScope, cash = { _, _, _ ->
            CommandResult.Success(committed)
        })
        coordinator.executeCashSale()
        runCurrent()
        assertSame(committed, state.value.lastCompletedTransaction)
        assertNull(state.value.inFlightSaleCommand)
        assertFalse(state.value.isExecuting)
    }

    private fun coordinator(
        state: MutableStateFlow<RegisterUiState>,
        scope: kotlinx.coroutines.CoroutineScope,
        currentSession: () -> ActiveSession? = { session() },
        generation: () -> Long = { 1L },
        cash: suspend (CashSaleCommand, UserAccountEntity, com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity?) -> CommandResult<TransactionEntity> =
            { _, _, _ -> CommandResult.Success(transaction()) },
        loadQr: suspend (String) -> MerchantQrSelection? = { qr() },
        online: suspend (OnlineSaleCommand) -> CommandResult<TransactionEntity> =
            { CommandResult.Success(transaction()) }
    ) = RegisterSaleExecutionCoordinator(
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
        submitCashSale = cash,
        loadOnlineQr = loadQr,
        submitOnlineSale = online
    )

    private fun saleState() = MutableStateFlow(
        RegisterUiState(draftState = DraftState(manualAmount = MoneyMinorUnits(1_000)))
    )

    private fun qr(): MerchantQrSelection {
        val media = MediaAssetEntity(
            id = "qr-media-1",
            businessId = "business-1",
            branchId = "branch-1",
            mediaType = MediaType.MERCHANT_QR,
            localReference = "/tmp/qr.png",
            createdAt = 1L
        )
        val config = MerchantQrConfigEntity(
            id = "qr-config-1",
            businessId = "business-1",
            branchId = "branch-1",
            displayLabel = "Merchant",
            bankAppName = "Bank",
            mediaId = media.id,
            isDefault = true,
            isActive = true,
            createdAt = 1L
        )
        return MerchantQrSelection(config, media)
    }

    private fun transaction() = TransactionEntity(
        id = "sale-transaction-1",
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
        displayRef = "S-1"
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
