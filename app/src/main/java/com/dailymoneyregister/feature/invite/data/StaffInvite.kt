package com.dailymoneyregister.feature.invite.data

import com.dailymoneyregister.core.model.enums.UserRole

data class StaffInvite(
    val tokenHash: String,
    val businessId: String,
    val branchId: String,
    val displayName: String,
    val canonicalPhone: String,
    val role: UserRole = UserRole.CASHIER,
    val permissions: Map<String, Boolean>,
    val createdAt: Long,
    val expiresAt: Long,
    val usedAt: Long? = null,
    val revokedAt: Long? = null
)

data class CreatedInvite(
    val email: String,
    val displayName: String,
    val canonicalPhone: String,
    val expiresAt: Long
)

sealed interface InviteResult {
    data class Valid(val invite: StaffInvite) : InviteResult
    data object Expired : InviteResult
    data object Used : InviteResult
    data object Revoked : InviteResult
    data object NotFound : InviteResult
}
