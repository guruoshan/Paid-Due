package com.dailymoneyregister.core.cloud.sync

import android.util.Log
import com.dailymoneyregister.BuildConfig
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort business-scoped customer directory synchronization. Customer IDs
 * remain stable locally; canonical IDs are recorded as aliases so financial
 * history is never rewritten to accommodate another device's identity.
 */
@Singleton
class CustomerDirectorySyncClient @Inject constructor(
    private val database: DmrDatabase,
    private val supabase: SupabaseClient
) {
    private val lastSyncByBusiness = ConcurrentHashMap<String, Long>()

    suspend fun syncIfDue() = withContext(Dispatchers.IO) {
        val session = SessionManager.currentSession.value ?: return@withContext
        if (session.user.role != UserRole.ADMIN) {
            Log.i(TAG, "CUSTOMER_DIRECTORY_SYNC_SKIPPED_NON_OWNER_SESSION")
            return@withContext
        }
        val membershipId = session.membership?.id?.takeIf { it.isNotBlank() } ?: return@withContext
        val businessId = session.business.id
        val branchId = session.branch.id
        val syncKey = "$businessId:$branchId:${session.user.role.name}"
        val now = System.currentTimeMillis()
        val previous = lastSyncByBusiness[syncKey] ?: 0L
        if (now - previous < MIN_SYNC_INTERVAL_MILLIS) return@withContext

        val token = when (session.user.role) {
            UserRole.CASHIER -> CashierCloudSession.tokenFor(membershipId)
            UserRole.ADMIN -> supabase.auth.currentSessionOrNull()?.accessToken
            else -> null
        } ?: return@withContext

        val baseUrl = BuildConfig.PAID_DUE_BACKEND_BASE_URL.trimEnd('/')
        require(baseUrl.startsWith("https://")) { "CUSTOMER_SYNC_BACKEND_URL_INVALID" }
        // Throttle failures too; an unavailable backend must not be hammered every sync tick.
        lastSyncByBusiness[syncKey] = now

        // Push local identities first. Server phone uniqueness returns the same
        // canonical ID for independently-created records on different devices.
        val localCustomers = database.customerDao().getCustomersForBusiness(businessId)
        for (customer in localCustomers) {
            val response = request(
                url = "$baseUrl/customers/sync",
                method = "POST",
                token = token,
                body = JSONObject()
                    .put("business_id", businessId)
                    .put("branch_id", branchId)
                    .put("customer_id", customer.id)
                    .put("canonical_phone", customer.canonicalPhone)
                    .put("display_name", customer.name)
                    .put("created_at", Instant.ofEpochMilli(customer.createdAt).toString())
            )
            val row = response.optJSONObject("customer")
                ?: throw IOException("CUSTOMER_SYNC_RESPONSE_INVALID")
            reconcile(row, businessId)
        }

        // Pull the shared business directory. Existing local IDs are preserved;
        // remote IDs become aliases to those rows instead of replacing their keys.
        val query = "?business_id=${encode(businessId)}&branch_id=${encode(branchId)}&limit=500"
        val response = request(
            url = "$baseUrl/customers/sync$query",
            method = "GET",
            token = token,
            body = null
        )
        val customers = response.optJSONArray("customers") ?: JSONArray()
        for (index in 0 until customers.length()) {
            reconcile(customers.getJSONObject(index), businessId)
        }
        lastSyncByBusiness[syncKey] = System.currentTimeMillis()
        Log.i(TAG, "CUSTOMER_DIRECTORY_SYNC_OK business=$businessId pushed=${localCustomers.size} pulled=${customers.length()}")
    }

    private suspend fun reconcile(row: JSONObject, expectedBusinessId: String) {
        val customerId = row.optString("customer_id").ifBlank { row.optString("id") }
        val phone = row.optString("canonical_phone")
        val name = row.optString("display_name").ifBlank { row.optString("name") }
        val businessId = row.optString("business_id").ifBlank { expectedBusinessId }
        require(businessId == expectedBusinessId && customerId.isNotBlank() && name.isNotBlank()) {
            "CUSTOMER_SYNC_IDENTITY_INVALID"
        }
        val createdAt = parseInstant(row.optString("created_at")) ?: System.currentTimeMillis()
        val updatedAt = parseInstant(row.optString("updated_at")) ?: createdAt
        database.withTransaction {
            CustomerIdentityReconciler.reconcileRemoteCustomer(
                database = database,
                businessId = businessId,
                canonicalCustomerId = customerId,
                canonicalPhone = phone,
                name = name,
                createdAt = createdAt,
                updatedAt = updatedAt,
                isServerCanonical = true
            )
        }
    }

    private fun request(url: String, method: String, token: String, body: JSONObject?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.setRequestProperty("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val outcome = runCatching { JSONObject(text).optString("outcome", "UNKNOWN") }
                    .getOrDefault("UNKNOWN")
                Log.e(TAG, "CUSTOMER_SYNC_HTTP status=$status method=$method outcome=$outcome")
                throw IOException("CUSTOMER_SYNC_HTTP_$status outcome=$outcome")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseInstant(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TAG = "PaidDueCustomerSync"
        const val MIN_SYNC_INTERVAL_MILLIS = 60_000L
    }
}
