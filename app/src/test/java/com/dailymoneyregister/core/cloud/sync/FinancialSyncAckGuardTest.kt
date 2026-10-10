package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FinancialSyncAckGuardTest {
    private val op = "0199c001-1234-7abc-8def-0123456789ab"
    private val otherOp = "0199c001-1234-7abc-8def-0123456789ac"
    private val digest = "a".repeat(64)
    private val otherDigest = "b".repeat(64)

    private fun identity() = FinancialSyncIdentity(op, "business-1", "branch-1",
        "membership-1", "device-1", 1, digest)

    private fun invalidField(field: String, changed: FinancialSyncIdentity.() -> FinancialSyncIdentity) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            identity().changed()
        }
        assertTrue("Expected failure naming $field", failure.message.orEmpty().contains(field))
    }

    @Test fun acceptedMatching() = assertTrue(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(op, digest)))
    @Test fun duplicateMatching() = assertTrue(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(op, digest, duplicate = true)))
    @Test fun wrongOperationOnAck() = assertFalse(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(otherOp, digest)))
    @Test fun wrongOperationOnDuplicateAck() = assertFalse(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(otherOp, digest, duplicate = true)))
    @Test fun wrongDigestOnAck() = assertFalse(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(op, otherDigest)))
    @Test fun wrongDigestOnDuplicateAck() = assertFalse(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(op, otherDigest, duplicate = true)))
    @Test fun bothWrongOnAck() = assertFalse(FinancialSyncAckGuard.isMatchingAck(
        identity(), FinancialSyncReceipt.Acknowledged(otherOp, otherDigest)))

    @Test fun nonAcksNeverClearOutbox() {
        val receipts = listOf(
            FinancialSyncReceipt.Retryable(op, RetryReason.NETWORK),
            FinancialSyncReceipt.Rejected(op, RejectReason.WRONG_TENANT),
            FinancialSyncReceipt.IntegrityConflict(op),
            FinancialSyncReceipt.Unauthorized(op),
            FinancialSyncReceipt.Retryable(otherOp, RetryReason.NETWORK),
            FinancialSyncReceipt.Rejected(otherOp, RejectReason.WRONG_TENANT),
            FinancialSyncReceipt.IntegrityConflict(otherOp),
            FinancialSyncReceipt.Unauthorized(otherOp)
        )
        for (receipt in receipts) {
            assertFalse(FinancialSyncAckGuard.isMatchingAck(identity(), receipt))
            assertEquals(receipt.operationId, when (receipt) {
                is FinancialSyncReceipt.Acknowledged -> receipt.operationId
                is FinancialSyncReceipt.Retryable -> receipt.operationId
                is FinancialSyncReceipt.Rejected -> receipt.operationId
                is FinancialSyncReceipt.IntegrityConflict -> receipt.operationId
                is FinancialSyncReceipt.Unauthorized -> receipt.operationId
                is FinancialSyncReceipt.NeedsAttention -> receipt.operationId
            })
        }
    }

    @Test fun malformedDigests() {
        for (value in listOf("", "a".repeat(63), "a".repeat(65),
            "A".repeat(64), "a".repeat(64) + "\n", "a".repeat(63) + "g")) {
            invalidField("canonicalSha256") { copy(canonicalSha256 = value) }
        }
    }

    @Test fun invalidStringIds() {
        invalidField("operationId") { copy(operationId = "") }
        invalidField("businessId") { copy(businessId = "") }
        invalidField("branchId") { copy(branchId = "") }
        invalidField("actorMembershipId") { copy(actorMembershipId = "") }
        invalidField("deviceId") { copy(deviceId = "") }
        invalidField("deviceId") { copy(deviceId = "x".repeat(129)) }
        invalidField("businessId") { copy(businessId = " business-1") }
        invalidField("branchId") { copy(branchId = "branch-1 ") }
    }

    @Test fun invalidSchemaVersions() {
        invalidField("commandSchemaVersion") { copy(commandSchemaVersion = 0) }
        invalidField("commandSchemaVersion") { copy(commandSchemaVersion = -1) }
    }

    @Test fun exactOperationIdValidation() {
        // Contract uses UUIDv7, so aliases must fail, even without whitespace.
        for (id in listOf("OP-1", "op-1 ", " op-1", op.uppercase(), " $op", "$op ")) {
            invalidField("operationId") { copy(operationId = id) }
        }
        assertEquals(op, identity().operationId)
    }
}
