package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialSyncStatusTest {
    @Test
    fun unsupportedCountIsUniquePerBusinessAndOperation() {
        val business = "status-test-business"
        FinancialSyncStatus.recordUnsupported(null, business, "op-a")
        FinancialSyncStatus.recordUnsupported(null, business, "op-a")
        FinancialSyncStatus.recordUnsupported(null, business, "op-b")
        assertEquals(2, FinancialSyncStatus.unsupportedInboundCounts.value[business])
    }
}
