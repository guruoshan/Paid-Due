package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.feature.admin.data.EmployeeDirectoryCloudSource
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Pulls credential-free staff identity projections before inbound financial processing.
 * Existing/tombstoned mappings are upserted, never used as login accounts.
 */
@Singleton
class DefaultStaffIdentitySync @Inject constructor(
    private val source: EmployeeDirectoryCloudSource,
    private val database: DmrDatabase,
    private val cashierMetadataSynchronizer: CashierMetadataSynchronizer
) : StaffIdentitySync {
    override suspend fun synchronize(businessId: String, branchId: String) {
        require(businessId.isNotBlank() && branchId.isNotBlank())
        val rows = source.listCashiers()
            .filter { it.branchId == branchId }
            .map { remote ->
                CloudStaffIdentityEntity(
                    membershipId = remote.membershipId,
                    localUserId = remote.userId,
                    businessId = businessId,
                    branchId = remote.branchId,
                    role = "CASHIER",
                    permissionsJson = JSONObject().apply {
                        remote.permissions.toSortedMap().forEach { (key, granted) ->
                            put(key, granted)
                        }
                    }.toString(),
                    isActive = remote.isActive,
                    isRemoved = remote.isRemoved,
                    updatedAt = System.currentTimeMillis()
                )
            }
        if (rows.isNotEmpty()) database.cloudStaffIdentityDao().upsertAll(rows)
    }

    override suspend fun refreshCashierPermissions(
        session: com.dailymoneyregister.core.security.ActiveSession
    ): Boolean = cashierMetadataSynchronizer.refresh(session)
}
