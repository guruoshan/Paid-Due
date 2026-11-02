package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import org.json.JSONObject

internal enum class InboundOperationAdmission { INSERTED, PENDING, ALREADY_SUCCEEDED }

/** Shared inbound operation completion; suppresses outbound echoes on local status transitions. */
internal object InboundOperationCompletion {
    /**
     * Validates an already-recorded operation before stateful evidence validators run.
     * A matching successful replay can then be skipped without reapplying its evidence.
     */
    suspend fun isExistingReplay(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        businessId: String,
        branchId: String,
        actorUserId: String
    ): Boolean {
        val existing = database.operationDao().getOperationById(envelope.operationId) ?: return false
        val expected = expectedOperation(envelope, businessId, branchId, actorUserId)
        check(existing.hasSameImmutableIdentity(expected)) {
            "Inbound operation immutable identity mismatch"
        }
        return when (existing.status) {
            OperationStatus.SUCCEEDED -> {
                check(existing.hasSameResultReference(expected)) {
                    "Inbound operation result reference mismatch"
                }
                true
            }
            OperationStatus.PENDING -> false
            else -> error("Inbound operation cannot replay from ${existing.status}")
        }
    }

    private fun expectedOperation(
        envelope: RemoteEnvelopeV2,
        businessId: String,
        branchId: String,
        actorUserId: String
    ): OperationEntity {
        val payload = envelope.payloadJson
        val operationType = OperationType.valueOf(envelope.operationType)
        val evidence = payload.optJSONObject("financial_evidence") ?: JSONObject()
        val transactionOperation = operationType in setOf(
            OperationType.RECORD_SALE,
            OperationType.RECORD_COUPON_PURCHASE,
            OperationType.RECORD_AMOUNT_CORRECTION,
            OperationType.RECORD_PAYMENT_METHOD_CHANGE,
            OperationType.RECORD_VOID,
            OperationType.RECORD_REFUND
        )
        val transaction = payload.optJSONObject("transaction")
        val transactionId = if (transactionOperation) {
            requireNotNull(transaction?.optString("id")?.takeIf { it.isNotBlank() })
        } else {
            null
        }
        val defaultSubjectType = when (operationType) {
            OperationType.RECORD_CREDIT_REPAYMENT -> SubjectType.REPAYMENT.name
            OperationType.OPEN_CASH_SHIFT,
            OperationType.COUNT_CASH_SHIFT,
            OperationType.CLOSE_CASH_SHIFT -> SubjectType.CASH_SHIFT.name
            OperationType.RECORD_EXPENSE,
            OperationType.RECORD_EXPENSE_CORRECTION,
            OperationType.RECORD_EXPENSE_VOID -> SubjectType.EXPENSE.name
            else -> SubjectType.TRANSACTION.name
        }
        val subjectType = SubjectType.valueOf(payload.optString("subject_type").ifBlank { defaultSubjectType })
        val subjectId = payload.optString("subject_id").ifBlank {
            when (operationType) {
                OperationType.RECORD_CREDIT_REPAYMENT ->
                    evidence.optJSONObject("credit_repayment")?.optString("id").orEmpty()
                OperationType.OPEN_CASH_SHIFT,
                OperationType.COUNT_CASH_SHIFT,
                OperationType.CLOSE_CASH_SHIFT ->
                    evidence.optJSONObject("cash_shift")?.optString("id").orEmpty()
                OperationType.RECORD_EXPENSE ->
                    payload.optJSONObject("expense")?.optString("id").orEmpty()
                OperationType.RECORD_EXPENSE_CORRECTION,
                OperationType.RECORD_EXPENSE_VOID ->
                    evidence.optJSONObject("expense_snapshot")?.optString("id").orEmpty()
                else -> transactionId.orEmpty()
            }
        }.ifBlank { envelope.operationId }
        val cashShiftLifecycle = operationType == OperationType.OPEN_CASH_SHIFT ||
            operationType == OperationType.COUNT_CASH_SHIFT ||
            operationType == OperationType.CLOSE_CASH_SHIFT
        val cashShiftId = if (cashShiftLifecycle) null else {
            payload.optString("cash_shift_id").ifBlank { null }
        }
        val resultReference = if (!transactionOperation) {
            subjectType.name to subjectId
        } else {
            val transactionRef = requireNotNull(transactionId)
            when (operationType) {
                OperationType.RECORD_REFUND ->
                    "REFUND_EVENT" to evidence.getJSONObject("refund_event").getString("id")
                OperationType.RECORD_VOID ->
                    "VOID_EVENT" to evidence.getJSONObject("void_event").getString("id")
                OperationType.RECORD_AMOUNT_CORRECTION ->
                    "AMOUNT_CORRECTION_EVENT" to
                        evidence.getJSONArray("amount_correction_events").getJSONObject(0).getString("id")
                OperationType.RECORD_PAYMENT_METHOD_CHANGE ->
                    "PAYMENT_METHOD_CHANGE_EVENT" to
                        evidence.getJSONArray("payment_method_change_events").getJSONObject(0).getString("id")
                else -> "TRANSACTION" to transactionRef
            }
        }
        val occurredAt = java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()
        val deviceId = when {
            transactionOperation -> payload.optString("device_id", "remote")
            operationType == OperationType.RECORD_EXPENSE -> payload.getString("device_id")
            else -> payload.optString("device_id").ifBlank { "remote" }
        }
        return OperationEntity(
            id = envelope.operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = operationType,
            subjectType = subjectType,
            subjectId = subjectId,
            transactionId = transactionId,
            cashShiftId = cashShiftId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            occurredAt = occurredAt,
            requestFingerprint = payload.optString("request_fingerprint").ifBlank {
                "remote-${envelope.operationId}"
            },
            commandSchemaVersion = payload.optInt("command_schema_version", 1),
            intentPayload = payload.optString("intent_payload", "{}"),
            status = OperationStatus.SUCCEEDED,
            resultReferenceType = resultReference.first,
            resultReferenceId = resultReference.second
        )
    }

