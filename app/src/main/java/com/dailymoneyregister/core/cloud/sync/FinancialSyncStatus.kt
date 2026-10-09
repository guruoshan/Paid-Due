package com.dailymoneyregister.core.cloud.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FinancialSyncProblem(
    val operationId: String?,
    val message: String
)

object FinancialSyncStatus {
    private val _adminVisibleProblem = MutableStateFlow<FinancialSyncProblem?>(null)
    val adminVisibleProblem: StateFlow<FinancialSyncProblem?> =
        _adminVisibleProblem.asStateFlow()

    internal fun report(problem: FinancialSyncProblem) {
        _adminVisibleProblem.value = problem
    }

    fun clearVisibleProblem() {
        _adminVisibleProblem.value = null
    }
}
