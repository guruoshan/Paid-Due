package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialSyncOperationPolicyTest {
    private val expectedFinancialOperations = setOf(
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

    @Test
    fun adminUploadAllowlistContainsExactlyAllThirteenFinancialOperations() {
        assertEquals(13, expectedFinancialOperations.size)
        assertEquals(expectedFinancialOperations, FinancialSyncOperationPolicy.adminUploadable)
    }

    @Test
    fun everyFinancialOperationIsQueuedOnlyForAdmin() {
        expectedFinancialOperations.forEach { type ->
            assertEquals(
                "$type must be queued for ADMIN",
                FinancialSyncDeliveryState.QUEUED,
                FinancialSyncOperationPolicy.initialState(UserRole.ADMIN, type)
            )
            assertEquals(
                "$type must not be queued for the retired CASHIER role",
                FinancialSyncDeliveryState.NOT_APPLICABLE,
                FinancialSyncOperationPolicy.initialState(UserRole.CASHIER, type)
            )
        }
    }

    @Test
    fun nonFinancialOperationsNeverEnterTheFinancialUploadQueue() {
        OperationType.values()
            .filterNot { it in expectedFinancialOperations }
            .forEach { type ->
                assertEquals(
                    "$type is not a financial upload operation",
                    FinancialSyncDeliveryState.NOT_APPLICABLE,
                    FinancialSyncOperationPolicy.initialState(UserRole.ADMIN, type)
                )
            }
    }
}
