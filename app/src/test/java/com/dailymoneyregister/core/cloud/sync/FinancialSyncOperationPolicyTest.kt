package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialSyncOperationPolicyTest {
    @Test
    fun onlyAdminFinancialOperationsEnterTheUploadQueue() {
        listOf(
            OperationType.RECORD_SALE,
            OperationType.RECORD_EXPENSE,
            OperationType.CLOSE_CASH_SHIFT
        ).forEach { type ->
            assertEquals(
                FinancialSyncDeliveryState.QUEUED,
                FinancialSyncOperationPolicy.initialState(UserRole.ADMIN, type)
            )
            assertEquals(
                FinancialSyncDeliveryState.NOT_APPLICABLE,
                FinancialSyncOperationPolicy.initialState(UserRole.CASHIER, type)
            )
        }
        assertEquals(
            FinancialSyncDeliveryState.NOT_APPLICABLE,
            FinancialSyncOperationPolicy.initialState(UserRole.ADMIN, OperationType.CREATE_CASHIER)
        )
    }
}
