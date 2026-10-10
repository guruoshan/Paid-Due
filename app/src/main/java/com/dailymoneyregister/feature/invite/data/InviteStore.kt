package com.dailymoneyregister.feature.invite.data

interface InviteStore {
    suspend fun create(invite: StaffInvite)
    suspend fun findByTokenHash(hash: String): StaffInvite?
    suspend fun markUsedIfUnused(hash: String, now: Long): Boolean
    suspend fun revoke(hash: String, now: Long)
    suspend fun listForBusiness(businessId: String): List<StaffInvite>
}

interface StaffInviteEmailSender {
    suspend fun sendEmailInvite(
        email: String,
        businessId: String,
        branchId: String,
        displayName: String,
        canonicalPhone: String,
        permissions: Map<String, Boolean>
    )
}
