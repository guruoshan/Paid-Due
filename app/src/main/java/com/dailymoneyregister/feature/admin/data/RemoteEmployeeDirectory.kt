package com.dailymoneyregister.feature.admin.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class CloudCashierDirectoryEntry(
    @SerialName("local_user_id") val userId: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("canonical_phone") val canonicalPhone: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("permissions") val permissions: Map<String, Boolean>,
    @SerialName("is_removed") val isRemoved: Boolean = false
)

interface EmployeeDirectoryCloudSource {
    suspend fun listCashiers(): List<CloudCashierDirectoryEntry>
    suspend fun updateCashierPermissions(membershipId: String, permissions: Map<String, Boolean>)
    suspend fun removeCashier(membershipId: String)
}

/** Supabase is authoritative for staff across devices; scope and Admin access are checked in SQL. */
@Singleton
class RemoteEmployeeDirectory @Inject constructor(
    private val supabase: SupabaseClient
) : EmployeeDirectoryCloudSource {
    private fun requireAdminSession() {
        check(supabase.auth.currentSessionOrNull() != null) {
            "Sign in as Admin to manage cloud cashiers."
        }
    }

    override suspend fun listCashiers(): List<CloudCashierDirectoryEntry> {
        requireAdminSession()
        return supabase.postgrest.rpc("list_admin_cashiers_with_permissions").decodeList()
    }

    override suspend fun updateCashierPermissions(
        membershipId: String,
        permissions: Map<String, Boolean>
    ) {
        requireAdminSession()
        val params = buildJsonObject {
            put("p_membership_id", JsonPrimitive(membershipId))
            put("p_permissions", JsonObject(permissions.mapValues { (_, granted) -> JsonPrimitive(granted) }))
        }
        supabase.postgrest.rpc(
            function = "update_admin_cashier_permissions",
            parameters = params
        )
    }

    override suspend fun removeCashier(membershipId: String) {
        requireAdminSession()
        val params = buildJsonObject {
            put("p_membership_id", JsonPrimitive(membershipId))
        }
        supabase.postgrest.rpc(
            function = "remove_admin_cashier",
            parameters = params
        )
    }
}
