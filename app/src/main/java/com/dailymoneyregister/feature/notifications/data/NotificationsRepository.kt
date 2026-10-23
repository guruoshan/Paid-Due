package com.dailymoneyregister.feature.notifications.data

import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.enums.UserRole
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BusinessNotification(
    val id: String,
    @SerialName("notification_type") val notificationType: String,
    val title: String,
    val body: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("branch_name") val branchName: String? = null,
    @SerialName("recipient_membership_id") val recipientMembershipId: String? = null
)

@Serializable
data class PermissionRequestRecord(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("branch_name") val branchName: String,
    @SerialName("requester_membership_id") val requesterMembershipId: String,
    @SerialName("requester_name") val requesterName: String,
    @SerialName("permission_key") val permissionKey: String,
    val note: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String
)

/** Durable server-backed inbox. Finance figures are displayed from close-event evidence, never recalculated here. */
@Singleton
class NotificationsRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isAdmin(): Boolean =
        SessionManager.currentSession.value?.user?.role == UserRole.ADMIN

    /**
     * Admin-only realtime invalidation flow. The private-schema RLS policy is the
     * authorization boundary; callers reload the bounded server inbox after each insert.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAdminNotificationInserts(): Flow<Unit> = callbackFlow {
        if (!isAdmin() || supabase.auth.currentSessionOrNull() == null) {
            close()
            return@callbackFlow
        }
        val userId = SessionManager.currentSession.value?.user?.id ?: "admin"
        val channel = supabase.channel("paid-due-notifications-$userId")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "private") {
            table = "business_notifications"
        }
        val watcher = launch {
            changes.collect { trySend(Unit) }
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitClose { watcher.cancel() }
        } finally {
            watcher.cancel()
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    suspend fun listNotifications(): List<BusinessNotification> {
        if (isAdmin()) {
            check(supabase.auth.currentSessionOrNull() != null) {
                "Admin sign-in is required to load notifications."
            }
            return supabase.postgrest.rpc("list_admin_notifications").decodeList()
        }
        return emptyList()
    }

    suspend fun listPermissionRequests(): List<PermissionRequestRecord> = emptyList()

    suspend fun markRead(notificationId: String) {
        if (isAdmin()) {
            check(supabase.auth.currentSessionOrNull() != null) { "Admin sign-in is required." }
            supabase.postgrest.rpc(
                function = "mark_admin_notification_read",
                parameters = buildJsonObject { put("p_notification_id", JsonPrimitive(notificationId)) }
            )
        }
    }

    suspend fun requestPermission(permissionKey: String, note: String?) {
        throw IllegalStateException("Cashier permissions are retired.")
    }

    suspend fun resolvePermissionRequest(
        requestId: String,
        approve: Boolean,
        additionalPermissions: Set<String> = emptySet()
    ) {
        throw IllegalStateException("Cashier permissions are retired.")
    }


    companion object {
        val ALLOWED_PERMISSION_KEYS = setOf(
            "CREATE_SALE", "ACCEPT_CASH", "ACCEPT_ONLINE", "CREATE_CREDIT_SALE", "USE_COUPON",
            "ISSUE_INVOICE", "REFUND", "VOID", "CORRECT_AMOUNT",
            "CHANGE_PAYMENT_METHOD", "VIEW_BROAD_HISTORY"
        )
    }
}
