package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.AuthorizedUserEventType
import com.dailymoneyregister.core.model.enums.CouponLedgerType

@Entity(
    tableName = "coupon_wallets",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["customer_id"])
    ]
)
data class CouponWalletEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // CouponWalletId (UUID v7)

    @ColumnInfo(name = "customer_id")
    val customerId: String,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String, // Permanent Currency Invariant: wallet belongs to one currency

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "coupon_authorized_users",
    foreignKeys = [
        ForeignKey(
            entity = CouponWalletEntity::class,
            parentColumns = ["id"],
            childColumns = ["wallet_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["wallet_id"])
    ]
)
data class CouponAuthorizedUserEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // CouponAuthorizedUserId (UUID v7)

    @ColumnInfo(name = "wallet_id")
    val walletId: String,

    @ColumnInfo(name = "is_purchaser")
    val isPurchaser: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "coupon_authorized_user_events",
    foreignKeys = [
        ForeignKey(
            entity = CouponAuthorizedUserEntity::class,
            parentColumns = ["id"],
            childColumns = ["authorized_user_id"],
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
        Index(value = ["authorized_user_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class CouponAuthorizedUserEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "authorized_user_id")
    val authorizedUserId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "event_type")
    val eventType: AuthorizedUserEventType,

    @ColumnInfo(name = "name_value")
    val nameValue: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String
)

@Entity(
    tableName = "coupon_ledger_events",
    foreignKeys = [
        ForeignKey(
            entity = CouponWalletEntity::class,
            parentColumns = ["id"],
            childColumns = ["wallet_id"],
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
        Index(value = ["wallet_id"]),
        Index(value = ["operation_id"]),
        Index(value = ["transaction_id"]),
        Index(value = ["occurred_at"])
    ]
)
data class CouponLedgerEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "wallet_id")
    val walletId: String,

    @ColumnInfo(name = "type")
    val type: CouponLedgerType,

    @ColumnInfo(name = "amount_signed_minor")
    val amountSignedMinor: Long, // Positive for PURCHASE (+), BONUS (+), REFUND (+); Negative for REDEMPTION (-)

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,

    @ColumnInfo(name = "authorized_user_id")
    val authorizedUserId: String? = null,

    @ColumnInfo(name = "authorized_user_name_snapshot")
    val authorizedUserNameSnapshot: String? = null,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)
