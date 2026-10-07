package com.dailymoneyregister.feature.invite.data

import com.dailymoneyregister.core.model.enums.UserRole
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.inject.Inject

class InviteService @Inject constructor(
    private val store: InviteStore,
    private val clock: Clock,
    private val linkConfig: InviteLinkConfig
) {
    private val secureRandom = SecureRandom()

    suspend fun createInvite(
        businessId: String,
        branchId: String,
        displayName: String,
        canonicalPhone: String,
        permissions: Map<String, Boolean>
    ): CreatedInvite {
        val baseUrl = linkConfig.requireConfiguredBaseUrl()

        val tokenBytes =
            ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)

        val token =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(tokenBytes)

        val now = clock.millis()

        val invite = StaffInvite(
            tokenHash = hashToken(token),
            businessId = businessId,
            branchId = branchId,
            displayName = displayName.trim(),
            canonicalPhone = canonicalPhone,
            role = UserRole.CASHIER,
            permissions = permissions.toMap(),
            createdAt = now,
            expiresAt = now + INVITE_LIFETIME_MILLIS
        )

        store.create(invite)

        return CreatedInvite(
            token = token,
            link = "$baseUrl#$token",
            invite = invite
        )
    }

    suspend fun validate(token: String): InviteResult {
        if (!isPlausibleToken(token)) {
            return InviteResult.NotFound
        }

        val invite =
            store.findByTokenHash(hashToken(token))
                ?: return InviteResult.NotFound

        return when {
            invite.revokedAt != null ->
                InviteResult.Revoked

            invite.usedAt != null ->
                InviteResult.Used

            invite.expiresAt <= clock.millis() ->
                InviteResult.Expired

            else ->
                InviteResult.Valid(invite)
        }
    }

    suspend fun consume(token: String): Boolean {
        if (validate(token) !is InviteResult.Valid) {
            return false
        }

        return store.markUsedIfUnused(
            hashToken(token),
            clock.millis()
        )
    }

    fun hashToken(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff)
                    .toString(16)
                    .padStart(2, '0')
            }

    private fun isPlausibleToken(token: String): Boolean {
        if (token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) {
            return false
        }

        return token.all { character ->
            character.isLetterOrDigit() ||
                character == '-' ||
                character == '_'
        }
    }

    private companion object {
        const val TOKEN_BYTES = 32

        const val INVITE_LIFETIME_MILLIS =
            48L * 60L * 60L * 1000L

        const val MIN_TOKEN_LENGTH = 32
        const val MAX_TOKEN_LENGTH = 128
    }
}
