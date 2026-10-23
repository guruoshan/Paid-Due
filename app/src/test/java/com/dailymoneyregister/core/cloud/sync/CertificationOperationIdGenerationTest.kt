package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.model.ids.OperationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificationOperationIdGenerationTest {
    private val uuidV7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun generatedOperationIdsAreUniqueUuidV7ValuesAcceptedBySyncContract() {
        val ids = List(1_000) { OperationId.generate().value }

        assertEquals("Every generated operation ID must be unique", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue("Expected UUIDv7 operation ID, got $id", uuidV7.matches(id))
            FinancialSyncIdentity(
                operationId = id,
                businessId = "cert-business",
                branchId = "cert-branch",
                actorMembershipId = "cert-membership",
                deviceId = "cert-device",
                commandSchemaVersion = 1,
                canonicalSha256 = "0".repeat(64)
            )
        }
    }

    @Test
    fun customCertificationPrefixesRemainRejectedAsOperationIds() {
        val rejectedIds = listOf(
            "cert-credit-repayment-8e606ebc",
            "cert-coupon-purchase-010112dc",
            "cert-open-bdc76195183e"
        )

        rejectedIds.forEach { id ->
            val error = runCatching {
                FinancialSyncIdentity(
                    operationId = id,
                    businessId = "cert-business",
                    branchId = "cert-branch",
                    actorMembershipId = "cert-membership",
                    deviceId = "cert-device",
                    commandSchemaVersion = 1,
                    canonicalSha256 = "0".repeat(64)
                )
            }.exceptionOrNull()
            assertTrue("Expected invalid operation ID to be rejected: $id", error is IllegalArgumentException)
        }
    }
}
