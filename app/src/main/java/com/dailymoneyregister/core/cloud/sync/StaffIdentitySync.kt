package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.security.ActiveSession

interface StaffIdentitySync {
    suspend fun synchronize(businessId: String, branchId: String)
    suspend fun refreshCashierPermissions(session: ActiveSession): Boolean = true

    object NoOp : StaffIdentitySync {
        override suspend fun synchronize(businessId: String, branchId: String) = Unit
    }
}
