package com.dailymoneyregister.feature.admin.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CloudCashierDirectoryEntry(
    @SerialName("local_user_id") val userId: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("canonical_phone") val canonicalPhone: String,
    @SerialName("is_active") val isActive: Boolean
)

interface EmployeeDirectoryCloudSource {
    suspend fun listCashiers(): List<CloudCashierDirectoryEntry>
}

/** Read-only cloud directory. The server RPC derives tenant scope from auth.uid(). */
@Singleton
class RemoteEmployeeDirectory @Inject constructor(
    private val supabase: SupabaseClient
) : EmployeeDirectoryCloudSource {
    override suspend fun listCashiers(): List<CloudCashierDirectoryEntry> {
        check(supabase.auth.currentSessionOrNull() != null) {
            "Sign in as Admin to refresh the cloud employee directory"
        }
        return supabase.postgrest.rpc("list_admin_cashiers").decodeList()
    }
}
