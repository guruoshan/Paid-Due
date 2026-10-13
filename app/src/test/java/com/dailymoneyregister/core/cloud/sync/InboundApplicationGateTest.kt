package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundApplicationGateTest {
    @Test
    fun releaseDefaultsToCashSalesOnly() {
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_SALE", false, null))
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_REFUND", false, null))
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_CREDIT_REPAYMENT", false, null))
    }

    @Test
    fun explicitBuildFlagEnablesApprovedExpandedMaterialization() {
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_REFUND", true, null))
    }

    @Test
    fun explicitOverrideCanDisableEvenCashSaleInbound() {
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_SALE", true, false))
    }

    @Test
    fun explicitTestOverrideCanEnableOperation() {
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_REFUND", false, true))
    }
}
