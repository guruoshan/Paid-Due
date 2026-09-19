package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType

/**
 * Immutable original transaction snapshot.
 * Historical records must NEVER be mutated or silently overwritten.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branch_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["branch_id"]),
        Index(value = ["customer_id"]),
        Index(value = ["created_at"]),
        Index(value = ["display_ref"])
    ]
)
data class TransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // TransactionId (UUID v7)

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "customer_id")
    val customerId: String? = null,

    @ColumnInfo(name = "subtotal_minor")
    val subtotalMinor: Long,

    @ColumnInfo(name = "tax_rate_bps")
    val taxRateBps: Int,

    @ColumnInfo(name = "taxable_base_minor")
    val taxableBaseMinor: Long,

    @ColumnInfo(name = "tax_amount_minor")
    val taxAmountMinor: Long,

    @ColumnInfo(name = "tx_shipping_minor")
    val txShippingMinor: Long = 0L,

    @ColumnInfo(name = "tx_packing_minor")
    val txPackingMinor: Long = 0L,

    @ColumnInfo(name = "grand_total_minor")
    val grandTotalMinor: Long,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "currency_decimal_places")
    val currencyDecimalPlaces: Int,

    @ColumnInfo(name = "created_by_user_id")
    val createdByUserId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "display_ref")
    val displayRef: String // Offline-safe human reference
)

@Entity(
    tableName = "transaction_items",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["transaction_id"])
    ]
)
data class TransactionItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // ItemId (UUID v7)

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "product_id")
    val productId: String? = null,

    @ColumnInfo(name = "product_name_snapshot")
    val productNameSnapshot: String,

    @ColumnInfo(name = "description_snapshot")
    val descriptionSnapshot: String? = null,

    @ColumnInfo(name = "quantity")
    val quantity: Int = 1,

    @ColumnInfo(name = "unit_price_minor")
    val unitPriceMinor: Long,

    @ColumnInfo(name = "line_total_minor")
    val lineTotalMinor: Long,

    @ColumnInfo(name = "barcode_snapshot")
    val barcodeSnapshot: String? = null,

    @ColumnInfo(name = "media_id")
    val mediaId: String? = null,

    @ColumnInfo(name = "item_packing_charge_minor")
    val itemPackingChargeMinor: Long = 0L,

    @ColumnInfo(name = "item_shipping_charge_minor")
    val itemShippingChargeMinor: Long = 0L
)

/**
 * Universal signed settlement/funding entries.
 * Represents all money/value movements (sales, repayments, refunds, corrections).
 */
@Entity(
    tableName = "settlement_entries",
    foreignKeys = [
        ForeignKey(
            entity = OperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["operation_id"]),
        Index(value = ["transaction_id"]),
        Index(value = ["subject_type", "subject_id"]),
        Index(value = ["category"]),
        Index(value = ["entry_purpose"]),
        Index(value = ["occurred_at"])
    ]
)
data class SettlementEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // SettlementId (UUID v7)

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "subject_type")
    val subjectType: SubjectType,

    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,

    @ColumnInfo(name = "entry_purpose")
    val entryPurpose: EntryPurpose,

    @ColumnInfo(name = "category")
    val category: PaymentCategory,

    @ColumnInfo(name = "channel")
    val channel: PaymentChannel,

    @ColumnInfo(name = "amount_signed_minor")
    val amountSignedMinor: Long, // SIGNED: positive for inflow/funding, negative for reversal/outflow

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "confirmation_type")
    val confirmationType: ConfirmationType,

    @ColumnInfo(name = "qr_profile_id_snapshot")
    val qrProfileIdSnapshot: String? = null,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "provider_ref")
    val providerRef: String? = null
)
