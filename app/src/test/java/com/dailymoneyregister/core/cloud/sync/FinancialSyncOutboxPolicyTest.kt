package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FinancialSyncOutboxPolicyTest {

    private val operationId =
        "0199c001-1234-7abc-8def-0123456789ab"

    private val digest = "a".repeat(64)

    private val identity = FinancialSyncIdentity(
        operationId = operationId,
        businessId = "business-1",
        branchId = "branch-1",
        actorMembershipId = "membership-1",
        deviceId = "device-1",
        commandSchemaVersion = 1,
        canonicalSha256 = digest
    )

    private val inFlight =
        FinancialSyncDeliveryState.IN_FLIGHT

    @Test
    fun queuedMayBeginUpload() {
        assertEquals(
            inFlight,
            FinancialSyncOutboxPolicy.beginUpload(
                FinancialSyncDeliveryState.QUEUED
            )
        )
    }

    @Test
    fun retryMayBeginUpload() {
        assertEquals(
            inFlight,
            FinancialSyncOutboxPolicy.beginUpload(
                FinancialSyncDeliveryState.WAITING_RETRY
            )
        )
    }

    @Test
    fun matchingReceiptAcknowledges() {
        assertEquals(
            FinancialSyncDeliveryState.ACKNOWLEDGED,
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                inFlight,
                FinancialSyncReceipt.Acknowledged(
                    operationId, digest
                )
            )
        )
    }

    @Test
    fun duplicateReceiptAcknowledgesOnce() {
        assertEquals(
            FinancialSyncDeliveryState.ACKNOWLEDGED,
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                inFlight,
                FinancialSyncReceipt.Acknowledged(
                    operationId, digest, duplicate = true
                )
            )
        )
    }

    @Test
    fun conflictingDigestIsNotAcknowledged() {
        assertEquals(
            FinancialSyncDeliveryState.INTEGRITY_CONFLICT,
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                inFlight,
                FinancialSyncReceipt.Acknowledged(
                    operationId, "b".repeat(64)
                )
            )
        )
    }

    @Test
    fun networkFailureWaitsForRetry() {
        assertEquals(
            FinancialSyncDeliveryState.WAITING_RETRY,
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                inFlight,
                FinancialSyncReceipt.Retryable(
                    operationId,
                    RetryReason.NETWORK
                )
            )
        )
    }

    @Test
    fun unauthorizedRequiresAuthentication() {
        assertEquals(
            FinancialSyncDeliveryState.AUTH_REQUIRED,
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                inFlight,
                FinancialSyncReceipt.Unauthorized(operationId)
            )
        )
    }

    @Test
    fun integrityConflictNeverAutomaticallyRetries() {
        val state = FinancialSyncOutboxPolicy.applyReceipt(
            identity,
            inFlight,
            FinancialSyncReceipt.IntegrityConflict(operationId)
        )

        assertEquals(
            FinancialSyncDeliveryState.INTEGRITY_CONFLICT,
            state
        )

        assertThrows(IllegalArgumentException::class.java) {
            FinancialSyncOutboxPolicy.beginUpload(state)
        }
    }

    @Test
    fun rejectedOperationCannotRetry() {
        val state = FinancialSyncOutboxPolicy.applyReceipt(
            identity,
            inFlight,
            FinancialSyncReceipt.Rejected(
                operationId,
                RejectReason.INVALID_EVIDENCE
            )
        )

        assertEquals(FinancialSyncDeliveryState.REJECTED, state)

        assertThrows(IllegalArgumentException::class.java) {
            FinancialSyncOutboxPolicy.beginUpload(state)
        }
    }

    @Test
    fun wrongOperationIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                inFlight,
                FinancialSyncReceipt.Acknowledged(
                    "0199c001-1234-7abc-8def-0123456789ac",
                    digest
                )
            )
        }
    }

    @Test
    fun acknowledgedCannotUploadAgain() {
        assertThrows(IllegalArgumentException::class.java) {
            FinancialSyncOutboxPolicy.beginUpload(
                FinancialSyncDeliveryState.ACKNOWLEDGED
            )
        }
    }

    @Test
    fun receiptCannotBeAppliedToQueuedOperation() {
        assertThrows(IllegalArgumentException::class.java) {
            FinancialSyncOutboxPolicy.applyReceipt(
                identity,
                FinancialSyncDeliveryState.QUEUED,
                FinancialSyncReceipt.Acknowledged(
                    operationId, digest
                )
            )
        }
    }
}