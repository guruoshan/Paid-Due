package com.dailymoneyregister.feature.invite.data

import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject

class InviteService @Inject constructor(
    private val store: InviteStore,
    private val linkCreator: StaffInviteLinkCreator,
    private val clock: Clock
) {
    suspend fun createInvite(
        businessId: String,
        branchId: String,
        displayName: String,
        canonicalPhone: String,
        permissions: Map<String, Boolean>
    ): CreatedInvite {
        require(businessId.isNotBlank() && branchId.isNotBlank()) {
            "Choose a valid business branch."
        }
        require(displayName.trim().isNotEmpty()) {
            "Cashier name is required."
        }
        require(CANONICAL_PHONE_REGEX.matches(canonicalPhone)) {
            "Enter a valid mobile number."
        }

        return linkCreator.createInviteLink(
            businessId = businessId,
            branchId = branchId,
            displayName = displayName.trim(),
            canonicalPhone = canonicalPhone,
            permissions = permissions.toMap()
        )
    }

    suspend fun validate(token: String): InviteResult {
        if (!isPlausibleToken(token)) return InviteResult.NotFound
        val invite = store.findByTokenHash(hashToken(token)) ?: return InviteResult.NotFound
        return when {
            invite.revokedAt != null -> InviteResult.Revoked
            invite.usedAt != null -> InviteResult.Used
            invite.expiresAt <= clock.millis() -> InviteResult.Expired
            else -> InviteResult.Valid(invite)
        }
    }

    suspend fun consume(token: String): Boolean {
        if (validate(token) !is InviteResult.Valid) return false
        return store.markUsedIfUnused(hashToken(token), clock.millis())
    }

    fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun isPlausibleToken(token: String): Boolean {
        if (token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) return false
        return token.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private companion object {
        val CANONICAL_PHONE_REGEX = Regex("""^\+[1-9][0-9]{7,14}$""")
        const val MIN_TOKEN_LENGTH = 32
        const val MAX_TOKEN_LENGTH = 128
    }
}
