package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable delivery metadata for a cash-sale operation.
 *
 * Does not replace or duplicate authoritative financial evidence.
 * No financial amount is stored in this table.
 */
@Entity(
    tableName = "financial_sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["occurred_at"])
    ]
)
data class FinancialSyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String,

    @ColumnInfo(name = "command_schema_version")
    val commandSchemaVersion: Int,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int
)