package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CloseRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.CountRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.RegisterShiftReport
import com.dailymoneyregister.feature.register.data.RegisterShiftResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterDayClosingCoordinatorTest {
    @Test
    fun `open loads the current shift report`() = runTest {
        val report = report()
        val state = MutableStateFlow(RegisterUiState())
        val coordinator = coordinator(state, backgroundScope, loadReport = { report })

        coordinator.openDayClosing()
        runCurrent()

        assertEquals(RegisterOverlay.DayClosing, state.value.overlay)
        assertEquals(report, state.value.dayClosingReport)
        assertFalse(state.value.dayClosingLoading)
        assertNull(state.value.errorMessage)
    }

    @Test
    fun `cash input filtering and review validation remain unchanged`() = runTest {
        val state = MutableStateFlow(RegisterUiState(dayClosingReport = report()))
        val coordinator = coordinator(state, backgroundScope)

        coordinator.updateDayClosingActualCash("12a.34")
        assertEquals("12.34", state.value.dayClosingActualCash)
        coordinator.updateDayClosingActualCash("12.3.4")
        assertEquals("12.34", state.value.dayClosingActualCash)

        coordinator.reviewDayClosing()
        assertTrue(state.value.dayClosingReview)

        val emptyState = MutableStateFlow(RegisterUiState(dayClosingReport = report()))
        coordinator(emptyState, backgroundScope).reviewDayClosing()
        assertFalse(emptyState.value.dayClosingReview)
        assertEquals(UiText.Resource(com.dailymoneyregister.R.string.dmr20_enter_actual_cash), emptyState.value.errorMessage)
    }

    @Test
    fun `confirm counts then closes with the unchanged snapshot and reconciliation`() = runTest {
        val state = MutableStateFlow(RegisterUiState(
            overlay = RegisterOverlay.DayClosing,
            dayClosingReport = report(),
            dayClosingActualCash = "12.345",
            dayClosingReview = true
        ))
        var counted: CountRegisterShiftCommand? = null
        var closed: CloseRegisterShiftCommand? = null
        val coordinator = coordinator(
            state, backgroundScope,
            count = {
                counted = it
                CommandResult.Success(RegisterShiftResult(it.shiftId, it.branchId, it.actorUserId, 1L, true, it.countedCashMinor, 2L))
            },
            close = {
                closed = it
                CommandResult.Success(RegisterShiftResult(it.shiftId, it.branchId, it.actorUserId, 1L, false, requireNotNull(it.reportSnapshot).countedCashMinor, 3L))
            }
        )

        coordinator.confirmDayClosing()
        runCurrent()

        assertNotNull(counted)
        assertNotNull(closed)
        assertEquals(12_345L, counted!!.countedCashMinor)
        val closeSnapshot = requireNotNull(closed!!.reportSnapshot)
        assertEquals(12_345L, closeSnapshot.countedCashMinor)
        assertEquals(9_345L, closeSnapshot.differenceMinor)
        assertEquals("shift-1", closeSnapshot.shiftId)
        assertEquals(9_345L, state.value.dayClosingResult?.reconciliation?.differenceMinor)
        assertFalse(state.value.isExecuting)
        assertNull(state.value.inFlightShiftCountCommand)
        assertNull(state.value.inFlightShiftCloseCommand)
    }

    @Test
    fun `identity change after count prevents shift close submission`() = runTest {
        val state = MutableStateFlow(RegisterUiState(
            overlay = RegisterOverlay.DayClosing,
            dayClosingReport = report(),
            dayClosingActualCash = "12.345",
            dayClosingReview = true
        ))
        var current = session()
        var closeCalls = 0
        val coordinator = coordinator(
            state, backgroundScope,
            currentSession = { current },
            count = {
                current = session("branch-2")
                CommandResult.Success(RegisterShiftResult(it.shiftId, it.branchId, it.actorUserId, 1L, true, it.countedCashMinor, 2L))
            },
            close = {
                closeCalls++
                CommandResult.Success(RegisterShiftResult(it.shiftId, it.branchId, it.actorUserId, 1L, false, requireNotNull(it.reportSnapshot).countedCashMinor, 3L))
            }
        )

        coordinator.confirmDayClosing()
        runCurrent()

        assertEquals(0, closeCalls)
        assertEquals(
            UiText.Dynamic("The signed-in identity changed during day closing. Reopen Day Closing and try again."),
            state.value.errorMessage
        )
        assertFalse(state.value.isExecuting)
    }

    private fun coordinator(
        state: MutableStateFlow<RegisterUiState>,
        scope: kotlinx.coroutines.CoroutineScope,
        loadReport: suspend () -> RegisterShiftReport? = { report() },
        currentSession: () -> ActiveSession? = { session() },
        count: suspend (CountRegisterShiftCommand) -> CommandResult<RegisterShiftResult> = {
            CommandResult.Success(RegisterShiftResult(it.shiftId, it.branchId, it.actorUserId, 1L, true, it.countedCashMinor, 2L))
        },
        close: suspend (CloseRegisterShiftCommand) -> CommandResult<RegisterShiftResult> = {
            CommandResult.Success(RegisterShiftResult(it.shiftId, it.branchId, it.actorUserId, 1L, false, requireNotNull(it.reportSnapshot).countedCashMinor, 3L))
        }
    ) = RegisterDayClosingCoordinator(
        uiState = state,
        scope = scope,
        currentSession = currentSession,
        synchronizeFinancialSession = { false },
        decimalPlaces = { 3 },
        deviceId = { "test-device" },
        loadCurrentRegisterShiftReport = loadReport,
        submitRegisterShiftCount = count,
        submitRegisterShiftClose = close
    )

    private fun report(): RegisterShiftReport {
        val shiftId = CashShiftId("shift-1")
        return RegisterShiftReport(
            shift = RegisterShiftResult(shiftId, "branch-1", "admin-1", 1L, true),
            operationCount = 4,
            salesCount = 2,
            grossSalesMinor = 5_000L,
            cashSalesMinor = 3_000L,
            onlineSalesMinor = 2_000L,
            couponUsedMinor = 0L,
            creditGivenMinor = 0L,
            cashCreditRepaymentsMinor = 0L,
            onlineCreditRepaymentsMinor = 0L,
            couponCreditRepaymentsMinor = 0L,
            couponIssuedMinor = 0L,
            couponTopUpMinor = 0L,
            couponRefundCreditsMinor = 0L,
            cashRefundsMinor = 0L,
            onlineRefundsMinor = 0L,
            refundCount = 0,
            voidCount = 0,
            correctionCount = 0,
            paymentMethodChangeCount = 0,
            expectedCashMinor = 3_000L
        )
    }

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
