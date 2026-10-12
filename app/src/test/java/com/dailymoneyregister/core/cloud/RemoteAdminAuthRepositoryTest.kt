package com.dailymoneyregister.core.cloud

import io.github.jan.supabase.SupabaseClient
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RemoteAdminAuthRepository].
 *
 * **Scope**: Verify guards that fire BEFORE any Supabase interaction.
 * These are the only cases that can be tested hermetically on the JVM
 * without the MockK agent, because the guards short-circuit before
 * any extension-function call on SupabaseClient.
 *
 * Specifically tested:
 *  1. E.164 phone input → Failed with email-required message.
 *  2. Local phone (digits only, no +) → Failed with email-required message.
 *  3. Phone with spaces/dashes → Failed with email-required message.
 *  4. Blank email → Failed.
 *  5. Blank password → Failed.
 *
 * Session-dependent behaviour (Supabase returns a session, or doesn't)
 * is tested in integration / instrumented tests where the full Supabase
 * client can be exercised with a real or emulated backend, because
 * `Auth.currentUserOrNull()` is an SDK extension function that cannot be
 * stubbed via MockK without the JVM agent.
 */
class RemoteAdminAuthRepositoryTest {

    // ── fixture ────────────────────────────────────────────────────────────

    /**
     * Repository backed by a fully relaxed [SupabaseClient].
     * Safe to use only when the test is expected to return BEFORE the
     * repository makes any call into the Supabase SDK.
     * No `every { }` stubs are set — relaxed mocks return null/false
     * by default and do not require the MockK JVM agent.
     */
    private fun repo(): RemoteAdminAuthRepository =
        RemoteAdminAuthRepository(mockk(relaxed = true))

    // ── phone number rejection ─────────────────────────────────────────────

    @Test
    fun `signIn with E164 phone returns failed with email-required message`() = runTest {
        val result = repo().signIn("+96891436786", "secret")
        assertIsFailedWithEmailMessage(result)
    }

    @Test
    fun `signIn with local phone number no plus returns failed with email-required message`() = runTest {
        val result = repo().signIn("0091436786", "secret")
        assertIsFailedWithEmailMessage(result)
    }

    @Test
    fun `signIn with phone containing spaces and dashes returns failed with email-required message`() = runTest {
        val result = repo().signIn("+968 91-436-786", "secret")
        assertIsFailedWithEmailMessage(result)
    }

    // ── blank input guards ─────────────────────────────────────────────────

    @Test
    fun `signIn with blank email returns Failed`() = runTest {
        val result = repo().signIn("   ", "secret")
        assertTrue("Expected Failed, got $result", result is RemoteAdminAuthResult.Failed)
    }

    @Test
    fun `signIn with blank password returns Failed`() = runTest {
        val result = repo().signIn("admin@example.com", "")
        assertTrue("Expected Failed, got $result", result is RemoteAdminAuthResult.Failed)
    }

    // ── email normalisation (no Supabase call needed to verify guard) ──────

    @Test
    fun `signIn with uppercase email does not trigger phone guard`() = runTest {
        // After normalisation to lowercase, "ADMIN@EXAMPLE.COM" becomes a valid
        // email — it must NOT match the phone pattern. If it did, the phone guard
        // would return Failed before reaching Supabase (which would return null
        // for currentUserOrNull on a relaxed mock, also Failed — so we verify the
        // message is NOT the phone-specific one).
        val result = repo().signIn("ADMIN@EXAMPLE.COM", "password")
        if (result is RemoteAdminAuthResult.Failed) {
            val msg = result.message
            assertTrue(
                "Uppercase email should not trigger phone guard, got: $msg",
                !msg.contains("not a phone number", ignoreCase = true)
            )
        }
        // Result may be Failed("Authentication succeeded without an active session")
        // or Failed(Supabase exception) — both are acceptable in a unit test without
        // a real backend. The assertion is that the phone guard is NOT triggered.
    }


    @Test
    fun remoteAuthOutageClassificationUsesExceptionTypeNotErrorText() {
        assertEquals(
            RemoteAuthFailureKind.UNAVAILABLE,
            classifyRemoteAuthFailure(IOException("invalid credentials"))
        )
        assertEquals(
            RemoteAuthFailureKind.UNAVAILABLE,
            classifyRemoteAuthFailure(java.util.concurrent.TimeoutException("timeout"))
        )
        assertEquals(
            RemoteAuthFailureKind.REJECTED,
            classifyRemoteAuthFailure(IllegalStateException("network connection timeout"))
        )
    }

    @Test
    fun remoteAuthHttpStatusClassificationAllowsOfflineFallbackOnlyForTimeoutOrServerFailure() {
        assertEquals(RemoteAuthFailureKind.REJECTED, classifyRemoteAuthHttpStatus(400))
        assertEquals(RemoteAuthFailureKind.REJECTED, classifyRemoteAuthHttpStatus(401))
        assertEquals(RemoteAuthFailureKind.REJECTED, classifyRemoteAuthHttpStatus(403))
        assertEquals(RemoteAuthFailureKind.REJECTED, classifyRemoteAuthHttpStatus(429))
        assertEquals(RemoteAuthFailureKind.UNAVAILABLE, classifyRemoteAuthHttpStatus(408))
        assertEquals(RemoteAuthFailureKind.UNAVAILABLE, classifyRemoteAuthHttpStatus(500))
        assertEquals(RemoteAuthFailureKind.UNAVAILABLE, classifyRemoteAuthHttpStatus(503))
    }

    // ── assertion helpers ──────────────────────────────────────────────────

    private fun assertIsFailedWithEmailMessage(result: RemoteAdminAuthResult) {
        assertTrue(
            "Expected RemoteAdminAuthResult.Failed, got $result",
            result is RemoteAdminAuthResult.Failed
        )
        val msg = (result as RemoteAdminAuthResult.Failed).message
        assertTrue(
            "Expected failure message to mention 'email', got: \"$msg\"",
            msg.contains("email", ignoreCase = true)
        )
    }
}
