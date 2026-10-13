package com.dailymoneyregister.core.cloud.sync

interface StaffIdentitySync {
    suspend fun synchronize(businessId: String, branchId: String)

    object NoOp : StaffIdentitySync {
        override suspend fun synchronize(businessId: String, branchId: String) = Unit
    }
}
