package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType

/**
 * Universal Command / Idempotency / Audit Spine.
 * Every business-changing action has an Operation record.
 * 
 * Rules:
 * - One real-world action = one Operation ID = one financial outcome.
 * - command_schema_version and intent_payload enable safe recovery from process crashes while in PENDING state.
 * - Once status = SUCCEEDED, command identity and result are strictly immutable.
 */
@Entity(
    tableName = "operations",
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["cash_shift_id"]),
        Index(value = ["business_id"]),
        Index(value = ["branch_id"]),
        Index(value = ["occurred_at"]),
        Index(value = ["status"])
    ]
)
data class OperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // OperationId (UUID v7)

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String?,

    @ColumnInfo(name = "operation_type")
    val operationType: OperationType,

    @ColumnInfo(name = "subject_type")
    val subjectType: SubjectType,

    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,

    /**
     * Immutable cashier-shift attribution.
     *
     * Historical rows remain NULL. No migration guesses which old
     * transaction belonged to which shift.
     */
    @ColumnInfo(name = "cash_shift_id")
    val cashShiftId: String? = null,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String,

    @ColumnInfo(name = "command_schema_version")
    val commandSchemaVersion: Int = 1,

    @ColumnInfo(name = "intent_payload")
    val intentPayload: String, // Serialized durable payload for crash recovery

    @ColumnInfo(name = "status")
    val status: OperationStatus,

    @ColumnInfo(name = "result_reference_type")
    val resultReferenceType: String? = null,

    @ColumnInfo(name = "result_reference_id")
    val resultReferenceId: String? = null,

    @ColumnInfo(name = "reason")
    val reason: String? = null
)
