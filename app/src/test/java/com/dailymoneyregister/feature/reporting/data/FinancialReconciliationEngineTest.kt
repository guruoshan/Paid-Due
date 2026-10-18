package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.FinancialReconciliationEngine
import com.dailymoneyregister.feature.reporting.domain.PaymentMethodChangeReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportBranch
import com.dailymoneyregister.feature.reporting.domain.ReportCashier
import com.dailymoneyregister.feature.reporting.domain.ReportingCloudOperation
import com.dailymoneyregister.feature.reporting.domain.ReportingReconciliationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialReconciliationEngineTest {
    private fun operation(
        id: String = "op-1",
        type: OperationType = OperationType.RECORD_PAYMENT_METHOD_CHANGE,
        status: OperationStatus = OperationStatus.SUCCEEDED
    ) = OperationEntity(
        id = id,
        businessId = "biz-1",
        branchId = "branch-1",
        operationType = type,
        subjectType = if (type.name.contains("CASH_SHIFT")) SubjectType.CASH_SHIFT else SubjectType.TRANSACTION,
        subjectId = "subject-$id",
        transactionId = if (type.name.contains("CASH_SHIFT")) null else "tx-$id",
        cashShiftId = if (type.name.contains("CASH_SHIFT")) "shift-$id" else null,
        actorUserId = "cashier-1",
        deviceId = "device-1",
        occurredAt = 100L,
        requestFingerprint = "fingerprint-$id",
        commandSchemaVersion = 1,
        intentPayload = "{}",
        status = status
    )

    private fun cloud(operation: OperationEntity) = ReportingCloudOperation(
        operationId = operation.id,
        businessId = operation.businessId,
        branchId = operation.branchId.orEmpty(),
        operationType = operation.operationType.name,
        occurredAtMillis = operation.occurredAt,
        actorUserId = operation.actorUserId,
        requestFingerprint = operation.requestFingerprint,
        canonicalSha256 = "verified-test-digest"
    )

    private fun row(operationId: String = "op-1", stableId: String = "change-1") =
        PaymentMethodChangeReportRow(
            stableId = stableId,
            operationId = operationId,
            transactionId = "tx-$operationId",
            reference = "REF-$operationId",
            occurredAt = 100L,
            currencyCode = "OMR",
            branch = ReportBranch("branch-1", "Main"),
            cashier = ReportCashier("cashier-1", "Cashier"),
            person = null,
            eventId = stableId,
            previousCategory = PaymentCategory.CASH,
            previousChannel = PaymentChannel.CASH_TENDER,
            newCategory = PaymentCategory.ONLINE,
            newChannel = PaymentChannel.MANUAL_QR,
            reason = "correction"
        )

    @Test
    fun completeRequiresVerifiedCloudInventoryAndExactIdentityCoverage() {
        val local = listOf(operation())
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = local,
            cloudOperations = local.map(::cloud),
            rows = listOf(row()),
            cloudInventoryVerified = true
        )

        assertEquals(ReportingReconciliationStatus.COMPLETE, result.status)
        assertEquals(0, result.operationCoverageDelta)
        assertEquals(emptyList<String>(), result.missingOperationIds)
        assertEquals(emptyList<String>(), result.unmatchedRowIds)
    }

    @Test
    fun localOnlyInventoryCannotClaimComplete() {
        val local = listOf(operation())
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = local,
            cloudOperations = emptyList(),
            rows = listOf(row()),
            cloudInventoryVerified = false,
            cloudInventoryFailureReason = "test offline"
        )

        assertEquals(ReportingReconciliationStatus.REQUIRES_REVIEW, result.status)
        assertEquals(false, result.cloudInventoryVerified)
    }

    @Test
    fun missingReportRowIsIncompleteWhenCloudInventoryIsVerified() {
        val local = listOf(operation())
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = local,
            cloudOperations = local.map(::cloud),
            rows = emptyList(),
            cloudInventoryVerified = true
        )

        assertEquals(ReportingReconciliationStatus.INCOMPLETE, result.status)
        assertEquals(listOf("op-1"), result.missingOperationIds)
    }

    @Test
    fun rowWithoutMatchingAuthoritativeOperationRequiresReview() {
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = emptyList(),
            cloudOperations = emptyList(),
            rows = listOf(row()),
            cloudInventoryVerified = true
        )

        assertEquals(ReportingReconciliationStatus.REQUIRES_REVIEW, result.status)
        assertEquals(listOf("change-1"), result.unmatchedRowIds)
    }

    @Test
    fun cashShiftIsExplicitlyExcludedFromTransactionDetailCoverage() {
        val local = listOf(operation(type = OperationType.OPEN_CASH_SHIFT))
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = local,
            cloudOperations = local.map(::cloud),
            rows = emptyList(),
            cloudInventoryVerified = true
        )

        assertEquals(ReportingReconciliationStatus.COMPLETE, result.status)
        assertEquals(
            "OPERATIONAL_EVENT_NOT_TRANSACTION_DETAIL",
            result.excludedOperations["op-1"]
        )
    }

    @Test
    fun twentyNineOperationsAndTwentyFiveDetailsClassifyFourCashShiftEventsExplicitly() {
        val financialOperations = (1..25).map { operation(id = "financial-$it") }
        val cashShiftOperations = listOf(
            operation(id = "open-1", type = OperationType.OPEN_CASH_SHIFT),
            operation(id = "open-2", type = OperationType.OPEN_CASH_SHIFT),
            operation(id = "count-1", type = OperationType.COUNT_CASH_SHIFT),
            operation(id = "count-2", type = OperationType.COUNT_CASH_SHIFT)
        )
        val detailRows = financialOperations.map { row(operationId = it.id, stableId = "detail-${it.id}") }

        val local = financialOperations + cashShiftOperations
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = local,
            cloudOperations = local.map(::cloud),
            rows = detailRows,
            cloudInventoryVerified = true
        )

        assertEquals(29, result.authoritativeOperationCount)
        assertEquals(25, result.reportableOperationCount)
        assertEquals(25, result.representedOperationCount)
        assertEquals(0, result.operationCoverageDelta)
        assertEquals(4, result.excludedOperations.size)
        assertEquals(ReportingReconciliationStatus.COMPLETE, result.status)
    }

    @Test
    fun duplicateReportRowIdentityRequiresReview() {
        val local = listOf(operation())
        val result = FinancialReconciliationEngine.reconcile(
            localOperations = local,
            cloudOperations = local.map(::cloud),
            rows = listOf(row(), row()),
            cloudInventoryVerified = true
        )

        assertEquals(ReportingReconciliationStatus.REQUIRES_REVIEW, result.status)
        assertEquals(listOf("change-1"), result.duplicateRowIds)
    }
}
