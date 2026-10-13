package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

@Serializable
private data class CloudStaffIdentityDto(
    @SerialName("local_user_id") val localUserId: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("role") val role: String,
    @SerialName("permissions") val permissions: Map<String, Boolean> = emptyMap(),
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("is_removed") val isRemoved: Boolean,
    @SerialName("updated_at") val updatedAt: String
)

/**
 * Downloads the server-authoritative membership projection before Admin-side
 * inbound materialization. This projection is deliberately separate from
 * user_accounts and never contains credentials or creates a login account.
 */
@Singleton
class CloudStaffIdentitySync @Inject constructor(
    private val supabase: SupabaseClient,
    private val database: DmrDatabase
) {
    suspend fun synchronize(businessId: String, branchId: String): Int {
        require(businessId.isNotBlank() && branchId.isNotBlank()) {
            "Staff identity sync requires an explicit business and branch scope"
        }

        val params = buildJsonObject {
            put("p_business_id", JsonPrimitive(businessId))
            put("p_branch_id", JsonPrimitive(branchId))
        }
        val remote = supabase.postgrest.rpc(
            function = "list_admin_sync_identities",
            parameters = params
        ).decodeList<CloudStaffIdentityDto>()

        require(remote.isNotEmpty()) {
            "Staff identity sync returned no membership rows for the active Admin branch"
        }
        require(remote.all {
            it.businessId == businessId && it.branchId == branchId &&
                it.localUserId.isNotBlank() && it.membershipId.isNotBlank() &&
                it.role in setOf("ADMIN", "CASHIER")
        }) {
            "Staff identity sync returned an out-of-scope or malformed membership"
        }

        val now = System.currentTimeMillis()
        val entities = remote.map { row ->
            CloudStaffIdentityEntity(
                membershipId = row.membershipId,
                localUserId = row.localUserId,
                businessId = row.businessId,
                branchId = row.branchId,
                role = row.role,
                permissionsJson = JSONObject(row.permissions).toString(),
                isActive = row.isActive,
                isRemoved = row.isRemoved,
                updatedAt = Instant.parse(row.updatedAt).toEpochMilli().takeIf { it > 0L } ?: now
            )
        }
        database.cloudStaffIdentityDao().upsertAll(entities)
        return entities.size
    }
}
