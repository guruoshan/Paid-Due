package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialSyncAckGuardTest {
    private val identity = FinancialSyncIdentity(
        operationId = "op-1",
        businessId = "business-1",
        branchId = "branch-1",
        actorMembershipId = "membership-1",
        deviceId = "device-1",
        commandSchemaVersion = 1,
        canonicalSha256 = "a".repeat(64)
    )

    @Test fun acceptedMatchingOperationAndDigest() {
        assertTrue(
            FinancialSyncAckGuard.isMatchingAck(
                identity, FinancialSyncReceipt.Accepted("op-1", "a".repeat(64))
            )
        )
    }

    @Test fun repeatedMatchingAcknowledgement() {
        assertTrue(
            FinancialSyncAckGuard.isMatchingAck(
                identity, FinancialSyncReceipt.AlreadyAccepted("op-1", "a".repeat(64))
            )
        )
    }

    @Test fun wrongOperationOrDigestMustNotAcknowledge() {
        assertFalse(FinancialSyncAckGuard.isMatchingAck(
            identity, FinancialSyncReceipt.Accepted("op-2", "a".repeat(64))
        ))
        assertFalse(FinancialSyncAckGuard.isMatchingAck(
            identity, FinancialSyncReceipt.Accepted("op-1", "b".repeat(64))
        ))
    }

    @Test fun errorsMustNotAcknowledge() {
        assertFalse(FinancialSyncAckGuard.isMatchingAck(
            identity, FinancialSyncReceipt.Retryable("NETWORK")
        ))
        assertFalse(FinancialSyncAckGuard.isMatchingAck(
            identity, FinancialSyncReceipt.Rejected("WRONG_TENANT")
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDigestFailsClosed() {
        identity.copy(canonicalSha256 = "not-a-digest")
    }
}
