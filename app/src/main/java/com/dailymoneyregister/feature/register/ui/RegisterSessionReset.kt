package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.feature.register.domain.CalculatorState

internal fun resetRegisterUiStateForSession(
    currentState: RegisterUiState,
    session: ActiveSession?
): RegisterUiState =
    RegisterUiState(
        calculatorState = CalculatorState(),
        draftState = clearedRegisterDraft(session?.branch),
        isRecentTransactionsExpanded = currentState.isRecentTransactionsExpanded
    )