    suspend fun ensure(database: DmrDatabase, expected: OperationEntity): InboundOperationAdmission {
        require(expected.status == OperationStatus.SUCCEEDED)
        require(!expected.resultReferenceType.isNullOrBlank() && !expected.resultReferenceId.isNullOrBlank())
        val dao = database.operationDao()
        val existing = dao.getOperationById(expected.id)
        if (existing == null) {
            check(dao.insertOperationIfAbsent(expected) != -1L) { "Inbound operation could not be inserted" }
            return InboundOperationAdmission.INSERTED
        }
        check(existing.hasSameImmutableIdentity(expected)) { "Inbound operation immutable identity mismatch" }
        return when (existing.status) {
            OperationStatus.SUCCEEDED -> {
                check(existing.hasSameResultReference(expected)) {
                    "Inbound operation result reference mismatch"
                }
                InboundOperationAdmission.ALREADY_SUCCEEDED
            }
            OperationStatus.PENDING -> InboundOperationAdmission.PENDING
            else -> error("Inbound operation cannot complete from ${existing.status}")
        }
    }

    suspend fun completePending(database: DmrDatabase, operation: OperationEntity) {
        val branchId = requireNotNull(operation.branchId)
        val referenceId = requireNotNull(operation.resultReferenceId)
        val referenceType = requireNotNull(operation.resultReferenceType)
        val acknowledged = FinancialSyncOutboxEntity(
            operationId = operation.id, businessId = operation.businessId, branchId = branchId,
            transactionId = operation.transactionId ?: referenceId, actorUserId = operation.actorUserId, deviceId = operation.deviceId,
            requestFingerprint = operation.requestFingerprint, commandSchemaVersion = operation.commandSchemaVersion,
            occurredAt = operation.occurredAt, status = "ACKNOWLEDGED", attemptCount = 0
        )
        val outbox = database.financialSyncOutboxDao()
        val existing = outbox.findByOperationId(operation.id)
        if (existing == null) {
            val inserted = outbox.insertOutbox(acknowledged)
            if (inserted == -1L) {
                val raced = outbox.findByOperationId(operation.id)
                check(raced?.hasSameAcknowledgedIdentity(acknowledged) == true) {
                    "Inbound operation found a conflicting outbound outbox row"
                }
            }
        } else {
            check(existing.hasSameAcknowledgedIdentity(acknowledged)) {
                "Inbound operation found an unexpected outbound outbox row"
            }
        }
        val dao = database.operationDao()
        val updated = dao.markSucceededIfPending(operation.id, operation.requestFingerprint, referenceType, referenceId)
        check(updated == 1 || dao.isOperationSucceeded(operation.id)) {
            "Inbound pending operation could not be finalized"
        }
    }

    private fun OperationEntity.hasSameImmutableIdentity(expected: OperationEntity): Boolean =
        id == expected.id && businessId == expected.businessId && branchId == expected.branchId &&
            operationType == expected.operationType && subjectType == expected.subjectType &&
            subjectId == expected.subjectId && transactionId == expected.transactionId &&
            cashShiftId == expected.cashShiftId && actorUserId == expected.actorUserId &&
            deviceId == expected.deviceId && occurredAt == expected.occurredAt &&
            requestFingerprint == expected.requestFingerprint &&
            commandSchemaVersion == expected.commandSchemaVersion && intentPayload == expected.intentPayload

    private fun OperationEntity.hasSameResultReference(expected: OperationEntity): Boolean =
        resultReferenceType == expected.resultReferenceType &&
            resultReferenceId == expected.resultReferenceId

    private fun FinancialSyncOutboxEntity.hasSameAcknowledgedIdentity(expected: FinancialSyncOutboxEntity): Boolean =
        operationId == expected.operationId && businessId == expected.businessId && branchId == expected.branchId &&
            transactionId == expected.transactionId && actorUserId == expected.actorUserId &&
            deviceId == expected.deviceId && requestFingerprint == expected.requestFingerprint &&
            commandSchemaVersion == expected.commandSchemaVersion && occurredAt == expected.occurredAt &&
            status == "ACKNOWLEDGED"
}
