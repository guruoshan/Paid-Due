package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus

internal enum class InboundOperationAdmission { INSERTED, PENDING, ALREADY_SUCCEEDED }

/** Shared inbound operation completion; suppresses outbound echoes on local status transitions. */
internal object InboundOperationCompletion {
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
            OperationStatus.SUCCEEDED -> InboundOperationAdmission.ALREADY_SUCCEEDED
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
            commandSchemaVersion == expected.commandSchemaVersion && intentPayload == expected.intentPayload &&
            resultReferenceType == expected.resultReferenceType &&
            resultReferenceId == expected.resultReferenceId

    private fun FinancialSyncOutboxEntity.hasSameAcknowledgedIdentity(expected: FinancialSyncOutboxEntity): Boolean =
        operationId == expected.operationId && businessId == expected.businessId && branchId == expected.branchId &&
            transactionId == expected.transactionId && actorUserId == expected.actorUserId &&
            deviceId == expected.deviceId && requestFingerprint == expected.requestFingerprint &&
            commandSchemaVersion == expected.commandSchemaVersion && occurredAt == expected.occurredAt &&
            status == "ACKNOWLEDGED"
}
