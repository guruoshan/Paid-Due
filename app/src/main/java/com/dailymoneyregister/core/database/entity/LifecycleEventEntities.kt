package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.PaymentCategory

@Entity(
    tableName = "amount_correction_events",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class AmountCorrectionEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "previous_amount_minor")
    val previousAmountMinor: Long,

    @ColumnInfo(name = "corrected_amount_minor")
    val correctedAmountMinor: Long,

    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)

@Entity(
    tableName = "payment_method_change_events",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class PaymentMethodChangeEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "previous_method")
    val previousMethod: PaymentCategory,

    @ColumnInfo(name = "new_method")
    val newMethod: PaymentCategory,

    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)

@Entity(
    tableName = "void_events",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class VoidEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)

@Entity(
    tableName = "refund_events",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class RefundEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "refund_amount_minor")
    val refundAmountMinor: Long,

    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)
