package com.dailymoneyregister.core.cloud.sync

import android.content.Context
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

    private val _unsupportedInboundCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unsupportedInboundCounts: StateFlow<Map<String, Int>> =
        _unsupportedInboundCounts.asStateFlow()
    private val unsupportedIdsInMemory = mutableMapOf<String, MutableSet<String>>()

    fun loadUnsupportedCount(context: Context?, businessId: String) {
        val ids = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getStringSet(key(businessId), emptySet())
            ?.toMutableSet()
            ?: unsupportedIdsInMemory[businessId].orEmpty().toMutableSet()
        unsupportedIdsInMemory[businessId] = ids
        _unsupportedInboundCounts.value = _unsupportedInboundCounts.value + (businessId to ids.size)
    }

    fun recordUnsupported(context: Context?, businessId: String, operationId: String) {
        val ids = unsupportedIdsInMemory.getOrPut(businessId) { mutableSetOf() }
        if (!ids.add(operationId)) return
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putStringSet(key(businessId), ids.toSet())?.commit()
        _unsupportedInboundCounts.value = _unsupportedInboundCounts.value + (businessId to ids.size)
    }

    private fun key(businessId: String) = "unsupported_operation_ids_$businessId"

    internal fun report(problem: FinancialSyncProblem) {
        _adminVisibleProblem.value = problem
    }

    fun clearVisibleProblem() {
        _adminVisibleProblem.value = null
    }

    private const val PREFS = "dmr_financial_sync_status"
}
