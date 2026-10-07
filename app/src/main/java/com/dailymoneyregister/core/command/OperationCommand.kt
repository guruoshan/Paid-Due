package com.dailymoneyregister.core.command

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

/**
 * Universal Command interface representing durable intent.
 * 
 * Rules:
 * - Each command carries a permanent OperationId generated at intent creation.
 * - requestFingerprint guards against payload tampering for the same OperationId.
 * - intentPayload contains serialized parameters needed to recover the operation if interrupted.
 */
interface OperationCommand {
    val operationId: OperationId
    val businessId: String
    val branchId: String?
    val operationType: OperationType
    val subjectType: SubjectType
    val subjectId: String
    val transactionId: String?

    /**
     * Immutable cashier-shift attribution for an admitted operation.
     *
     * Null is valid for:
     * - historical operations created before shift attribution
     * - operations that do not belong to a cashier financial shift
     *
     * New cashier financial operations must explicitly freeze this value
     * before their durable Operation row is admitted.
     */
    val cashShiftId: String? get() = null

    val actorUserId: String
    val deviceId: String
    val occurredAt: Long
    val commandSchemaVersion: Int get() = 1
    val requestFingerprint: String
    val intentPayload: String
}

sealed class CommandResult<out T> {
    data class Success<T>(val data: T, val isIdempotentReplay: Boolean = false) : CommandResult<T>()
    data class Conflict(val message: String) : CommandResult<Nothing>()
    data class PermissionDenied(val message: String) : CommandResult<Nothing>()
    data class Error(val exception: Throwable) : CommandResult<Nothing>()
}
