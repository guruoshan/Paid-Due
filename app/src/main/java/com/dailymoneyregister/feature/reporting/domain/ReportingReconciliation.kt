package com.dailymoneyregister.feature.reporting.domain

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus

enum class ReportingReconciliationStatus {
    COMPLETE,
    INCOMPLETE,
    REQUIRES_REVIEW
}

data class ReportingCloudOperation(
    val operationId: String,
    val businessId: String,
    val branchId: String,
    val operationType: String,
    val occurredAtMillis: Long,
    val actorUserId: String?,
    val requestFingerprint: String?,
    val canonicalSha256: String
)

data class ReportingReconciliation(
    val status: ReportingReconciliationStatus,
    val authoritativeOperationCount: Int,
    val reportableOperationCount: Int,
    val representedOperationCount: Int,
    val missingOperationIds: List<String> = emptyList(),
    val unmatchedRowIds: List<String> = emptyList(),
    val operationTypeMismatchRowIds: List<String> = emptyList(),
    val duplicateOperationIds: List<String> = emptyList(),
    val duplicateCloudOperationIds: List<String> = emptyList(),
    val localOnlyOperationIds: List<String> = emptyList(),
    val cloudOnlyOperationIds: List<String> = emptyList(),
    val unresolvedLocalOperationIds: List<String> = emptyList(),
    val identityMismatchOperationIds: List<String> = emptyList(),
    val duplicateRowIds: List<String> = emptyList(),
    val unsupportedOperationIds: List<String> = emptyList(),
    val excludedOperations: Map<String, String> = emptyMap(),
    val cloudInventoryVerified: Boolean = false,
    val cloudInventoryFailureReason: String? = null,
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
                val operationId = row.operationId
                operationId.isNullOrBlank() ||
                    operationId !in operationsById ||
                    operationsById[operationId]?.status != OperationStatus.SUCCEEDED
            }
            .map { it.stableId }
            .distinct()
            .sorted()

        val typeMismatches = rows.filter { row ->
            val operationId = row.operationId ?: return@filter true
            val operation = operationsById[operationId] ?: return@filter true
            operation.operationType.name !in expectedOperationTypes(row)
        }.map { it.stableId }.distinct().sorted()

        val representedOperationIds = reportable
            .map { it.id }
            .filter { !reportRowsByOperation[it].isNullOrEmpty() }
            .distinct()

        val status = when {
            duplicateOperationIds.isNotEmpty() ||
                duplicateRowIds.isNotEmpty() ||
                unmatchedRows.isNotEmpty() ||
                typeMismatches.isNotEmpty() ||
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
                if (typeMismatches.isNotEmpty()) add("report rows linked to an incompatible operation type")
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
            operationTypeMismatchRowIds = typeMismatches,
            duplicateOperationIds = duplicateOperationIds,
            duplicateRowIds = duplicateRowIds,
            unsupportedOperationIds = unsupported.distinct().sorted(),
            excludedOperations = excluded,
            cloudInventoryVerified = cloudInventoryVerified,
            explanation = explanation
        )
    }

    /**
     * Production reconciliation across cloud envelopes, local operation rows,
     * and report details. A local-only result can never be COMPLETE.
     */
    fun reconcile(
        localOperations: List<OperationEntity>,
        cloudOperations: List<ReportingCloudOperation>,
        rows: List<ReportRow>,
        cloudInventoryVerified: Boolean,
        cloudInventoryFailureReason: String? = null
    ): ReportingReconciliation {
        val localById = localOperations.associateBy { it.id }
        val cloudById = cloudOperations.associateBy { it.operationId }
        val duplicateCloudIds = cloudOperations.groupingBy { it.operationId }
            .eachCount().filterValues { it > 1 }.keys.sorted()
        val localOnly = localOperations.filter {
            it.status == OperationStatus.SUCCEEDED && it.id !in cloudById
        }.map { it.id }.distinct().sorted()
        val cloudOnly = cloudOperations.filter { it.operationId !in localById }
            .map { it.operationId }.distinct().sorted()
        val unresolvedLocal = localOperations.filter {
            it.status != OperationStatus.SUCCEEDED
        }.map { it.id }.distinct().sorted()
        val identityMismatches = cloudOperations.filter { cloud ->
            val local = localById[cloud.operationId] ?: return@filter false
            local.businessId != cloud.businessId ||
                local.branchId != cloud.branchId ||
                local.operationType.name != cloud.operationType ||
                local.occurredAt != cloud.occurredAtMillis ||
                (!cloud.actorUserId.isNullOrBlank() && local.actorUserId != cloud.actorUserId) ||
                (!cloud.requestFingerprint.isNullOrBlank() &&
                    local.requestFingerprint != cloud.requestFingerprint)
        }.map { it.operationId }.distinct().sorted()

        val duplicateRows = rows.groupingBy { it.stableId }
            .eachCount().filterValues { it > 1 }.keys.sorted()
        val reportRowsByOperation = rows.filter { !it.operationId.isNullOrBlank() }
            .groupBy { it.operationId!! }
        val reportableTypes = setOf(
            "RECORD_SALE", "RECORD_REFUND", "RECORD_VOID",
            "RECORD_AMOUNT_CORRECTION", "RECORD_PAYMENT_METHOD_CHANGE",
            "RECORD_CREDIT_REPAYMENT", "RECORD_COUPON_PURCHASE",
            "RECORD_EXPENSE", "RECORD_EXPENSE_CORRECTION", "RECORD_EXPENSE_VOID"
        )
        val operationalTypes = setOf("OPEN_CASH_SHIFT", "COUNT_CASH_SHIFT", "CLOSE_CASH_SHIFT")
        val authoritative = if (cloudInventoryVerified) cloudOperations else emptyList()
        val reportable = authoritative.filter { it.operationType in reportableTypes }
        val unsupported = authoritative.filter {
            it.operationType !in reportableTypes && it.operationType !in operationalTypes
        }.map { it.operationId }.distinct().sorted()
        val missing = reportable.filter {
            reportRowsByOperation[it.operationId].isNullOrEmpty()
        }.map { it.operationId }.distinct().sorted()
        val unmatchedRows = rows.filter { row ->
            val id = row.operationId
            id.isNullOrBlank() ||
                id !in cloudById ||
                localById[id]?.status != OperationStatus.SUCCEEDED
        }.map { it.stableId }.distinct().sorted()
        val typeMismatches = rows.filter { row ->
            val id = row.operationId ?: return@filter true
            val type = cloudById[id]?.operationType ?: return@filter true
            type !in expectedOperationTypes(row)
        }.map { it.stableId }.distinct().sorted()
        val represented = reportable.filter {
            !reportRowsByOperation[it.operationId].isNullOrEmpty() &&
                localById[it.operationId]?.status == OperationStatus.SUCCEEDED &&
                it.operationId !in identityMismatches
        }.map { it.operationId }.distinct()
        val excluded = authoritative.filter { it.operationType in operationalTypes }
            .associate { it.operationId to "OPERATIONAL_EVENT_NOT_TRANSACTION_DETAIL" }

        val status = when {
            duplicateOperationIds.isNotEmpty() || duplicateCloudIds.isNotEmpty() ||
                duplicateRows.isNotEmpty() || unmatchedRows.isNotEmpty() ||
                typeMismatches.isNotEmpty() || unsupported.isNotEmpty() ||
                identityMismatches.isNotEmpty() || !cloudInventoryVerified ->
                ReportingReconciliationStatus.REQUIRES_REVIEW
            missing.isNotEmpty() || localOnly.isNotEmpty() || cloudOnly.isNotEmpty() ||
                unresolvedLocal.isNotEmpty() || represented.size != reportable.size ->
                ReportingReconciliationStatus.INCOMPLETE
            else -> ReportingReconciliationStatus.COMPLETE
        }
        val explanation = when (status) {
            ReportingReconciliationStatus.COMPLETE ->
                "Cloud and local operation inventories reconcile; every reportable operation has report rows, operational exclusions are explicit, and envelope digests are verified."
            ReportingReconciliationStatus.INCOMPLETE -> buildList {
                if (missing.isNotEmpty()) add("reportable cloud operations have no report rows")
                if (localOnly.isNotEmpty()) add("successful local operations are absent from cloud inventory")
                if (cloudOnly.isNotEmpty()) add("cloud operations are not materialized locally")
                if (unresolvedLocal.isNotEmpty()) add("local operations are not in a succeeded state")
            }.joinToString("; ").ifBlank { "Operation coverage is incomplete." }
            ReportingReconciliationStatus.REQUIRES_REVIEW -> buildList {
                if (duplicateOperationIds.isNotEmpty()) add("duplicate local operation identities")
                if (duplicateCloudIds.isNotEmpty()) add("duplicate cloud operation identities")
                if (duplicateRows.isNotEmpty()) add("duplicate report row identities")
                if (unmatchedRows.isNotEmpty()) add("report rows lack a matching successful cloud and local operation")
                if (typeMismatches.isNotEmpty()) add("report rows link to an incompatible operation type")
                if (unsupported.isNotEmpty()) add("cloud operations have unsupported report classification")
                if (identityMismatches.isNotEmpty()) add("cloud and local operation identities conflict")
                if (!cloudInventoryVerified) add(
                    cloudInventoryFailureReason?.takeIf { it.isNotBlank() }
                        ?: "cloud inventory coverage is not verified"
                )
            }.joinToString("; ").ifBlank { "Scope or evidence could not be verified." }
        }

        return ReportingReconciliation(
            status = status,
            authoritativeOperationCount = if (cloudInventoryVerified) cloudOperations.size else localOperations.size,
            reportableOperationCount = reportable.size,
            representedOperationCount = represented.size,
            missingOperationIds = missing,
            unmatchedRowIds = unmatchedRows,
            operationTypeMismatchRowIds = typeMismatches,
            duplicateOperationIds = duplicateOperationIds,
            duplicateCloudOperationIds = duplicateCloudIds,
            duplicateRowIds = duplicateRows,
            unsupportedOperationIds = unsupported,
            localOnlyOperationIds = localOnly,
            cloudOnlyOperationIds = cloudOnly,
            unresolvedLocalOperationIds = unresolvedLocal,
            identityMismatchOperationIds = identityMismatches,
            excludedOperations = excluded,
            cloudInventoryVerified = cloudInventoryVerified,
            cloudInventoryFailureReason = cloudInventoryFailureReason,
            explanation = explanation
        )
    }

    private fun expectedOperationTypes(row: ReportRow): Set<String> = when (row) {
        is SaleReportRow -> setOf("RECORD_SALE")
        is RefundReportRow -> setOf("RECORD_REFUND")
        is VoidReportRow -> setOf("RECORD_VOID")
        is AmountCorrectionReportRow -> setOf("RECORD_AMOUNT_CORRECTION")
        is PaymentMethodChangeReportRow -> setOf("RECORD_PAYMENT_METHOD_CHANGE")
        is CreditRepaymentReportRow -> setOf("RECORD_CREDIT_REPAYMENT")
        is ExpenseReportRow -> setOf("RECORD_EXPENSE")
        is CouponReportRow -> when (row.eventKind) {
            CouponReportEventKind.PURCHASE,
            CouponReportEventKind.TOP_UP,
            CouponReportEventKind.PROMOTION_BONUS -> setOf("RECORD_COUPON_PURCHASE")
            CouponReportEventKind.REFUND_CREDIT -> setOf("RECORD_REFUND")
            CouponReportEventKind.CREDIT_REPAYMENT -> setOf("RECORD_CREDIT_REPAYMENT")
            CouponReportEventKind.REDEMPTION -> setOf("RECORD_SALE", "RECORD_CREDIT_REPAYMENT")
        }
    }
}
