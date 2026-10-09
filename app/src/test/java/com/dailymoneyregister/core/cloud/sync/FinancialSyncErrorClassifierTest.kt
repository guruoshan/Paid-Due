package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialSyncErrorClassifierTest {
    @Test
    fun permanentOutcomeNeedsAttentionWithServerMessage() {
        val receipt = FinancialSyncErrorClassifier.classify(
            operationId = "op-1",
            statusCode = 400,
            code = "P0001",
            outcome = "DIGEST_MISMATCH",
            serverMessage = "Canonical digest does not match"
        )

        assertEquals(
            FinancialSyncReceipt.NeedsAttention("op-1", "Canonical digest does not match"),
            receipt
        )
    }

    @Test
    fun serverFailureRetriesButUnknownClientFailureNeedsAttention() {
        assertTrue(
            FinancialSyncErrorClassifier.classify("op-1", 503, null, null, "down")
                is FinancialSyncReceipt.Retryable
        )
        assertTrue(
            FinancialSyncErrorClassifier.classify("op-1", 400, "P0001", null, "bad request")
                is FinancialSyncReceipt.NeedsAttention
        )
    }

    @Test
    fun extractsOnlyKnownStructuredOutcome() {
        assertEquals(
            "LOCAL_DATE_MISMATCH",
            FinancialSyncErrorClassifier.extractOutcome(
                "P0001",
                "{\"outcome\":\"LOCAL_DATE_MISMATCH\"}"
            )
        )
    }
}
