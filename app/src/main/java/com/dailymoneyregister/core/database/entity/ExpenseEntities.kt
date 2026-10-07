package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expense_payees",
    indices = [
        Index(value = ["business_id", "normalized_name"]),
        Index(value = ["business_id", "canonical_phone"]),
        Index(value = ["business_id", "account_reference"])
    ]
)
data class ExpensePayeeEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    val name: String,

    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,

    @ColumnInfo(name = "canonical_phone")
    val canonicalPhone: String? = null,

    @ColumnInfo(name = "account_reference")
    val accountReference: String? = null,

    @ColumnInfo(name = "payee_type")
    val payeeType: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "created_by_user_id")
    val createdByUserId: String
)

@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["business_id", "branch_id", "occurred_at"]),
        Index(value = ["payee_id"]),
        Index(value = ["cash_shift_id"]),
        Index(value = ["operation_id"], unique = true)
    ]
)
data class ExpenseEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "cash_shift_id")
    val cashShiftId: String?,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "payee_id")
    val payeeId: String?,

    @ColumnInfo(name = "payee_name_snapshot")
    val payeeNameSnapshot: String,

    @ColumnInfo(name = "payee_phone_snapshot")
    val payeePhoneSnapshot: String?,

    @ColumnInfo(name = "payee_account_snapshot")
    val payeeAccountSnapshot: String?,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    @ColumnInfo(name = "payment_category")
    val paymentCategory: String,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "reference")
    val reference: String?,

    @ColumnInfo(name = "note")
    val note: String?,

    @ColumnInfo(name = "recurrence_id")
    val recurrenceId: String?,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "expense_events",
    indices = [
        Index(value = ["expense_id", "occurred_at"]),
        Index(value = ["operation_id"], unique = true)
    ]
)
data class ExpenseEventEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String,

    @ColumnInfo(name = "previous_amount_minor")
    val previousAmountMinor: Long? = null,

    @ColumnInfo(name = "new_amount_minor")
    val newAmountMinor: Long? = null,

    @ColumnInfo(name = "previous_payment_category")
    val previousPaymentCategory: String? = null,

    @ColumnInfo(name = "new_payment_category")
    val newPaymentCategory: String? = null,

    @ColumnInfo(name = "previous_category")
    val previousCategory: String? = null,

    @ColumnInfo(name = "new_category")
    val newCategory: String? = null,

    @ColumnInfo(name = "previous_payee_id")
    val previousPayeeId: String? = null,

    @ColumnInfo(name = "new_payee_id")
    val newPayeeId: String? = null,

    val reason: String? = null,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)

@Entity(
    tableName = "expense_recurrences",
    indices = [
        Index(value = ["business_id", "branch_id"]),
        Index(value = ["payee_id"])
    ]
)
data class ExpenseRecurrenceEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "payee_id")
    val payeeId: String?,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "default_amount_minor")
    val defaultAmountMinor: Long?,

    @ColumnInfo(name = "default_payment_category")
    val defaultPaymentCategory: String?,

    @ColumnInfo(name = "frequency")
    val frequency: String,

    @ColumnInfo(name = "next_due_at")
    val nextDueAt: Long,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "created_by_user_id")
    val createdByUserId: String
)