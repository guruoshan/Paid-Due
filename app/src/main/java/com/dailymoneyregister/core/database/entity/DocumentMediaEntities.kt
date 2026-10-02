package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.DocumentType
import com.dailymoneyregister.core.model.enums.MediaType
import com.dailymoneyregister.core.model.enums.SubjectType

@Entity(
    tableName = "documents",
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
        Index(value = ["transaction_id"]),
        Index(value = ["subject_type", "subject_id"]),
        Index(value = ["display_number"]),
        Index(value = ["customer_id"])
    ]
)
data class DocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // DocumentId (UUID v7)

    @ColumnInfo(name = "document_type")
    val documentType: DocumentType,

    @ColumnInfo(name = "subject_type")
    val subjectType: SubjectType,

    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "snapshot_json")
    val snapshotJson: String, // Immutable snapshot of business/customer/items/tax at issuance time

    @ColumnInfo(name = "display_number")
    val displayNumber: String, // e.g. "INV-SAL-2026-000123"

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "created_by_user_id")
    val createdByUserId: String,

    /**
     * The customer this document was issued to. Nullable: receipts and
     * invoices created before schema v9 have no link.
     */
    @ColumnInfo(name = "customer_id")
    val customerId: String? = null
)

@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["id"],
            childColumns = ["business_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["business_id"]),
        Index(value = ["branch_id"])
    ]
)
data class MediaAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // MediaId (UUID v7)

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String? = null,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    @ColumnInfo(name = "local_reference")
    val localReference: String, // Local filesystem path or URI

    @ColumnInfo(name = "cloud_reference")
    val cloudReference: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "merchant_qr_configs",
    foreignKeys = [
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branch_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["branch_id"]),
        Index(value = ["media_id"])
    ]
)
data class MerchantQrConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // QrProfileId (UUID v7)

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "display_label")
    val displayLabel: String,

    @ColumnInfo(name = "bank_app_name")
    val bankAppName: String,

    @ColumnInfo(name = "account_label")
    val accountLabel: String? = null,

    @ColumnInfo(name = "media_id")
    val mediaId: String,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
