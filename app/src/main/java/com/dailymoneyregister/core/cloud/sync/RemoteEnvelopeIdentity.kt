package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase

/** Shared actor-identity validation for inbound operation handlers. */
internal object RemoteEnvelopeIdentity {
    suspend fun hasActorIdentity(
        database: DmrDatabase,
        actorUserId: String,
        businessId: String,
        branchId: String,
        envelope: RemoteEnvelopeV2? = null
    ): Boolean {
        // The download RPC returns actor membership metadata from the immutable
        // server envelope row, joined to a membership in this exact business/branch.
        // Ingest validated its local_user_id against canonical actor_user_id. This
        // permits historical Admin-authored sales on Cashier devices without creating
        // a second local Admin login, while still rejecting absent/mismatched metadata.
        if (
            envelope != null &&
            !envelope.actorMembershipId.isNullOrBlank() &&
            envelope.actorLocalUserId == actorUserId &&
            (envelope.actorRole == "ADMIN" || envelope.actorRole == "CASHIER") &&
            envelope.businessId == businessId &&
            envelope.branchId == branchId
        ) return true

        // A synchronized cloud identity is authoritative when present, including
        // inactive/removed tombstones. Never let a stale local login row override revocation.
        val cloudIdentity = database.cloudStaffIdentityDao()
            .findIdentity(actorUserId, businessId, branchId)
        if (cloudIdentity != null) {
            return cloudIdentity.isActive && !cloudIdentity.isRemoved &&
                cloudIdentity.role == "CASHIER"
        }

        val localActor = database.userDao().getUserByIdInBusiness(actorUserId, businessId)
        if (localActor == null || !localActor.isActive) return false
        if (localActor.role.name == "ADMIN") return true
        return database.userDao().getMembership(actorUserId, branchId) != null
    }
}
