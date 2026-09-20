package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.security.ActiveSession

internal fun shouldApplyFinancialResultToRegister(
    executionContext: RegisterFinancialSessionContext?,
    currentSession: ActiveSession?,
    executionGeneration: Long,
    currentGeneration: Long
): Boolean =
    executionContext != null &&
        executionGeneration == currentGeneration &&
        executionContext == currentSession.toRegisterFinancialSessionContext()
