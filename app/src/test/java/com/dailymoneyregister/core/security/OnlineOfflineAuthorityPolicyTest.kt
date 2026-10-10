package com.dailymoneyregister.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineOfflineAuthorityPolicyTest {
    private val now = 1_800_000_000_000L
    private val grace = 60 * 60 * 1000L

    @Test
    fun onlineAccessRequiresFreshServerAuthorization() {
        assertEquals(
            AuthorityDecision.ALLOW_ONLINE,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.ONLINE,
                RemoteAuthorityResult.AUTHORIZED,
                now,
                now,
                grace
            )
        )
    }

    @Test
    fun onlineServerUnavailableNeverFallsBackToCachedCredentials() {
        assertEquals(
            AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.ONLINE,
                RemoteAuthorityResult.UNAVAILABLE,
                now,
                now,
                grace
            )
        )
    }

    @Test
    fun serverRevocationAlwaysWinsEvenWhenOfflineGraceRemains() {
        assertEquals(
            AuthorityDecision.DENY_REVOKED,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.OFFLINE,
                RemoteAuthorityResult.REVOKED,
                now,
                now,
                grace
            )
        )
    }

    @Test
    fun offlineAccessRequiresPreviouslyValidatedSession() {
        assertEquals(
            AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.OFFLINE,
                RemoteAuthorityResult.NOT_CHECKED,
                null,
                now,
                grace
            )
        )
    }

    @Test
    fun offlineAccessAllowedOnlyInsideGraceWindow() {
        assertEquals(
            AuthorityDecision.ALLOW_OFFLINE_LIMITED,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.OFFLINE,
                RemoteAuthorityResult.NOT_CHECKED,
                now - grace,
                now,
                grace
            )
        )
        assertEquals(
            AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.OFFLINE,
                RemoteAuthorityResult.NOT_CHECKED,
                now - grace - 1L,
                now,
                grace
            )
        )
    }

    @Test
    fun zeroGraceDisablesOfflineSessionRestoration() {
        assertEquals(
            AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.OFFLINE,
                RemoteAuthorityResult.NOT_CHECKED,
                now,
                now,
                0L
            )
        )
    }

    @Test
    fun invalidFutureValidationTimestampFailsClosed() {
        assertEquals(
            AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION,
            OnlineOfflineAuthorityPolicy.evaluate(
                AuthorityConnectivity.OFFLINE,
                RemoteAuthorityResult.NOT_CHECKED,
                now + 1L,
                now,
                grace
            )
        )
    }
}
