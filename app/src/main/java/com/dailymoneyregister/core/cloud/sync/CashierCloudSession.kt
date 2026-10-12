package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.dailymoneyregister.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CashierCloudSession {

    private const val TAG = "PaidDueFinancialSync"

    @Volatile
    var lastAuthStage: String = "NOT_STARTED"
        private set
    private const val STORE = "paid_due_cashier_sync_credentials"
    private const val ENTRY = "encrypted_session"
    private const val KEY_ALIAS = "paid_due_cashier_sync_aes_v1"

    private data class Credential(
        val token: String,
        val membershipId: String,
        val expiresAt: Instant
    )

    @Volatile
    private var credential: Credential? = null

    @Volatile
    private var pendingRestoration: JSONObject? = null

    @Volatile
    private var applicationContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        applicationContext = context.applicationContext

        // Never overwrite a freshly authenticated in-memory session.
        if (credential != null) return

        val stored = applicationContext!!
            .getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(ENTRY, null) ?: return

        try {
            val parts = stored.split(":", limit = 2)
            require(parts.size == 2)

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )

            val data = JSONObject(
                String(
                    cipher.doFinal(encrypted),
                    StandardCharsets.UTF_8
                )
            )

            val restored = Credential(
                token = data.getString("token"),
                membershipId = data.getString("membership_id"),
                expiresAt = Instant.parse(
                    data.getString("expires_at")
                )
            )

            if (
                restored.token.matches(Regex("^[0-9a-f]{64}$")) &&
                restored.membershipId.isNotBlank() &&
                Instant.now().isBefore(restored.expiresAt)
            ) {
                credential = restored
                Log.i(TAG, "CASHIER_CLOUD_SESSION_RESTORED")
            } else {
                clear()
            }
        } catch (e: Exception) {
            // Invalid, expired, or undecryptable credentials
            // must never become an authenticated session.
            clear()
            Log.w(TAG, "CASHIER_STORED_SESSION_INVALID")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)

        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }

    @Synchronized
    private fun persist(value: Credential) {
        val context = applicationContext
            ?: throw IllegalStateException(
                "CASHIER_CLOUD_SESSION_NOT_INITIALIZED"
            )

        val plaintext = JSONObject()
            .put("token", value.token)
            .put("membership_id", value.membershipId)
            .put("expires_at", value.expiresAt.toString())
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )

        val encrypted = Base64.encodeToString(
            cipher.doFinal(plaintext),
            Base64.NO_WRAP
        )

        val saved = context
            .getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit()
            .putString(ENTRY, "$iv:$encrypted")
            .commit()

        check(saved) {
            "CASHIER_CLOUD_SESSION_STORAGE_FAILED"
        }
    }

    @Synchronized
    fun clear() {
        pendingRestoration = null
        credential = null
        applicationContext
            ?.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(ENTRY)
            ?.commit()
    }

    fun tokenFor(membershipId: String): String? {
        val value = credential ?: return null

        if (
            value.membershipId != membershipId ||
            !Instant.now().isBefore(value.expiresAt)
        ) {
            return null
        }

        return value.token
    }

    /** The active server membership ID, exposed only while the encrypted cloud session is valid. */
    fun currentMembershipId(): String? {
        val value = credential ?: return null
        if (!Instant.now().isBefore(value.expiresAt)) return null
        return value.membershipId
    }

    suspend fun authenticate(
        canonicalPhone: String,
        pin: String,
        expectedMembershipId: String
    ): Boolean = withContext(Dispatchers.IO) {

        lastAuthStage = "STARTED"
        clear()

        if (
            !canonicalPhone.matches(
                Regex("^\\+[1-9][0-9]{7,14}$")
            ) ||
            !pin.matches(Regex("^[0-9]{6}$"))
        ) {
            lastAuthStage = "INPUT_INVALID"
            Log.w(TAG, "CASHIER_CLOUD_AUTH_INPUT_INVALID")
            return@withContext false
        }

        val baseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trimEnd('/') + "/functions/v1/api"

        if (!baseUrl.startsWith("https://")) {
            lastAuthStage = "BACKEND_URL_INVALID"
            Log.e(TAG, "CASHIER_BACKEND_URL_INVALID")
            return@withContext false
        }

        val connection = (
            URL("${baseUrl}/staff/login")
                .openConnection() as HttpURLConnection
        )

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            connection.setRequestProperty(
                "apikey",
                BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY
            )

            val request = JSONObject()
                .put("canonical_phone", canonicalPhone)
                .put("pin", pin)

            connection.outputStream.use {
                it.write(
                    request.toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val status = connection.responseCode
            lastAuthStage = "HTTP_$status"

            if (status != 200) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                val outcome = runCatching {
                    JSONObject(errorBody).optString("outcome", "UNKNOWN")
                }.getOrDefault("UNKNOWN")
                Log.w(
                    TAG,
                    "CASHIER_CLOUD_AUTH_HTTP_$status path=/staff/login outcome=$outcome"
                )
                return@withContext false
            }

            lastAuthStage = "READING_RESPONSE"
            val response = JSONObject(
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
            )

            val membershipId =
                response.optString("membership_id")

            val token =
                response.optString("session_token")

            lastAuthStage = "VALIDATING_RESPONSE"
            val expiresAt = runCatching {
                Instant.parse(
                    response.optString("expires_at")
                )
            }.getOrNull() ?: return@withContext false

            if (
                response.optString("outcome") != "AUTHENTICATED" ||
                (expectedMembershipId.isNotBlank() && membershipId != expectedMembershipId) ||
                !token.matches(Regex("^[0-9a-f]{64}$")) ||
                !Instant.now().isBefore(expiresAt)
            ) {
                Log.w(
                    TAG,
                    "CASHIER_CLOUD_AUTH_RESPONSE_REJECTED"
                )
                return@withContext false
            }

            val authenticated = Credential(
                token = token,
                membershipId = membershipId,
                expiresAt = expiresAt
            )

            // Do not advertise authentication until
            // the encrypted credential has been saved.
            lastAuthStage = "SAVING_SESSION"
            persist(authenticated)
            credential = authenticated
            pendingRestoration = response.optJSONObject("restoration")
            lastAuthStage = if (pendingRestoration == null) {
                "RESTORATION_MISSING"
            } else {
                "AUTHENTICATED"
            }

            Log.i(TAG, "CASHIER_CLOUD_AUTHENTICATED")
            true

        } finally {
            connection.disconnect()
        }
    }

    suspend fun authenticateForRestoration(
        canonicalPhone: String,
        pin: String
    ): JSONObject? {
        if (!authenticate(canonicalPhone, pin, expectedMembershipId = "")) {
            return null
        }
        val payload = pendingRestoration
        pendingRestoration = null
        if (payload == null) clear()
        return payload
    }

    suspend fun upload(
        membershipId: String,
        envelope: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {

        val token = tokenFor(membershipId)
            ?: throw SecurityException(
                "CASHIER_CLOUD_SESSION_REQUIRED"
            )

        val baseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trimEnd('/') + "/functions/v1/api"

        require(baseUrl.startsWith("https://"))

        val connection = (
            URL("${baseUrl}/financial/upload")
                .openConnection() as HttpURLConnection
        )

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            connection.setRequestProperty(
                "apikey",
                BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY
            )

            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )

            connection.outputStream.use {
                it.write(
                    envelope.toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val status = connection.responseCode
            if (status == 401 || status == 403) {
                clear()
                throw SecurityException(
                    "CASHIER_CLOUD_SESSION_REJECTED"
                )
            }

            if (status !in 200..299) {
                val responseBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                val outcome = runCatching {
                    JSONObject(responseBody)
                        .optString("outcome", "UNKNOWN")
                }.getOrDefault("UNKNOWN")

                Log.w(TAG, "CASHIER_CLOUD_HTTP path=/financial/upload status=$status outcome=$outcome")
                throw FinancialUploadHttpException(
                    statusCode = status,
                    outcome = outcome,
                    serverMessage = responseBody
                )
            }

            JSONObject(
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
            )

        } finally {
            connection.disconnect()
        }
    }

    /** Fetch the authoritative cashier metadata using the existing custom sync session. */
    suspend fun fetchMetadata(membershipId: String): JSONObject = withContext(Dispatchers.IO) {
        val token = tokenFor(membershipId)
            ?: throw SecurityException("CASHIER_CLOUD_SESSION_REQUIRED")
        val baseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trimEnd('/') + "/functions/v1/api"
        require(baseUrl.startsWith("https://"))
        val connection = (URL("${baseUrl}/staff/metadata").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val outcome = runCatching { JSONObject(errorBody).optString("outcome", "UNKNOWN") }.getOrDefault("UNKNOWN")
                Log.w(TAG, "CASHIER_CLOUD_HTTP path=/staff/metadata status=$status outcome=$outcome")
                if (status == 401 || status == 403) {
                    clear()
                    throw SecurityException("CASHIER_CLOUD_SESSION_REJECTED")
                }
                throw IOException("CASHIER_METADATA_HTTP_$status outcome=$outcome")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val metadata = JSONObject(body)
            if (metadata.optString("membership_id") != membershipId) {
                clear()
                throw SecurityException("CASHIER_METADATA_MEMBERSHIP_MISMATCH")
            }
            metadata
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        membershipId: String,
        afterReceivedAt: String?,
        afterOperationId: String?,
        limit: Int
    ): JSONArray = withContext(Dispatchers.IO) {
        val token = tokenFor(membershipId) ?: throw SecurityException("CASHIER_CLOUD_SESSION_REQUIRED")
        val baseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trimEnd('/') + "/functions/v1/api"
        require(baseUrl.startsWith("https://"))
        val query = buildString {
            append("?limit=").append(limit)
            if (afterReceivedAt != null) append("&after_received_at=").append(java.net.URLEncoder.encode(afterReceivedAt, "UTF-8"))
            if (afterOperationId != null) append("&after_operation_id=").append(java.net.URLEncoder.encode(afterOperationId, "UTF-8"))
        }
        val connection = (URL("${baseUrl}/financial/download$query").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.setRequestProperty("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val outcome = runCatching { JSONObject(errorBody).optString("outcome", "UNKNOWN") }.getOrDefault("UNKNOWN")
                Log.w(TAG, "CASHIER_CLOUD_HTTP path=/financial/download status=$status outcome=$outcome")
                if (status == 401 || status == 403) {
                    clear()
                    throw SecurityException("CASHIER_CLOUD_SESSION_REJECTED")
                }
                throw IOException("CASHIER_DOWNLOAD_HTTP_$status outcome=$outcome")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONArray("envelopes") ?: JSONArray()
        } finally { connection.disconnect() }
    }
}

class FinancialUploadHttpException(
    val statusCode: Int,
    val outcome: String,
    val serverMessage: String
) : Exception("Financial upload rejected with HTTP $statusCode ($outcome)")
