package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CashierSessionRejectionPolicyTest {
    @Test
    fun unauthorizedResponseClearsSession() {
        assertTrue(shouldClearCashierSession(401, "UNAUTHORIZED"))
    }

    @Test
    fun forbiddenOperationDoesNotClearSession() {
        assertFalse(shouldClearCashierSession(403, "OPERATION_NOT_PERMITTED"))
        assertFalse(shouldClearCashierSession(403, "FORBIDDEN"))
    }

    @Test
    fun explicitMembershipRejectionClearsSession() {
        assertTrue(shouldClearCashierSession(403, "INVALID_CASHIER_MEMBERSHIP"))
    }

    @Test
    fun metadataAuthorizationDenialIsRejectedButServerFailureIsUnavailable() {
        assertTrue(isCashierMetadataAuthorizationRejected(401))
        assertTrue(isCashierMetadataAuthorizationRejected(403))
        assertFalse(isCashierMetadataAuthorizationRejected(500))
        assertFalse(isCashierMetadataAuthorizationRejected(503))
    }

    @Test
    fun serverFailuresDoNotClearSession() {
        assertFalse(shouldClearCashierSession(500, "INTERNAL_SERVER_ERROR"))
    }
}
