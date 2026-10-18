package com.dailymoneyregister.feature.reporting.domain

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus

enum class ReportingReconciliationStatus {
    COMPLETE,
    INCOMPLETE,
    REQUIRES_REVIEW
}

data class ReportingReconciliation(
    val status: ReportingReconciliationStatus,
    val authoritativeOperationCount: Int,
    val reportableOperationCount: Int,
    val representedOperationCount: Int,
    val missingOperationIds: List<String> = emptyList(),
    val unmatchedRowIds: List<String> = emptyList(),
    val duplicateOperationIds: List<String> = emptyList(),
    val duplicateRowIds: List<String> = emptyList(),
    val unsupportedOperationIds: List<String> = emptyList(),
    val excludedOperations: Map<String, String> = emptyMap(),
    val cloudInventoryVerified: Boolean = false,
    val explanation: String
) {
    val operationCoverageDelta: Int
        get() = reportableOperationCount - representedOperationCount

    companion object {
        fun unverified(): ReportingReconciliation = ReportingReconciliation(
            status = ReportingReconciliationStatus.REQUIRES_REVIEW,
            authoritativeOperationCount = 0,
            reportableOperationCount = 0,
            representedOperationCount = 0,
            cloudInventoryVerified = false,
            explanation = "Authoritative operation-to-report reconciliation has not been run."
        )
    }
}

/**
 * Identity-based reconciliation between local authoritative operations and the
 * report rows projected for the same requested scope. Raw counts are not
 * compared because one operation may legitimately produce multiple event rows.
 *
 * COMPLETE is deliberately impossible unless the caller supplies verified cloud
 * inventory coverage. A local-only read must never certify cross-device
 * completeness.
 */
object FinancialReconciliationEngine {
    private val reportableOperationTypes = setOf(
        "RECORD_SALE",
        "RECORD_REFUND",
        "RECORD_VOID",
        "RECORD_AMOUNT_CORRECTION",
        "RECORD_PAYMENT_METHOD_CHANGE",
        "RECORD_CREDIT_REPAYMENT",
        "RECORD_COUPON_PURCHASE",
        "RECORD_EXPENSE",
        "RECORD_EXPENSE_CORRECTION",
        "RECORD_EXPENSE_VOID"
    )

    private val explicitlyNonReportableOperationTypes = setOf(
        "OPEN_CASH_SHIFT",
        "COUNT_CASH_SHIFT",
        "CLOSE_CASH_SHIFT"
    )

    fun reconcile(
        operations: List<OperationEntity>,
        rows: List<ReportRow>,
        cloudInventoryVerified: Boolean
    ): ReportingReconciliation {
        val duplicateOperationIds = operations
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        val duplicateRowIds = rows
            .groupingBy { it.stableId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

        val operationsById = operations.associateBy { it.id }
        val reportRowsByOperation = rows
            .filter { !it.operationId.isNullOrBlank() }
            .groupBy { it.operationId!! }

        val excluded = linkedMapOf<String, String>()
        val reportable = mutableListOf<OperationEntity>()
        val unsupported = mutableListOf<String>()

        operations.forEach { operation ->
            when {
                operation.status != OperationStatus.SUCCEEDED ->
                    excluded[operation.id] = "OPERATION_NOT_SUCCEEDED"

                operation.operationType.name in reportableOperationTypes ->
                    reportable += operation

                operation.operationType.name in explicitlyNonReportableOperationTypes ->
                    excluded[operation.id] = "OPERATIONAL_EVENT_NOT_TRANSACTION_DETAIL"

                else -> unsupported += operation.id
            }
        }

        val missing = reportable
            .filter { reportRowsByOperation[it.id].isNullOrEmpty() }
            .map { it.id }
            .distinct()
            .sorted()

        val unmatchedRows = rows
            .filter { row ->
                row.operationId.isNullOrBlank() ||
                    row.operationId !in operationsById ||
                    operationsById[row.operationId]?.status != OperationStatus.SUCCEEDED
            }
            .map { it.stableId }
            .distinct()
            .sorted()

        val representedOperationIds = reportable
            .map { it.id }
            .filter { !reportRowsByOperation[it].isNullOrEmpty() }
            .distinct()

        val status = when {
            duplicateOperationIds.isNotEmpty() ||
                duplicateRowIds.isNotEmpty() ||
                unmatchedRows.isNotEmpty() ||
                unsupported.isNotEmpty() ||
                !cloudInventoryVerified ->
                ReportingReconciliationStatus.REQUIRES_REVIEW

            missing.isNotEmpty() ->
                ReportingReconciliationStatus.INCOMPLETE

            representedOperationIds.size != reportable.size ->
                ReportingReconciliationStatus.INCOMPLETE

            else -> ReportingReconciliationStatus.COMPLETE
        }

        val explanation = when (status) {
            ReportingReconciliationStatus.COMPLETE ->
                "Every in-scope successful reportable operation is represented, exclusions are explicit, identities are unique, and cloud inventory coverage is verified."
            ReportingReconciliationStatus.INCOMPLETE ->
                "One or more in-scope successful operations have no corresponding report row."
            ReportingReconciliationStatus.REQUIRES_REVIEW -> buildList {
                if (duplicateOperationIds.isNotEmpty()) add("duplicate operation identities")
                if (duplicateRowIds.isNotEmpty()) add("duplicate report row identities")
                if (unmatchedRows.isNotEmpty()) add("report rows without a matching successful in-scope operation")
                if (unsupported.isNotEmpty()) add("successful operations with unsupported report classification")
                if (!cloudInventoryVerified) add("cloud inventory coverage is not verified")
            }.joinToString("; ").ifBlank { "Scope or evidence could not be verified." }
        }

        return ReportingReconciliation(
            status = status,
            authoritativeOperationCount = operations.size,
            reportableOperationCount = reportable.size,
            representedOperationCount = representedOperationIds.size,
            missingOperationIds = missing,
            unmatchedRowIds = unmatchedRows,
            duplicateOperationIds = duplicateOperationIds,
            duplicateRowIds = duplicateRowIds,
            unsupportedOperationIds = unsupported.distinct().sorted(),
            excludedOperations = excluded,
            cloudInventoryVerified = cloudInventoryVerified,
            explanation = explanation
        )
    }
}
