package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CloseRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.CountRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.RegisterShiftCloseReportSnapshot
import com.dailymoneyregister.feature.register.data.RegisterShiftReport
import com.dailymoneyregister.feature.register.data.RegisterShiftResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Day Closing UI workflow while the ViewModel retains shared session
 * and RegisterUiState ownership.
 *
 * Financial execution and durable operation semantics remain in the existing
 * RegisterShiftRepository through the supplied command callbacks.
 */
internal class RegisterDayClosingCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val currentSession: () -> ActiveSession?,
    private val synchronizeFinancialSession: (ActiveSession?) -> Boolean,
    private val decimalPlaces: () -> Int,
    private val deviceId: () -> String,
    private val loadCurrentRegisterShiftReport: suspend () -> RegisterShiftReport?,
    private val submitRegisterShiftCount: suspend (CountRegisterShiftCommand) -> CommandResult<RegisterShiftResult>,
    private val submitRegisterShiftClose: suspend (CloseRegisterShiftCommand) -> CommandResult<RegisterShiftResult>
) {
    fun openDayClosing() {
        val session = currentSession() ?: return

        if (synchronizeFinancialSession(session)) return
        if (uiState.value.isExecuting) return

        uiState.value =
            uiState.value.copy(
                overlay = RegisterOverlay.DayClosing,
                dayClosingLoading = true,
                dayClosingReport = null,
                dayClosingActualCash = "",
                dayClosingReview = false,
                dayClosingResult = null,
                errorMessage = null
            )

        scope.launch {
            try {
                val report = loadCurrentRegisterShiftReport()

                uiState.value =
                    uiState.value.copy(
                        dayClosingLoading = false,
                        dayClosingReport = report,
                        errorMessage =
                            if (report == null) {
                                UiText.Resource(R.string.dmr20_day_no_open)
                            } else {
                                null
                            }
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error

                uiState.value =
                    uiState.value.copy(
                        dayClosingLoading = false,
                        errorMessage =
                            UiText.Dynamic(
                                error.message ?: "Unable to load Day Closing."
                            )
                    )
            }
        }
    }

    fun closeDayClosing() {
        if (uiState.value.overlay != RegisterOverlay.DayClosing || uiState.value.isExecuting) return

        uiState.value =
            uiState.value.copy(
                overlay = null,
                dayClosingLoading = false,
                dayClosingReport = null,
                dayClosingActualCash = "",
                dayClosingReview = false,
                dayClosingResult = null,
                errorMessage = null
            )
    }

    fun updateDayClosingActualCash(value: String) {
        if (uiState.value.isExecuting) return
        if (uiState.value.dayClosingReview) return
        if (uiState.value.dayClosingResult != null) return

        val cleaned =
            value.filter { it.isDigit() || it == '.' }

        if (cleaned.count { it == '.' } > 1) return

        uiState.value =
            uiState.value.copy(
                dayClosingActualCash = cleaned,
                errorMessage = null
            )
    }

    fun reviewDayClosing() {
        val state = uiState.value
        if (state.dayClosingResult != null) return
        if (state.dayClosingReport == null) return

        val entered =
            state.dayClosingActualCash.toBigDecimalOrNull()

        if (entered == null || entered.signum() < 0) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(R.string.dmr20_enter_actual_cash)
                )
            return
        }

        uiState.value =
            state.copy(
                dayClosingReview = true,
                errorMessage = null
            )
    }

    fun backFromDayClosingReview() {
        if (uiState.value.isExecuting) return

        uiState.value =
            uiState.value.copy(
                dayClosingReview = false
            )
    }

    fun confirmDayClosing() {
        val session = currentSession() ?: return
        val state = uiState.value
        val report = state.dayClosingReport ?: return

        if (state.isExecuting) return
        if (!state.dayClosingReview) return

        val entered =
            state.dayClosingActualCash.toBigDecimalOrNull()

        if (entered == null || entered.signum() < 0) {
            uiState.value =
                state.copy(
                    dayClosingReview = false,
                    errorMessage =
                        UiText.Resource(R.string.dmr20_enter_actual_cash)
                )
            return
        }

        val multiplier =
            java.math.BigDecimal.TEN.pow(decimalPlaces())

        val countedMinor =
            try {
                entered
                    .multiply(multiplier)
                    .setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                uiState.value =
                    state.copy(
                        dayClosingReview = false,
                        errorMessage =
                            UiText.Resource(R.string.dmr20_invalid_cash)
                    )
                return
            }

        val countCommand =
            CountRegisterShiftCommand(
                operationId = OperationId.generate(),
                businessId = session.business.id,
                branchId = session.branch.id,
                shiftId = report.shift.shiftId,
                countedCashMinor = countedMinor,
                actorUserId = session.user.id,
                deviceId = deviceId(),
                occurredAt = System.currentTimeMillis()
            )

        uiState.value =
            state.copy(
                isExecuting = true,
                inFlightShiftCountCommand = countCommand,
                errorMessage = null
            )

        scope.launch {
            when (
                val countResult =
                    submitRegisterShiftCount(countCommand)
            ) {
                is CommandResult.Success -> {
                    val closingSession = currentSession()
                    if (closingSession == null ||
                        closingSession.business.id != session.business.id ||
                        closingSession.branch.id != session.branch.id ||
                        (closingSession.user.role != UserRole.ADMIN && closingSession.user.id != session.user.id)
                    ) {
                        uiState.value = uiState.value.copy(
                            isExecuting = false,
                            inFlightShiftCountCommand = null,
                            inFlightShiftCloseCommand = null,
                            errorMessage = UiText.Dynamic("The signed-in identity changed during day closing. Reopen Day Closing and try again.")
                        )
                        return@launch
                    }
                    val closeOccurredAt = System.currentTimeMillis()
                    val closeCommand =
                        CloseRegisterShiftCommand(
                            operationId = OperationId.generate(),
                            businessId = closingSession.business.id,
                            branchId = closingSession.branch.id,
                            shiftId = report.shift.shiftId,
                            actorUserId = closingSession.user.id,
                            deviceId = deviceId(),
                            occurredAt = closeOccurredAt,
                            reportSnapshot = RegisterShiftCloseReportSnapshot(
                                shiftId = report.shift.shiftId.value,
                                currencyCode = closingSession.branch.currencyCode,
                                currencyDecimalPlaces = closingSession.branch.currencyDecimalPlaces,
                                currencySymbol = closingSession.branch.currencySymbol,
                                operationCount = report.operationCount,
                                salesCount = report.salesCount,
                                grossSalesMinor = report.grossSalesMinor,
                                cashSalesMinor = report.cashSalesMinor,
                                onlineSalesMinor = report.onlineSalesMinor,
                                creditGivenMinor = report.creditGivenMinor,
                                couponUsedMinor = report.couponUsedMinor,
                                cashCreditRepaymentsMinor = report.cashCreditRepaymentsMinor,
                                onlineCreditRepaymentsMinor = report.onlineCreditRepaymentsMinor,
                                couponCreditRepaymentsMinor = report.couponCreditRepaymentsMinor,
                                couponIssuedMinor = report.couponIssuedMinor,
                                couponTopUpMinor = report.couponTopUpMinor,
                                couponRefundCreditsMinor = report.couponRefundCreditsMinor,
                                cashRefundsMinor = report.cashRefundsMinor,
                                onlineRefundsMinor = report.onlineRefundsMinor,
                                cashExpensesMinor = report.cashExpensesMinor,
                                onlineExpensesMinor = report.onlineExpensesMinor,
                                cashCouponFundingMinor = report.cashCouponFundingMinor,
                                onlineCouponFundingMinor = report.onlineCouponFundingMinor,
                                cashLifecycleAdjustmentMinor = report.cashLifecycleAdjustmentMinor,
                                onlineLifecycleAdjustmentMinor = report.onlineLifecycleAdjustmentMinor,
                                refundCount = report.refundCount,
                                voidCount = report.voidCount,
                                correctionCount = report.correctionCount,
                                paymentMethodChangeCount = report.paymentMethodChangeCount,
                                expectedCashMinor = report.expectedCashMinor,
                                countedCashMinor = countedMinor,
                                differenceMinor = Math.subtractExact(countedMinor, report.expectedCashMinor),
                                cashierUserId = report.shift.actorUserId,
                                closedByUserId = closingSession.user.id,
                                closedAt = closeOccurredAt
                            )
                        )

                    uiState.value =
                        uiState.value.copy(
                            inFlightShiftCountCommand = null,
                            inFlightShiftCloseCommand = closeCommand
                        )

                    when (
                        val closeResult =
                            submitRegisterShiftClose(closeCommand)
                    ) {
                                                is CommandResult.Success -> {
                            val persisted =
                                closeResult.data

                            val persistedCounted =
                                persisted.countedCashMinor

                            val persistedClosedAt =
                                persisted.closedAt

                            val reconciliation =
                                if (
                                    !persisted.isOpen &&
                                    persistedCounted != null &&
                                    persistedClosedAt != null
                                ) {
                                    dayClosingReconciliation(
                                        expectedCashMinor =
                                            report.expectedCashMinor,
                                        countedCashMinor =
                                            persistedCounted
                                    )
                                } else {
                                    null
                                }

                            if (
                                reconciliation == null ||
                                persistedClosedAt == null
                            ) {
                                /*
                                 * Close already succeeded durably.
                                 * Never retry a financial close just because
                                 * presentation evidence is incomplete.
                                 */
                                uiState.value =
                                    uiState.value.copy(
                                        isExecuting = false,
                                        overlay = null,
                                        dayClosingLoading = false,
                                        dayClosingReport = null,
                                        dayClosingActualCash = "",
                                        dayClosingReview = false,
                                        dayClosingResult = null,
                                        inFlightShiftCountCommand = null,
                                        inFlightShiftCloseCommand = null,
                                        errorMessage = null,
                                        infoMessage =
                                            UiText.Dynamic(
                                                "Shift closed successfully."
                                            )
                                    )
                            } else {
                                val ownerId =
                                    persisted.actorUserId

                                val ownerDisplayName =
                                    if (
                                        ownerId ==
                                        closeCommand.actorUserId
                                    ) {
                                        session.user.displayName
                                    } else {
                                        ownerId
                                    }

                                val summary =
                                    DayClosingResultUi(
                                        reconciliation =
                                            reconciliation,
                                        shiftOwnerUserId =
                                            ownerId,
                                        shiftOwnerDisplayName =
                                            ownerDisplayName,
                                        closedByUserId =
                                            closeCommand.actorUserId,
                                        closedByDisplayName =
                                            session.user.displayName,
                                        closedAt =
                                            persistedClosedAt,
                                        closedAtDisplay =
                                            formatDayClosingTime(
                                                persistedClosedAt,
                                                session.branch.timezoneId
                                            )
                                    )

                                uiState.value =
                                    uiState.value.copy(
                                        isExecuting = false,
                                        overlay =
                                            RegisterOverlay.DayClosing,
                                        dayClosingLoading = false,
                                        dayClosingReport = report,
                                        dayClosingReview = false,
                                        dayClosingResult = summary,
                                        inFlightShiftCountCommand = null,
                                        inFlightShiftCloseCommand = null,
                                        errorMessage = null,
                                        infoMessage = null
                                    )
                            }
                        }

                        is CommandResult.Conflict ->
                            uiState.value =
                                uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(closeResult.message)
                                )

                        is CommandResult.PermissionDenied ->
                            uiState.value =
                                uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(closeResult.message)
                                )

                        is CommandResult.Error ->
                            uiState.value =
                                uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(
                                            closeResult.exception.message
                                                ?: "Unable to close shift."
                                        )
                                )
                    }
                }

                is CommandResult.Conflict ->
                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(countResult.message)
                        )

                is CommandResult.PermissionDenied ->
                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(countResult.message)
                        )

                is CommandResult.Error ->
                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(
                                    countResult.exception.message
                                        ?: "Unable to save cash count."
                                )
                        )
            }
        }
    }

}
