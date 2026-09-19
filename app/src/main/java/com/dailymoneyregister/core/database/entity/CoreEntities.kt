package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode

@Entity(
    tableName = "human_identities",
    indices = [Index(value = ["canonical_phone"], unique = true)]
)
data class HumanIdentityEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "canonical_phone") val canonicalPhone: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "businesses"
)
data class BusinessEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "branches",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["id"],
            childColumns = ["business_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["business_id"])
    ]
)
data class BranchEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "code")
    val code: String, // e.g. "MAIN", "SAL"

    @ColumnInfo(name = "country_code")
    val countryCode: String, // "OM", "IN", etc.

    @ColumnInfo(name = "currency_code")
    val currencyCode: String, // "OMR", "INR", etc.

    @ColumnInfo(name = "currency_decimal_places")
    val currencyDecimalPlaces: Int, // 3 for OMR, 2 for INR

    @ColumnInfo(name = "currency_symbol")
    val currencySymbol: String,

    @ColumnInfo(name = "timezone_id")
    val timezoneId: String, // "Asia/Muscat", "Asia/Kolkata"

    @ColumnInfo(name = "tax_rounding_mode")
    val taxRoundingMode: TaxRoundingMode = TaxRoundingMode.HALF_UP,

    @ColumnInfo(name = "default_tax_rate_bps")
    val defaultTaxRateBps: Int = 0, // e.g. 500 for 5.00%

    @ColumnInfo(name = "address")
    val address: String? = null,

    @ColumnInfo(name = "phone")
    val phone: String? = null,

    @ColumnInfo(name = "tax_registration_number")
    val taxRegistrationNumber: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "user_accounts",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["id"],
            childColumns = ["business_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["business_id", "username"], unique = true),
        Index(value = ["business_id"])
    ]
)
data class UserAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "identity_id")
    val identityId: String? = null,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "role")
    val role: UserRole,

    @ColumnInfo(name = "auth_type")
    val authType: AuthType,

    @ColumnInfo(name = "credential_hash")
    val credentialHash: String,

    @ColumnInfo(name = "credential_salt")
    val credentialSalt: String?,

    @ColumnInfo(name = "language_code")
    val languageCode: String = "en",

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "must_change_pin")
    val mustChangePin: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "user_branch_memberships",
    foreignKeys = [
        ForeignKey(
            entity = UserAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branch_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["user_id", "branch_id"], unique = true),
        Index(value = ["branch_id"])
    ]
)
data class UserBranchMembershipEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "role")
    val role: UserRole,

    @ColumnInfo(name = "can_create_sale")
    val canCreateSale: Boolean = true,

    @ColumnInfo(name = "can_accept_cash")
    val canAcceptCash: Boolean = true,

    @ColumnInfo(name = "can_accept_online")
    val canAcceptOnline: Boolean = true,

    @ColumnInfo(name = "can_create_credit_sale")
    val canCreateCreditSale: Boolean = false,

    @ColumnInfo(name = "can_use_coupon")
    val canUseCoupon: Boolean = false,

    @ColumnInfo(name = "can_issue_invoice")
    val canIssueInvoice: Boolean = true,

    @ColumnInfo(name = "can_issue_receipt")
    val canIssueReceipt: Boolean = true,

    @ColumnInfo(name = "can_refund")
    val canRefund: Boolean = false,

    @ColumnInfo(name = "can_void")
    val canVoid: Boolean = false,

    @ColumnInfo(name = "can_correct_amount")
    val canCorrectAmount: Boolean = false,

    @ColumnInfo(name = "can_change_payment_method")
    val canChangePaymentMethod: Boolean = false,

    @ColumnInfo(name = "can_view_broad_history")
    val canViewBroadHistory: Boolean = false,

    @ColumnInfo(name = "can_manage_products")
    val canManageProducts: Boolean = false,

    @ColumnInfo(name = "can_view_reports")
    val canViewReports: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "customers",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["id"],
            childColumns = ["business_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["business_id", "canonical_phone"], unique = true),
        Index(value = ["business_id"])
    ]
)
data class CustomerEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "canonical_phone")
    val canonicalPhone: String, // E.164 format: "+96891234567"

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branch_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["branch_id", "barcode"], unique = true),
        Index(value = ["branch_id"])
    ]
)
data class ProductEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "barcode")
    val barcode: String? = null,

    @ColumnInfo(name = "unit_price_minor")
    val unitPriceMinor: Long,

    @ColumnInfo(name = "media_id")
    val mediaId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
