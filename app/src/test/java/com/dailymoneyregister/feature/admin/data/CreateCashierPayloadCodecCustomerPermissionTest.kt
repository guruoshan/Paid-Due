package com.dailymoneyregister.feature.admin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateCashierPayloadCodecCustomerPermissionTest {
    private fun payload(permission: Boolean) = CreateCashierRecoveryPayload(
        operationId = "op-1",
        businessId = "business-1",
        branchId = "branch-1",
        adminUserId = "admin-1",
        cashierUserId = "cashier-1",
        membershipId = "membership-1",
        occurredAt = 1_791_630_000_000L,
        deviceId = "device-1",
        displayName = "Cashier",
        username = "+96890000000",
        canonicalPhone = "+96890000000",
        credentialHash = "test-hash",
        credentialSalt = "test-salt",
        preferredLanguage = "en",
        permissions = CashierPermissionsConfig(canViewCustomers = permission)
    )

    @Test
    fun `customer access permission round trips in durable recovery payload`() {
        val original = payload(permission = false)
        val restored = CreateCashierPayloadCodec.decode(CreateCashierPayloadCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `legacy durable payload defaults missing customer permission to enabled`() {
        val encodedLegacy = CreateCashierPayloadCodec.encode(payload(permission = true))
            .lineSequence()
            .filterNot { it.startsWith("canViewCustomers=") }
            .joinToString("\n")

        assertTrue(encodedLegacy.isNotBlank())
        assertEquals(
            true,
            CreateCashierPayloadCodec.decode(encodedLegacy).permissions.canViewCustomers
        )
    }

    @Test
    fun `cashier create fingerprint includes customer access permission`() {
        val grant = CreateCashierParams(
            businessId = "business-1",
            branchId = "branch-1",
            adminUserId = "admin-1",
            displayName = "Cashier",
            username = "+96890000000",
            pin = "123456",
            permissions = CashierPermissionsConfig(canViewCustomers = true)
        )
        val deny = grant.copy(permissions = grant.permissions.copy(canViewCustomers = false))
        assertNotEquals(
            CreateCashierPayloadCodec.fingerprint(grant),
            CreateCashierPayloadCodec.fingerprint(deny)
        )
    }
}
