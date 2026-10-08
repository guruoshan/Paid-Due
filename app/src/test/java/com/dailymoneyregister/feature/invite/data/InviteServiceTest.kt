package com.dailymoneyregister.feature.invite.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteServiceTest {

    private val baseInstant =
        Instant.parse("2026-10-07T00:00:00Z")

    private val baseClock =
        Clock.fixed(
            baseInstant,
            ZoneOffset.UTC
        )

    @Test
    fun freshInviteValidatesAsValid() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        val created =
            service.createInvite(
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier",
                canonicalPhone = "+919812345678",
                permissions = emptyMap()
            )

        val result =
            service.validate(created.token)

        assertTrue(
            result is InviteResult.Valid
        )
    }

    @Test
    fun inviteExpiresAfter48Hours() = runTest {
        val store = FakeInviteStore()

        val creator =
            InviteService(
                store = store,
                clock = baseClock,
                linkConfig = FakeInviteLinkConfig()
            )

        val created =
            creator.createInvite(
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier",
                canonicalPhone = "+919812345678",
                permissions = emptyMap()
            )

        val expiredClock =
            Clock.fixed(
                baseInstant.plusSeconds(
                    (48L * 60L * 60L) + 1L
                ),
                ZoneOffset.UTC
            )

        val validator =
            InviteService(
                store = store,
                clock = expiredClock,
                linkConfig = FakeInviteLinkConfig()
            )

        assertEquals(
            InviteResult.Expired,
            validator.validate(created.token)
        )
    }

    @Test
    fun consumedInviteBecomesUsedAndCannotConsumeAgain() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        val created =
            createInvite(service)

        assertTrue(
            service.consume(created.token)
        )

        assertFalse(
            service.consume(created.token)
        )

        assertEquals(
            InviteResult.Used,
            service.validate(created.token)
        )
    }

    @Test
    fun revokedInviteIsRejected() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        val created =
            createInvite(service)

        store.revoke(
            service.hashToken(created.token),
            baseClock.millis()
        )

        assertEquals(
            InviteResult.Revoked,
            service.validate(created.token)
        )
    }

    @Test
    fun unknownTokenIsNotFound() = runTest {
        val service =
            service(FakeInviteStore())

        val unknownToken =
            "abcdefghijklmnopqrstuvwxyzABCDEFG1234567890_-"

        assertEquals(
            InviteResult.NotFound,
            service.validate(unknownToken)
        )
    }

    @Test
    fun storeContainsHashNeverRawToken() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        val created =
            createInvite(service)

        assertFalse(
            store.invites.containsKey(
                created.token
            )
        )

        assertTrue(
            store.invites.containsKey(
                service.hashToken(created.token)
            )
        )

        assertEquals(
            created.invite.tokenHash,
            service.hashToken(created.token)
        )
    }

    @Test
    fun twoInvitesHaveDifferentTokens() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        val first =
            service.createInvite(
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier One",
                canonicalPhone = "+919812345671",
                permissions = emptyMap()
            )

        val second =
            service.createInvite(
                businessId = "business-1",
                branchId = "branch-1",
                displayName = "Cashier Two",
                canonicalPhone = "+919812345672",
                permissions = emptyMap()
            )

        assertNotEquals(
            first.token,
            second.token
        )
    }

    @Test
    fun tamperedTokenIsNotFound() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        val created =
            createInvite(service)

        val replacement =
            if (created.token.last() == 'A') {
                'B'
            } else {
                'A'
            }

        val tampered =
            created.token.dropLast(1) +
                replacement

        assertEquals(
            InviteResult.NotFound,
            service.validate(tampered)
        )
    }

    @Test
    fun generatedLinkUsesConfiguredPaidDueEndpoint() = runTest {
        val service =
            service(FakeInviteStore())

        val created =
            createInvite(service)

        assertTrue(
            created.link.startsWith(
                "https://paid-due.test/open/invite#"
            )
        )

        assertFalse(
            created.link.contains(
                "muscatera.com",
                ignoreCase = true
            )
        )

        assertTrue(
            created.link.endsWith(
                created.token
            )
        )
    }

    @Test
    fun malformedTokenIsRejectedWithoutStoreLookup() = runTest {
        val store = FakeInviteStore()
        val service = service(store)

        assertEquals(
            InviteResult.NotFound,
            service.validate("not valid !!!")
        )

        assertEquals(
            0,
            store.findCount
        )
    }

    private suspend fun createInvite(
        service: InviteService
    ): CreatedInvite =
        service.createInvite(
            businessId = "business-1",
            branchId = "branch-1",
            displayName = "Cashier",
            canonicalPhone = "+919812345678",
            permissions = emptyMap()
        )

    private fun service(
        store: FakeInviteStore
    ): InviteService =
        InviteService(
            store = store,
            clock = baseClock,
            linkConfig = FakeInviteLinkConfig()
        )

    private class FakeInviteLinkConfig :
        InviteLinkConfig() {

        override fun configuredBaseUrl(): String =
            "https://paid-due.test/open/invite"
    }

    private class FakeInviteStore : InviteStore {

        val invites =
            linkedMapOf<String, StaffInvite>()

        var findCount: Int = 0
            private set

        override suspend fun create(
            invite: StaffInvite
        ) {
            invites[invite.tokenHash] =
                invite
        }

        override suspend fun findByTokenHash(
            hash: String
        ): StaffInvite? {
            findCount += 1
            return invites[hash]
        }

        override suspend fun markUsedIfUnused(
            hash: String,
            now: Long
        ): Boolean {

            val current =
                invites[hash]
                    ?: return false

            if (
                current.usedAt != null ||
                current.revokedAt != null
            ) {
                return false
            }

            invites[hash] =
                current.copy(
                    usedAt = now
                )

            return true
        }

        override suspend fun revoke(
            hash: String,
            now: Long
        ) {
            val current =
                invites[hash]
                    ?: return

            invites[hash] =
                current.copy(
                    revokedAt = now
                )
        }

        override suspend fun listForBusiness(
            businessId: String
        ): List<StaffInvite> =
            invites.values.filter {
                it.businessId == businessId
            }
    }
}
