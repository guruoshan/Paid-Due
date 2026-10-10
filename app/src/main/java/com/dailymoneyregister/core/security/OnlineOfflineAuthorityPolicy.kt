package com.dailymoneyregister.core.security

/**
 * BOARDLOCK FOUNDATION ONLY.
 *
 * Pure authorization policy for the future Supabase-primary session gate.
 * This policy is intentionally not wired into login/startup yet: callers
 * must first supply trustworthy connectivity and server-validation results.
 * It never mutates local financial data or the durable financial outbox.
 */
enum class AuthorityConnectivity {
    ONLINE,
    OFFLINE
}

enum class RemoteAuthorityResult {
    AUTHORIZED,
    REVOKED,
    UNAVAILABLE,
    NOT_CHECKED
}

enum class AuthorityDecision {
    ALLOW_ONLINE,
    ALLOW_OFFLINE_LIMITED,
    REQUIRE_ONLINE_AUTHENTICATION,
    DENY_REVOKED
}

object OnlineOfflineAuthorityPolicy {
    /**
     * Decide whether a cached identity may enter the app.
     *
     * An online server rejection/unavailability never falls back to local
     * credentials. Offline access requires a prior server validation within
     * the caller-approved grace period. A known revocation always wins.
     *
     * This is a contract only; the grace duration and permitted offline
     * actions must be approved before production wiring.
     */
    fun evaluate(
        connectivity: AuthorityConnectivity,
        remoteResult: RemoteAuthorityResult,
        lastServerValidatedAtMillis: Long?,
        nowMillis: Long,
        offlineGraceMillis: Long
    ): AuthorityDecision {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        require(offlineGraceMillis >= 0L) { "offlineGraceMillis must be non-negative" }

        if (remoteResult == RemoteAuthorityResult.REVOKED) {
            return AuthorityDecision.DENY_REVOKED
        }

        if (connectivity == AuthorityConnectivity.ONLINE) {
            return if (remoteResult == RemoteAuthorityResult.AUTHORIZED) {
                AuthorityDecision.ALLOW_ONLINE
            } else {
                // A reachable network is not permission to trust local state
                // when the authority endpoint fails or has not been checked.
                AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION
            }
        }

        val validatedAt = lastServerValidatedAtMillis
            ?: return AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION

        if (validatedAt < 0L || validatedAt > nowMillis || offlineGraceMillis == 0L) {
            return AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION
        }

        val age = nowMillis - validatedAt
        return if (age <= offlineGraceMillis) {
            AuthorityDecision.ALLOW_OFFLINE_LIMITED
        } else {
            AuthorityDecision.REQUIRE_ONLINE_AUTHENTICATION
        }
    }
}
