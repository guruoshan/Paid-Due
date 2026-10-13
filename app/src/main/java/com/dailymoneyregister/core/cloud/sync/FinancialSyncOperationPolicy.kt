package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.UserRole

object FinancialSyncOperationPolicy {
    val adminUploadable = setOf(
        OperationType.RECORD_SALE,
        OperationType.RECORD_CREDIT_REPAYMENT,
        OperationType.RECORD_COUPON_PURCHASE,
        OperationType.RECORD_AMOUNT_CORRECTION,
        OperationType.RECORD_PAYMENT_METHOD_CHANGE,
        OperationType.RECORD_VOID,
        OperationType.RECORD_REFUND,
        OperationType.RECORD_EXPENSE,
        OperationType.RECORD_EXPENSE_CORRECTION,
        OperationType.RECORD_EXPENSE_VOID,
        OperationType.OPEN_CASH_SHIFT,
        OperationType.COUNT_CASH_SHIFT,
        OperationType.CLOSE_CASH_SHIFT
    )

    val cashierUploadable = setOf(
        OperationType.RECORD_SALE,
        OperationType.RECORD_CREDIT_REPAYMENT,
        OperationType.RECORD_COUPON_PURCHASE,
        OperationType.RECORD_REFUND,
        OperationType.RECORD_VOID,
        OperationType.RECORD_AMOUNT_CORRECTION,
        OperationType.RECORD_PAYMENT_METHOD_CHANGE,
        OperationType.OPEN_CASH_SHIFT,
        OperationType.COUNT_CASH_SHIFT,
        OperationType.CLOSE_CASH_SHIFT
    )

    fun initialState(role: UserRole, type: OperationType): FinancialSyncDeliveryState =
        when {
            role == UserRole.ADMIN && type in adminUploadable -> FinancialSyncDeliveryState.QUEUED
            role == UserRole.CASHIER && type in cashierUploadable -> FinancialSyncDeliveryState.QUEUED
            role == UserRole.CASHIER && type in adminUploadable -> FinancialSyncDeliveryState.NOT_SYNCABLE_YET
            else -> FinancialSyncDeliveryState.NOT_APPLICABLE
        }

    fun canUpload(role: UserRole, type: OperationType): Boolean =
        initialState(role, type) == FinancialSyncDeliveryState.QUEUED
}
