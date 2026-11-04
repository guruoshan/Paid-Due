package com.dailymoneyregister

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager

internal suspend fun useAdminActor(
    database: DmrDatabase,
    businessId: String,
    branchId: String,
    adminActorId: String,
    updateActorId: (String) -> Unit
) {
    updateActorId(adminActorId)
    val sessionBusiness = checkNotNull(database.businessBranchDao().getBusinessById(businessId))
    val sessionBranch = checkNotNull(database.businessBranchDao().getBranchById(branchId))
    val sessionActor = checkNotNull(database.userDao().getUserById(adminActorId))
    val sessionMembership = checkNotNull(database.userDao().getMembership(adminActorId, branchId))
    SessionManager.setSession(
        ActiveSession(
            business = sessionBusiness,
            branch = sessionBranch,
            user = sessionActor,
            membership = sessionMembership
        )
    )
}
