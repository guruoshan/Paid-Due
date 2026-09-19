package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.CashShiftEventType

/**
 * CashShift identity record.
 * Contains no editable financial status or expected cash.
 * Expected cash is derived from opening cash + cash inflows - cash outflows.
 */
@Entity(
    tableName = "cash_shifts",
    foreignKeys = [
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branch_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UserAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["branch_id"]),
        Index(value = ["user_id"])
    ]
)
data class CashShiftEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // CashShiftId (UUID v7)

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "cash_shift_events",
    foreignKeys = [
        ForeignKey(
            entity = CashShiftEntity::class,
            parentColumns = ["id"],
            childColumns = ["shift_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["shift_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class CashShiftEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "shift_id")
    val shiftId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "type")
    val type: CashShiftEventType,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String
)
