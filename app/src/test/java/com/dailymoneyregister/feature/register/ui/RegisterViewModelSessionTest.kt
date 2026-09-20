package com.dailymoneyregister.feature.register.ui

import androidx.lifecycle.ViewModelStore
import com.dailymoneyregister.R
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.*
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.KeypadKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var nextViewModelId = 0
    private val a = session("branch-a", "cashier-a")
    private val b = session("branch-b", "cashier-b", "INR", 2, 725, TaxRoundingMode.FLOOR)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionManager.setSession(a)
    }

    @After
    fun tearDown() {
        store.clear()
        SessionManager.clearSession()
        Dispatchers.resetMain()
    }

    @Test
    fun initialAndSameContextDoNotResetOrAdvanceGeneration() = runTest {
        val vm = viewModel()
        assertNull(vm.uiState.value.infoMessage)
        populate(vm)
        val before = vm.uiState.value
        runCurrent()
        // Non-financial metadata and permissions must not discard the draft.
        SessionManager.setSession(a.copy(
            branch = a.branch.copy(name = "Renamed", timezoneId = "UTC"),
            membership = a.membership!!.copy(canAcceptCash = false)
        ))
        runCurrent()
        assertSame(before, vm.uiState.value)
        assertEquals(0L, vm.financialSessionGeneration)
    }

    @Test
    fun branchObserverClearsEveryOldUiFieldAndLoadsNewDefaults() = runTest {
        val vm = viewModel { _, _, _ -> CommandResult.Error(IllegalStateException("old error")) }
        populate(vm)
        vm.executeCashSale()
        runCurrent()
        assertNotNull(vm.uiState.value.inFlightSaleCommand)
        assertNotNull(vm.uiState.value.errorMessage)
        SessionManager.setSession(b)
        runCurrent()
        assertReset(vm, 725, TaxRoundingMode.FLOOR)
        assertEquals(1L, vm.financialSessionGeneration)
        assertEquals(UiText.Resource(R.string.register_financial_session_reset), vm.uiState.value.infoMessage)
    }

    @Test
    fun cashierSwitchAndReturnAdvanceGenerationAndClearDraft() = runTest {
        val vm = viewModel()
        populate(vm)
        SessionManager.setSession(a.copy(user = b.user, membership = null))
        runCurrent()
        assertReset(vm, 500, TaxRoundingMode.HALF_UP)
        assertEquals(1L, vm.financialSessionGeneration)
        vm.onKeypadPress(KeypadKey.Digit('8'))
        SessionManager.setSession(a)
        runCurrent()
        assertReset(vm, 500, TaxRoundingMode.HALF_UP)
        assertEquals(2L, vm.financialSessionGeneration)
    }

    @Test
    fun businessCurrencyPrecisionAndTaxChangesEachReset() = runTest {
        val vm = viewModel()
        val changed = listOf(
            a.copy(business = a.business.copy(id = "other-business")),
            a.copy(branch = a.branch.copy(currencyCode = "INR")),
            a.copy(branch = a.branch.copy(currencyDecimalPlaces = 2)),
            a.copy(branch = a.branch.copy(defaultTaxRateBps = 725)),
            a.copy(branch = a.branch.copy(taxRoundingMode = TaxRoundingMode.FLOOR))
        )
        changed.forEachIndexed { index, session ->
            vm.onKeypadPress(KeypadKey.Digit('5'))
            SessionManager.setSession(session)
            runCurrent()
            assertEquals(MoneyMinorUnits.ZERO, vm.uiState.value.draftState.grandTotal)
            assertEquals((index + 1).toLong(), vm.financialSessionGeneration)
            assertEquals(session.branch.defaultTaxRateBps, vm.uiState.value.draftState.taxRateBps)
            assertEquals(session.branch.taxRoundingMode, vm.uiState.value.draftState.taxRoundingMode)
        }
    }

    @Test
    fun logoutResetsSilentlyAndRepeatedNullDoesNothing() = runTest {
        val vm = viewModel()
        populate(vm)
        SessionManager.clearSession()
        runCurrent()
        assertReset(vm, 0, TaxRoundingMode.HALF_UP)
        assertNull(vm.uiState.value.infoMessage)
        assertEquals(1L, vm.financialSessionGeneration)
        SessionManager.clearSession()
        runCurrent()
        assertEquals(1L, vm.financialSessionGeneration)
        vm.executeCashSale()
        assertEquals(UiText.Resource(R.string.sale_session_missing), vm.uiState.value.errorMessage)
    }

    @Test
    fun synchronousKeypadRaceRebuildsPrecisionBeforeProcessingInput() = runTest {
        val vm = viewModel()
        vm.onKeypadPress(KeypadKey.Digit('9'))
        SessionManager.setSession(b)
        // Deliberately do not run the queued session collector.
        vm.onKeypadPress(KeypadKey.Digit('1'))
        assertEquals("1", vm.uiState.value.calculatorState.expression)
        assertEquals(MoneyMinorUnits(100), vm.uiState.value.draftState.manualAmount)
        assertEquals(1L, vm.financialSessionGeneration)
        assertEquals(UiText.Resource(R.string.register_financial_session_reset), vm.uiState.value.infoMessage)
        vm.onKeypadPress(KeypadKey.Decimal)
        "234".forEach { vm.onKeypadPress(KeypadKey.Digit(it)) }
        assertEquals("1.23", vm.uiState.value.calculatorState.currentInput)
        assertEquals(MoneyMinorUnits(123), vm.uiState.value.draftState.manualAmount)
        runCurrent()
        assertEquals(1L, vm.financialSessionGeneration)
    }

    @Test
    fun shippingAndItemParsingUseNewBoundPrecisionBeforeCollectorRuns() = runTest {
        val shippingVm = viewModel()
        shippingVm.onKeypadPress(KeypadKey.Digit('9'))
        SessionManager.setSession(b)
        shippingVm.updateTxShippingPacking("1.25", "2.50")
        assertEquals(MoneyMinorUnits.ZERO, shippingVm.uiState.value.draftState.manualAmount)
        assertEquals(MoneyMinorUnits(125), shippingVm.uiState.value.draftState.txShipping)
        assertEquals(MoneyMinorUnits(250), shippingVm.uiState.value.draftState.txPacking)

        SessionManager.setSession(a)
        val itemVm = viewModel()
        itemVm.onKeypadPress(KeypadKey.Digit('9'))
        SessionManager.setSession(b)
        itemVm.addItem("Item", "1.25", 2, packingText = "0.10", shippingText = "0.20")
        val draft = itemVm.uiState.value.draftState
        assertEquals(MoneyMinorUnits.ZERO, draft.manualAmount)
        assertEquals(MoneyMinorUnits(125), draft.items.single().unitPrice)
        assertEquals(MoneyMinorUnits(20), draft.itemPackingTotal)
        assertEquals(MoneyMinorUnits(40), draft.itemShippingTotal)
        assertEquals(MoneyMinorUnits(310), draft.grandTotal)
    }

    @Test
    fun everyOtherDraftMutationSynchronizesBeforeItCanCarryOldMoney() = runTest {
        val actions: List<(RegisterViewModel) -> Unit> = listOf(
            { it.onManualDescriptionChanged("new note") },
            { it.setTaxEnabled(true) },
            { it.updateTaxRatePercent("8") },
            { it.removeItem("missing") },
            { it.updateItemQuantity("missing", 3) },
            { it.clearDraft() },
            { it.startNewSale() }
        )
        actions.forEach { action ->
            SessionManager.setSession(a)
            val vm = viewModel()
            populate(vm)
            SessionManager.setSession(b)
            action(vm)
            assertEquals(1L, vm.financialSessionGeneration)
            assertEquals(CalculatorState(), vm.uiState.value.calculatorState)
            assertEquals(MoneyMinorUnits.ZERO, vm.uiState.value.draftState.grandTotal)
            assertTrue(vm.uiState.value.draftState.items.isEmpty())
            assertTrue(vm.uiState.value.isRecentTransactionsExpanded)
        }
    }

    @Test
    fun cashPressDuringContextChangeFailsClosedWithoutAdmittingOldDraft() = runTest {
        var calls = 0
        val vm = viewModel { _, _, _ -> calls++; CommandResult.Conflict("unexpected") }
        vm.onKeypadPress(KeypadKey.Digit('9'))
        SessionManager.setSession(b)
        vm.executeCashSale()
        runCurrent()
        assertEquals(0, calls)
        assertEquals(1L, vm.financialSessionGeneration)
        assertEquals(MoneyMinorUnits.ZERO, vm.uiState.value.draftState.grandTotal)
        assertNull(vm.uiState.value.inFlightSaleCommand)
        assertFalse(vm.uiState.value.isExecuting)
        vm.executeCashSale()
        assertEquals(UiText.Resource(R.string.sale_total_zero), vm.uiState.value.errorMessage)
    }

    @Test
    fun admittedCashKeepsFrozenIdentityEvenWhenSessionChangesBeforeCoroutineStarts() = runTest {
        var admitted: CashSaleCommand? = null
        val vm = viewModel { command, user, membership ->
            admitted = command
            assertSame(a.user, user)
            assertSame(a.membership, membership)
            CommandResult.Success(transaction())
        }
        vm.onKeypadPress(KeypadKey.Digit('9'))
        vm.executeCashSale()
        SessionManager.setSession(b)
        runCurrent()
        assertNotNull(admitted)
        assertEquals("business-1", admitted!!.businessId)
        assertEquals("branch-a", admitted!!.branchId)
        assertEquals("cashier-a", admitted!!.actorUserId)
        assertEquals("OMR", admitted!!.currencyCode)
        assertEquals(3, admitted!!.currencyDecimalPlaces)
        assertEquals(MoneyMinorUnits(9000), admitted!!.draftState.grandTotal)
        assertNull(vm.uiState.value.lastCompletedTransaction)
    }

    @Test
    fun sameSessionRetryReusesExactCommandAndLatestSnapshotPermissions() = runTest {
        val commands = mutableListOf<CashSaleCommand>()
        val memberships = mutableListOf<UserBranchMembershipEntity?>()
        val vm = viewModel { command, _, membership ->
            commands += command
            memberships += membership
            CommandResult.Error(IllegalStateException("retry"))
        }
        vm.onKeypadPress(KeypadKey.Digit('4'))
        vm.executeCashSale()
        runCurrent()
        val fingerprint = commands.single().requestFingerprint
        val changedPermissions = a.copy(membership = a.membership!!.copy(canAcceptCash = false))
        SessionManager.setSession(changedPermissions)
        vm.executeCashSale()
        runCurrent()
        assertSame(commands[0], commands[1])
        assertEquals(fingerprint, commands[1].requestFingerprint)
        assertSame(changedPermissions.membership, memberships[1])
        assertEquals(0L, vm.financialSessionGeneration)
    }

    @Test
    fun everyStaleResultLeavesNewBranchExecutingUiUntouched() = runTest {
        val results: List<CommandResult<TransactionEntity>> = listOf(
            CommandResult.Success(transaction()),
            CommandResult.Conflict("old conflict"),
            CommandResult.PermissionDenied("old denial"),
            CommandResult.Error(IllegalStateException("old error"))
        )
        for (result in results) {
            SessionManager.setSession(a)
            val oldResult = CompletableDeferred<CommandResult<TransactionEntity>>()
            val newResult = CompletableDeferred<CommandResult<TransactionEntity>>()
            val vm = viewModel { command, _, _ ->
                if (command.branchId == a.branch.id) oldResult.await() else newResult.await()
            }
            vm.onKeypadPress(KeypadKey.Digit('1'))
            vm.executeCashSale()
            runCurrent()
            SessionManager.setSession(b)
            runCurrent()
            vm.onKeypadPress(KeypadKey.Digit('2'))
            vm.executeCashSale()
            runCurrent()
            val before = vm.uiState.value
            assertTrue(before.isExecuting)
            oldResult.complete(result)
            runCurrent()
            assertSame(before, vm.uiState.value)
            newResult.complete(CommandResult.Conflict("new context result"))
            runCurrent()
            assertFalse(vm.uiState.value.isExecuting)
            assertEquals(UiText.Dynamic("new context result"), vm.uiState.value.errorMessage)
        }
    }

    @Test
    fun resultGuardRejectsNewSessionBeforeQueuedObserverCanReset() = runTest {
        val pending = CompletableDeferred<CommandResult<TransactionEntity>>()
        val vm = viewModel { _, _, _ -> pending.await() }
        vm.onKeypadPress(KeypadKey.Digit('1'))
        vm.executeCashSale()
        runCurrent()
        val before = vm.uiState.value
        val observed = mutableListOf<RegisterUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { observed += it }
        }
        // Queue result first, then change the session before that continuation runs.
        pending.complete(CommandResult.Success(transaction()))
        SessionManager.setSession(b)
        assertEquals(0L, vm.financialSessionGeneration)
        runCurrent()
        assertNull(vm.uiState.value.lastCompletedTransaction)
        assertTrue(observed.none { it.lastCompletedTransaction != null })
        assertNotEquals(before, vm.uiState.value)
        assertEquals(1L, vm.financialSessionGeneration)
    }

    @Test
    fun oldAResultCannotModifyLaterAGeneration() = runTest {
        val pending = CompletableDeferred<CommandResult<TransactionEntity>>()
        val vm = viewModel { _, _, _ -> pending.await() }
        vm.onKeypadPress(KeypadKey.Digit('1'))
        vm.executeCashSale()
        runCurrent()
        SessionManager.setSession(b)
        runCurrent()
        assertEquals(1L, vm.financialSessionGeneration)
        SessionManager.setSession(a)
        runCurrent()
        assertEquals(2L, vm.financialSessionGeneration)
        vm.onKeypadPress(KeypadKey.Digit('7'))
        val before = vm.uiState.value
        pending.complete(CommandResult.Success(transaction()))
        runCurrent()
        assertSame(before, vm.uiState.value)
    }

    @Test
    fun completedSuccessUiIsClearedOnSwitch() = runTest {
        val vm = viewModel { _, _, _ -> CommandResult.Success(transaction()) }
        vm.onKeypadPress(KeypadKey.Digit('1'))
        vm.executeCashSale()
        runCurrent()
        assertNotNull(vm.uiState.value.lastCompletedTransaction)
        SessionManager.setSession(b)
        runCurrent()
        assertNull(vm.uiState.value.lastCompletedTransaction)
        assertEquals(MoneyMinorUnits.ZERO, vm.uiState.value.draftState.grandTotal)
    }

    private fun viewModel(
        execute: suspend (CashSaleCommand, UserAccountEntity, UserBranchMembershipEntity?) -> CommandResult<TransactionEntity> =
            { _, _, _ -> error("No sale should be admitted") }
    ): RegisterViewModel = RegisterViewModel(
        observeToday = { _, _ -> flowOf(emptyList()) },
        executeSale = execute,
        deviceId = { "test-device" }
    ).also { store.put("register-${nextViewModelId++}", it) }

    private fun populate(vm: RegisterViewModel) {
        vm.onKeypadPress(KeypadKey.Digit('9'))
        vm.onManualDescriptionChanged("old note")
        vm.addItem("old item", "2.000", 2)
        vm.setTaxEnabled(true)
        vm.updateTxShippingPacking("1", "2")
        vm.toggleTaxDialog(true)
        vm.toggleShippingPackingDialog(true)
        vm.toggleItemizedBillDialog(true)
        vm.toggleAddItemDialog(true)
        vm.toggleRecentTransactionsExpanded()
    }

    private fun assertReset(vm: RegisterViewModel, tax: Int, rounding: TaxRoundingMode) {
        val state = vm.uiState.value
        assertEquals(CalculatorState(), state.calculatorState)
        assertEquals(MoneyMinorUnits.ZERO, state.draftState.grandTotal)
        assertEquals(MoneyMinorUnits.ZERO, state.draftState.manualAmount)
        assertEquals(MoneyMinorUnits.ZERO, state.draftState.txShipping)
        assertEquals(MoneyMinorUnits.ZERO, state.draftState.txPacking)
        assertTrue(state.draftState.items.isEmpty())
        assertNull(state.draftState.manualDescription)
        assertEquals("", state.manualDescription)
        assertEquals(tax, state.draftState.taxRateBps)
        assertEquals(rounding, state.draftState.taxRoundingMode)
        assertFalse(state.draftState.isTaxEnabled)
        assertFalse(state.isTaxDialogOpen)
        assertFalse(state.isShippingPackingDialogOpen)
        assertFalse(state.isItemizedBillDialogOpen)
        assertFalse(state.isAddItemDialogOpen)
        assertFalse(state.isExecuting)
        assertNull(state.inFlightSaleCommand)
        assertNull(state.lastCompletedTransaction)
        assertNull(state.errorMessage)
        assertTrue(state.isRecentTransactionsExpanded)
    }

    private fun session(
        branchId: String, userId: String, currency: String = "OMR", precision: Int = 3,
        tax: Int = 500, rounding: TaxRoundingMode = TaxRoundingMode.HALF_UP
    ): ActiveSession = ActiveSession(
        business = BusinessEntity("business-1", "Business", createdAt = 1, updatedAt = 1),
        branch = BranchEntity(
            id = branchId, businessId = "business-1", name = branchId, code = branchId,
            countryCode = "OM", currencyCode = currency, currencyDecimalPlaces = precision,
            currencySymbol = currency, timezoneId = "Asia/Muscat", defaultTaxRateBps = tax,
            taxRoundingMode = rounding, createdAt = 1, updatedAt = 1
        ),
        user = UserAccountEntity(
            id = userId, businessId = "business-1", displayName = userId, username = userId,
            role = UserRole.CASHIER, authType = AuthType.PIN, credentialHash = "hash",
            credentialSalt = "salt", createdAt = 1, updatedAt = 1
        ),
        membership = UserBranchMembershipEntity(
            id = "$branchId-$userId", userId = userId, branchId = branchId,
            role = UserRole.CASHIER, createdAt = 1
        )
    )

    private fun transaction() = TransactionEntity(
        id = "completed-a", branchId = "branch-a", type = TransactionType.SALE,
        subtotalMinor = 1000, taxRateBps = 0, taxableBaseMinor = 1000, taxAmountMinor = 0,
        grandTotalMinor = 1000, currencyCode = "OMR", currencyDecimalPlaces = 3,
        createdByUserId = "cashier-a", deviceId = "test-device", createdAt = 1, displayRef = "A-1"
    )
}
