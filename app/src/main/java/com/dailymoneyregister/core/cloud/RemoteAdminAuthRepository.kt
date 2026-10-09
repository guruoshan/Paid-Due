package com.dailymoneyregister.core.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Matches E.164 (+XXXXXXX) or common local phone patterns (all-digit, starts with 0). */
private val PHONE_PATTERN = Regex("""^\+?\d[\d\s\-().]{6,19}$""")

sealed interface RemoteAdminAuthResult {

    data class Authenticated(
        val authUserId: String,
        val email: String
    ) : RemoteAdminAuthResult

    data class EmailVerificationRequired(
        val email: String
    ) : RemoteAdminAuthResult

    data class Failed(
        val message: String
    ) : RemoteAdminAuthResult
}

@Singleton
class RemoteAdminAuthRepository @Inject constructor(
    private val supabase: SupabaseClient
) {

    suspend fun signUp(
        email: String,
        password: String
    ): RemoteAdminAuthResult {

        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) {
            return RemoteAdminAuthResult.Failed(
                "Email is required"
            )
        }

        if (password.isBlank()) {
            return RemoteAdminAuthResult.Failed(
                "Password is required"
            )
        }

        return try {

            supabase.auth.signUpWith(Email) {
                this.email = normalizedEmail
                this.password = password
            }

            authenticatedResult()
                ?: RemoteAdminAuthResult.EmailVerificationRequired(
                    email = normalizedEmail
                )

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {

            RemoteAdminAuthResult.Failed(
                error.message ?: "Admin signup failed"
            )
        }
    }

    suspend fun signIn(
        email: String,
        password: String
    ): RemoteAdminAuthResult {

        val normalizedEmail = normalizeEmail(email)

        // Guard: admin accounts use email, not phone number.
        // Return a human-readable error rather than sending a phone
        // number to signInWith(Email) and getting an opaque 400.
        if (PHONE_PATTERN.matches(normalizedEmail)) {
            return RemoteAdminAuthResult.Failed(
                "Admin login requires your email address, not a phone number"
            )
        }

        if (normalizedEmail.isBlank()) {
            return RemoteAdminAuthResult.Failed(
                "Email is required"
            )
        }

        if (password.isBlank()) {
            return RemoteAdminAuthResult.Failed(
                "Password is required"
            )
        }

        return try {

            supabase.auth.signInWith(Email) {
                this.email = normalizedEmail
                this.password = password
            }

            authenticatedResult()
                ?: RemoteAdminAuthResult.Failed(
                    "Authentication succeeded without an active session"
                )

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {

            RemoteAdminAuthResult.Failed(
                error.message ?: "Admin login failed"
            )
        }
    }

    fun currentAuthUserId(): String? =
        supabase.auth.currentUserOrNull()
            ?.id

    fun currentEmail(): String? =
        supabase.auth.currentUserOrNull()
            ?.email
            ?.trim()
            ?.lowercase()

    fun hasAuthenticatedSession(): Boolean =
        supabase.auth.currentSessionOrNull() != null

    private fun authenticatedResult():
        RemoteAdminAuthResult.Authenticated? {

        val user =
            supabase.auth.currentUserOrNull()
                ?: return null

        val email =
            user.email
                ?.trim()
                ?.lowercase()
                ?: return null

        return RemoteAdminAuthResult.Authenticated(
            authUserId = user.id,
            email = email
        )
    }

    private fun normalizeEmail(
        value: String
    ): String =
        value.trim().lowercase()
}