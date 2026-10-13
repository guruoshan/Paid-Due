package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundApplicationGateTest {
    private val releaseMaterializerOperations = setOf(
        "RECORD_SALE",
        "RECORD_CREDIT_REPAYMENT",
        "RECORD_COUPON_PURCHASE",
        "RECORD_AMOUNT_CORRECTION",
        "RECORD_PAYMENT_METHOD_CHANGE",
        "RECORD_VOID",
        "RECORD_REFUND",
        "RECORD_EXPENSE",
        "RECORD_EXPENSE_CORRECTION",
        "RECORD_EXPENSE_VOID",
        "OPEN_CASH_SHIFT",
        "COUNT_CASH_SHIFT",
        "CLOSE_CASH_SHIFT"
    )

    @Test
    fun releaseEnablesAllImplementedFinancialMaterializers() {
        releaseMaterializerOperations.forEach { operationType ->
            assertTrue(
                "Expected release inbound application for $operationType",
                InboundApplicationGate.shouldEnable(operationType, true, null)
            )
        }
    }

    @Test
    fun releaseStillRejectsOperationsWithoutFinancialMaterializers() {
        listOf(
            "CREATE_BUSINESS",
            "CREATE_BRANCH",
            "CREATE_ADMIN",
            "CREATE_CASHIER",
            "UPDATE_CASHIER_PERMISSIONS",
            "GENERATE_DOCUMENT",
            "SHARE_DOCUMENT",
            "UNKNOWN_OPERATION"
        ).forEach { operationType ->
            assertFalse(
                "Unexpected release inbound application for $operationType",
                InboundApplicationGate.shouldEnable(operationType, true, null)
            )
        }
    }

    @Test
    fun releaseFlagCanDisableAllInboundApplication() {
        releaseMaterializerOperations.forEach { operationType ->
            assertFalse(InboundApplicationGate.shouldEnable(operationType, false, null))
        }
    }

    @Test
    fun debugBuildFlagEnablesExpandedMaterializationForTesting() {
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_REFUND", true, null, debugBuild = true))
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_REFUND", false, null, debugBuild = true))
    }

    @Test
    fun explicitTestOverrideCanDisableOrEnableOperation() {
        assertFalse(InboundApplicationGate.shouldEnable("RECORD_SALE", true, false))
        assertTrue(InboundApplicationGate.shouldEnable("RECORD_REFUND", false, true))
    }
}
