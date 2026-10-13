package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialSyncOperationPolicyTest {
    @Test
    fun roleAwareStatesFailClosed() {
        assertEquals(
            FinancialSyncDeliveryState.QUEUED,
            FinancialSyncOperationPolicy.initialState(UserRole.CASHIER, OperationType.RECORD_SALE)
        )
        assertEquals(
            FinancialSyncDeliveryState.NOT_SYNCABLE_YET,
            FinancialSyncOperationPolicy.initialState(UserRole.CASHIER, OperationType.RECORD_EXPENSE)
        )
        assertEquals(
            FinancialSyncDeliveryState.QUEUED,
            FinancialSyncOperationPolicy.initialState(UserRole.CASHIER, OperationType.CLOSE_CASH_SHIFT)
        )
        assertEquals(
            FinancialSyncDeliveryState.NOT_APPLICABLE,
            FinancialSyncOperationPolicy.initialState(UserRole.ADMIN, OperationType.CREATE_CASHIER)
        )
    }
}
