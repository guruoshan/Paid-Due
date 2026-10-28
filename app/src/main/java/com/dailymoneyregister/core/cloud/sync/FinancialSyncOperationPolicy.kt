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

    /**
     * Only ADMIN-created financial operations may enter the active upload queue.
     * The legacy role enum remains for decoding old outbox/history rows; it does
     * not grant upload capability.
     */
    fun initialState(role: UserRole, type: OperationType): FinancialSyncDeliveryState =
        if (role == UserRole.ADMIN && type in adminUploadable) {
            FinancialSyncDeliveryState.QUEUED
        } else {
            FinancialSyncDeliveryState.NOT_APPLICABLE
        }

    fun canUpload(role: UserRole, type: OperationType): Boolean =
        initialState(role, type) == FinancialSyncDeliveryState.QUEUED
}
