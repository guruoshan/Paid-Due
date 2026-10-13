package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundApplicationGateTest {
    @Test
    fun releaseDefaultsToCashSalesOnlyEvenWhenReleaseFlagIsEnabled() {
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_SALE", true, null))
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_REFUND", true, null))
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_CREDIT_REPAYMENT", true, null))
    }

    @Test
    fun debugBuildFlagEnablesExpandedMaterializationForTesting() {
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_REFUND", true, null, debugBuild = true))
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
