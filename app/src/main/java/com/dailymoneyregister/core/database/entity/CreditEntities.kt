package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.CreditLedgerType
import com.dailymoneyregister.core.model.enums.PaymentCategory

@Entity(
    tableName = "credit_ledger_events",
    foreignKeys = [
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["operation_id"]),
        Index(value = ["customer_id"]),
        Index(value = ["transaction_id"]),
        Index(value = ["repayment_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class CreditLedgerEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "customer_id")
    val customerId: String,

    @ColumnInfo(name = "type")
    val type: CreditLedgerType,

    @ColumnInfo(name = "amount_signed_minor")
    val amountSignedMinor: Long, // Positive for obligation (+), Negative for repayment (-), Signed for adjustment (+/-)

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,

    @ColumnInfo(name = "repayment_id")
    val repaymentId: String? = null,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)

@Entity(
    tableName = "credit_repayments",
    foreignKeys = [
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["operation_id"]),
        Index(value = ["customer_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class CreditRepaymentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // CreditRepaymentId (UUID v7)

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "customer_id")
    val customerId: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long, // Positive amount collected

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "payment_category")
    val paymentCategory: PaymentCategory, // CASH or ONLINE

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String
)

@Entity(
    tableName = "credit_repayment_allocations",
    foreignKeys = [
        ForeignKey(
            entity = CreditRepaymentEntity::class,
            parentColumns = ["id"],
            childColumns = ["repayment_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["original_transaction_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["repayment_id"]),
        Index(value = ["original_transaction_id"])
    ]
)
data class CreditRepaymentAllocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "repayment_id")
    val repaymentId: String,

    @ColumnInfo(name = "original_transaction_id")
    val originalTransactionId: String,

    @ColumnInfo(name = "allocated_amount_minor")
    val allocatedAmountMinor: Long, // Positive amount allocated to this sale

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)
