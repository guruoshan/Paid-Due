package com.dailymoneyregister.feature.invite.data

import com.dailymoneyregister.core.model.enums.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteServiceTest {
    private val baseInstant = Instant.parse("2026-10-07T00:00:00Z")
    private val baseClock = Clock.fixed(baseInstant, ZoneOffset.UTC)

    @Test
    fun invitationIsSentThroughSupabaseAndIsNotStoredLocally() = runTest {
        val store = FakeInviteStore()
        val sender = FakeEmailSender()
        val service = service(store, sender)

        val created = service.createInvite(
            email = " Cashier@Example.com ",
            businessId = "business-1",
            branchId = "branch-1",
            displayName = "Cashier",
            canonicalPhone = "+919812345678",
            permissions = mapOf("REFUND" to false)
        )

        assertEquals("cashier@example.com", sender.lastEmail)
        assertEquals("business-1", sender.lastBusinessId)
        assertEquals("branch-1", sender.lastBranchId)
        assertEquals("Cashier", sender.lastDisplayName)
        assertEquals("+919812345678", sender.lastPhone)
        assertEquals(mapOf("REFUND" to false), sender.lastPermissions)
        assertEquals("cashier@example.com", created.email)
        assertEquals("Cashier", created.displayName)
        assertEquals(baseClock.millis() + 48L * 60L * 60L * 1000L, created.expiresAt)
        assertTrue(store.invites.isEmpty())
    }

    @Test
    fun invalidEmailIsRejectedBeforeCallingSupabase() = runTest {
        val sender = FakeEmailSender()
        val service = service(FakeInviteStore(), sender)

        val failure = runCatching {
            service.createInvite(
                email = "not-an-email",
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier",
                canonicalPhone = "+919812345678",
                permissions = emptyMap()
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(0, sender.sendCount)
    }

    @Test
    fun tokenHashIsSha256HexAndDoesNotExposeRawToken() {
        val service = service(FakeInviteStore(), FakeEmailSender())
        val token = "abcdefghijklmnopqrstuvwxyzABCDEFG1234567890_-"
        val hash = service.hashToken(token)
        assertEquals(64, hash.length)
        assertFalse(hash.contains(token))
    }

    @Test
    fun remoteInviteValidationHonorsExpiryAndSingleUse() = runTest {
        val store = FakeInviteStore()
        val sender = FakeEmailSender()
        val service = service(store, sender)
        val token = "abcdefghijklmnopqrstuvwxyzABCDEFG1234567890_-"
        val hash = service.hashToken(token)
        val now = baseClock.millis()
        store.create(
            StaffInvite(
                tokenHash = hash,
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier",
                canonicalPhone = "+919812345678",
                role = UserRole.CASHIER,
                permissions = emptyMap(),
                createdAt = now,
                expiresAt = now + 48L * 60L * 60L * 1000L
            )
        )

        assertTrue(service.consume(token))
        assertFalse(service.consume(token))
        assertTrue(service.validate(token) is InviteResult.Used)
    }

    @Test
    fun expiredInviteIsRejected() = runTest {
        val store = FakeInviteStore()
        val token = "abcdefghijklmnopqrstuvwxyzABCDEFG1234567890_-"
        val seedService = service(store, FakeEmailSender())
        val now = baseClock.millis()
        store.create(
            StaffInvite(
                tokenHash = seedService.hashToken(token),
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier",
                canonicalPhone = "+919812345678",
                role = UserRole.CASHIER,
                permissions = emptyMap(),
                createdAt = now - 49L * 60L * 60L * 1000L,
                expiresAt = now - 1
            )
        )
        assertEquals(InviteResult.Expired, seedService.validate(token))
    }

    private fun service(
        store: FakeInviteStore,
        sender: FakeEmailSender,
        clock: Clock = baseClock
    ) = InviteService(store = store, emailSender = sender, clock = clock)

    private class FakeEmailSender : StaffInviteEmailSender {
        var sendCount = 0
        var lastEmail: String? = null
        var lastBusinessId: String? = null
        var lastBranchId: String? = null
        var lastDisplayName: String? = null
        var lastPhone: String? = null
        var lastPermissions: Map<String, Boolean>? = null

        override suspend fun sendEmailInvite(
            email: String,
            businessId: String,
            branchId: String,
            displayName: String,
            canonicalPhone: String,
            permissions: Map<String, Boolean>
        ) {
            sendCount++
            lastEmail = email
            lastBusinessId = businessId
            lastBranchId = branchId
            lastDisplayName = displayName
            lastPhone = canonicalPhone
            lastPermissions = permissions
        }
    }

    private class FakeInviteStore : InviteStore {
        val invites = linkedMapOf<String, StaffInvite>()

        override suspend fun create(invite: StaffInvite) {
            invites[invite.tokenHash] = invite
        }

        override suspend fun findByTokenHash(hash: String): StaffInvite? = invites[hash]

        override suspend fun markUsedIfUnused(hash: String, now: Long): Boolean {
            val invite = invites[hash] ?: return false
            if (invite.usedAt != null || invite.revokedAt != null || invite.expiresAt <= now) return false
            invites[hash] = invite.copy(usedAt = now)
            return true
        }

        override suspend fun revoke(hash: String, now: Long) {
            val invite = invites[hash] ?: return
            invites[hash] = invite.copy(revokedAt = now)
        }

        override suspend fun listForBusiness(businessId: String): List<StaffInvite> =
            invites.values.filter { it.businessId == businessId }
    }
}
