package com.dailymoneyregister.feature.notifications.data

import com.dailymoneyregister.BuildConfig
import com.dailymoneyregister.core.cloud.sync.CashierCloudSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.enums.UserRole
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
            launch { runCatching { supabase.realtime.removeChannel(channel) } }
        }
    }

    suspend fun listNotifications(): List<BusinessNotification> {
        if (isAdmin()) {
            check(supabase.auth.currentSessionOrNull() != null) {
                "Admin sign-in is required to load notifications."
            }
            return supabase.postgrest.rpc("list_admin_notifications").decodeList()
        }
        val response = cashierCall("staff/notifications", "GET", null)
        val array = response.optJSONArray("notifications") ?: org.json.JSONArray()
        return json.decodeFromString<List<BusinessNotification>>(array.toString())
    }

    suspend fun listPermissionRequests(): List<PermissionRequestRecord> {
        if (!isAdmin()) return emptyList()
        check(supabase.auth.currentSessionOrNull() != null) {
            "Admin sign-in is required to review permission requests."
        }
        return supabase.postgrest.rpc("list_admin_permission_requests").decodeList()
    }

    suspend fun markRead(notificationId: String) {
        if (isAdmin()) {
            check(supabase.auth.currentSessionOrNull() != null) { "Admin sign-in is required." }
            supabase.postgrest.rpc(
                function = "mark_admin_notification_read",
                parameters = buildJsonObject { put("p_notification_id", JsonPrimitive(notificationId)) }
            )
        } else {
            cashierCall(
                "staff/notifications/read",
                "POST",
                JSONObject().put("notification_id", notificationId)
            )
        }
    }

    suspend fun requestPermission(permissionKey: String, note: String?) {
        require(permissionKey in ALLOWED_PERMISSION_KEYS) { "Unknown permission." }
        cashierCall(
            "staff/permission-requests",
            "POST",
            JSONObject().put("permission_key", permissionKey).put("note", note.orEmpty())
        )
    }

    suspend fun resolvePermissionRequest(
        requestId: String,
        approve: Boolean,
        additionalPermissions: Set<String> = emptySet()
    ) {
        check(isAdmin()) { "Only an Admin can decide permission requests." }
        check(supabase.auth.currentSessionOrNull() != null) { "Admin sign-in is required." }
        require(additionalPermissions.all { it in ALLOWED_PERMISSION_KEYS }) { "Unknown additional permission." }
        val extra = JsonObject(additionalPermissions.associateWith { JsonPrimitive(true) })
        supabase.postgrest.rpc(
            function = "resolve_admin_permission_request",
            parameters = buildJsonObject {
                put("p_request_id", JsonPrimitive(requestId))
                put("p_decision", JsonPrimitive(if (approve) "APPROVE" else "REJECT"))
                put("p_additional_permissions", extra)
            }
        )
    }

    private suspend fun cashierCall(path: String, method: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val membershipId = CashierCloudSession.currentMembershipId()
                ?: throw IllegalStateException("Cashier cloud session expired. Sign in again to continue.")
            val bearer = CashierCloudSession.tokenFor(membershipId)
                ?: throw IllegalStateException("Cashier cloud session expired. Sign in again to continue.")
            val baseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trimEnd('/') + "/functions/v1/api"
            require(baseUrl.startsWith("https://")) { "Supabase endpoint configuration is invalid." }
            val connection = URL("$baseUrl/api/$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 7000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
                connection.setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val outcome = runCatching { JSONObject(raw).optString("outcome") }.getOrNull()
                    throw IllegalStateException(outcome?.takeIf { it.isNotBlank() } ?: "Notification request failed (HTTP $status).")
                }
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        val ALLOWED_PERMISSION_KEYS = setOf(
            "CREATE_SALE", "ACCEPT_CASH", "ACCEPT_ONLINE", "CREATE_CREDIT_SALE", "USE_COUPON",
            "ISSUE_INVOICE", "REFUND", "VOID", "CORRECT_AMOUNT",
            "CHANGE_PAYMENT_METHOD", "VIEW_BROAD_HISTORY", "VIEW_CUSTOMERS"
        )
    }
}
