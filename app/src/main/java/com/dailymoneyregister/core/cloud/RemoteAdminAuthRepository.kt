package com.dailymoneyregister.core.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import javax.inject.Inject
import javax.inject.Singleton

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