package com.dailymoneyregister.core.cloud.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.dailymoneyregister.core.security.SessionManager

@Serializable
data class IngestEnvelopeParams(
    @SerialName("p_business_id") val businessId: String,
    @SerialName("p_operation_id") val operationId: String,
    @SerialName("p_branch_id") val branchId: String,
    @SerialName("p_actor_membership_id") val actorMembershipId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_operation_type") val operationType: String,
    @SerialName("p_schema_version") val schemaVersion: Int,
    @SerialName("p_occurred_at") val occurredAtIso: String,
    @SerialName("p_branch_timezone_id") val branchTimezoneId: String,
    @SerialName("p_business_local_date") val businessLocalDate: String,
    @SerialName("p_canonical_text") val canonicalText: String,
    @SerialName("p_canonical_sha256") val canonicalSha256: String,
    @SerialName("p_canonical_payload") val canonicalPayload: JsonObject
)

@Serializable
data class IngestReceiptDto(
    @SerialName("status") val status: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("canonical_sha256") val canonicalSha256: String,
    @SerialName("duplicate") val duplicate: Boolean
)

@Serializable
data class FetchEnvelopesParams(
    @SerialName("p_business_id") val businessId: String,
    @SerialName("p_since_received_at") val sinceReceivedAtIso: String = "1970-01-01T00:00:00Z",
    @SerialName("p_limit") val limit: Int = 100
)

@Serializable
data class RemoteEnvelopeDto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("operation_type") val operationType: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("canonical_text") val canonicalText: String
)

@Singleton
class RemoteFinancialSyncRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun uploadEnvelope(
        envelope: FinancialEnvelopeCodec.EncodedEnvelope,
        actorMembershipId: String,
        branchTimezoneId: String,
        businessLocalDate: String = businessLocalDateFor(envelope.occurredAt, branchTimezoneId)
    ): FinancialSyncReceipt {
        android.util.Log.i(
            "PaidDueFinancialSync",
            "UPLOAD_TIMEZONE operation=${envelope.operationId} branch_timezone_id=$branchTimezoneId business_local_date=$businessLocalDate"
        )
        val currentSession =
            com.dailymoneyregister.core.security.SessionManager
                .currentSession.value

        if (
            currentSession?.user?.role ==
            com.dailymoneyregister.core.model.enums.UserRole.CASHIER
        ) {
            return try {
                val payload = JSONObject()
                    .put("p_business_id", envelope.businessId)
                    .put("p_operation_id", envelope.operationId)
                    .put("p_branch_id", envelope.branchId)
                    .put("p_actor_membership_id", actorMembershipId)
                    .put("p_device_id", envelope.deviceId)
                    .put("p_operation_type", envelope.operationType)
                    .put("p_schema_version", envelope.commandSchemaVersion)
                    .put(
                        "p_occurred_at",
                        java.time.Instant.ofEpochMilli(
                            envelope.occurredAt
                        ).toString()
                    )
                    .put("p_branch_timezone_id", branchTimezoneId)
                    .put("p_business_local_date", businessLocalDate)
                    .put("p_canonical_text", envelope.canonicalText)
                    .put("p_canonical_sha256", envelope.canonicalSha256)
                    .put(
                        "p_canonical_payload",
                        JSONObject(envelope.canonicalText)
                    )

                val result = CashierCloudSession.upload(
                    actorMembershipId,
                    payload
                )
                
                val status = result.optString("status")
                android.util.Log.i(
                    "PaidDueFinancialSync",
                    "CASHIER UPLOAD operation=${envelope.operationId} type=${envelope.operationType} http_status=200 receipt_status=$status reason=${result.optString("reason", "none")}"
                )

                if (
                    status != "ACKNOWLEDGED" ||
                    result.optString("operation_id") != envelope.operationId ||
                    result.optString("canonical_sha256") !=
                        envelope.canonicalSha256
                ) {
                    FinancialSyncReceipt.IntegrityConflict(
                        envelope.operationId
                    )
                } else {
                    FinancialSyncReceipt.Acknowledged(
                        operationId = envelope.operationId,
                        canonicalSha256 = envelope.canonicalSha256,
                        duplicate = result.optBoolean("duplicate")
                    )
                }
            } catch (e: SecurityException) {
                android.util.Log.w(
                    "PaidDueFinancialSync",
                    "CASHIER UPLOAD operation=${envelope.operationId} type=${envelope.operationType} outcome=UNAUTHORIZED reason=${e.message}"
                )
                FinancialSyncReceipt.Unauthorized(
                    envelope.operationId
                )
            } catch (e: FinancialUploadHttpException) {
                FinancialSyncErrorClassifier.classify(
                    operationId = envelope.operationId,
                    statusCode = e.statusCode,
                    code = null,
                    outcome = e.outcome,
                    serverMessage = e.serverMessage
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "PaidDueFinancialSync",
                    "CASHIER UPLOAD operation=${envelope.operationId} type=${envelope.operationType} outcome=RETRYABLE reason=${e.message}"
                )
                FinancialSyncReceipt.Retryable(
                    envelope.operationId,
                    RetryReason.NETWORK
                )
            }
        }

        // The RPC requires auth.uid() and validates this Admin membership server-side.
        // A local Room session is not a Supabase Auth session; fail closed and keep
        // the durable outbox row recoverable until the Admin signs in remotely.
        if (supabase.auth.currentSessionOrNull() == null) {
            android.util.Log.w(
                "PaidDueFinancialSync",
                "ADMIN_SUPABASE_AUTH_REQUIRED operation=${envelope.operationId}"
            )
            return FinancialSyncReceipt.Unauthorized(envelope.operationId)
        }

        return try {
            val isoOccurredAt = java.time.Instant.ofEpochMilli(envelope.occurredAt).toString()

            val params = IngestEnvelopeParams(
                businessId = envelope.businessId,
                operationId = envelope.operationId,
                branchId = envelope.branchId,
                actorMembershipId = actorMembershipId,
                deviceId = envelope.deviceId,
                operationType = envelope.operationType,
                schemaVersion = envelope.commandSchemaVersion,
                occurredAtIso = isoOccurredAt,
                branchTimezoneId = branchTimezoneId,
                businessLocalDate = businessLocalDate,
                canonicalText = envelope.canonicalText,
                canonicalSha256 = envelope.canonicalSha256,
                canonicalPayload = json.parseToJsonElement(envelope.canonicalText).jsonObject
            )

            val paramJsonObj = json.parseToJsonElement(
                json.encodeToString(IngestEnvelopeParams.serializer(), params)
            ).jsonObject

            val responseList = supabase.postgrest.rpc(
                function = "ingest_financial_envelope",
                parameters = paramJsonObj
            ).decodeList<IngestReceiptDto>()

            val receipt = responseList.firstOrNull()
                ?: return FinancialSyncReceipt.Retryable(envelope.operationId, RetryReason.SERVER_UNAVAILABLE)

            // Never treat a syntactically valid RPC row as success unless the
            // backend explicitly returned its committed acknowledgement status.
            if (receipt.status != "ACKNOWLEDGED") {
                android.util.Log.e(
                    "PaidDueFinancialSync",
                    "UPLOAD_RECEIPT_STATUS_UNEXPECTED operation=${envelope.operationId} status=${receipt.status}"
                )
                return FinancialSyncReceipt.NeedsAttention(
                    envelope.operationId,
                    "Unexpected server receipt status: ${receipt.status}"
                )
            }

            FinancialSyncReceipt.Acknowledged(
                operationId = receipt.operationId,
                canonicalSha256 = receipt.canonicalSha256,
                duplicate = receipt.duplicate
            )
        } catch (e: io.github.jan.supabase.postgrest.exception.PostgrestRestException) {
            android.util.Log.e(
                "PaidDueFinancialSync",
                "UPLOAD_FAILED",
                e
            )
            FinancialSyncErrorClassifier.classify(
                operationId = envelope.operationId,
                statusCode = e.statusCode,
                code = e.code,
                outcome = FinancialSyncErrorClassifier.extractOutcome(
                    e.error,
                    e.description,
                    e.details.toString()
                ),
                serverMessage = listOfNotNull(e.error, e.description)
                    .firstOrNull { it.isNotBlank() }
                    ?: "Server rejected the financial operation"
            )
        } catch (e: Exception) {
            android.util.Log.e("PaidDueFinancialSync", "UPLOAD_FAILED", e)
            FinancialSyncReceipt.Retryable(envelope.operationId, RetryReason.NETWORK)
        }
    }

    suspend fun isAdminSessionAuthorized(
        expectedMembershipId: String,
        expectedBusinessId: String,
        expectedBranchId: String
    ): Boolean {
        if (supabase.auth.currentSessionOrNull() == null) return false

        return runCatching {
            val rows = supabase.postgrest.rpc("restore_admin_by_auth_id")
                .decodeList<AdminSessionScopeDto>()
            val scope = rows.singleOrNull() ?: return@runCatching false
            scope.membershipId == expectedMembershipId &&
                scope.businessId == expectedBusinessId &&
                scope.branchId == expectedBranchId
        }.getOrDefault(false)
    }

    suspend fun fetchRemoteEnvelopesV2(
        businessId: String,
        afterReceivedAt: String? = null,
        afterOperationId: String? = null,
        limit: Int = 100
    ): List<RemoteEnvelopeV2> {
        require((afterReceivedAt == null && afterOperationId == null) || (afterReceivedAt != null && afterOperationId != null)) {
            "Both cursor params (afterReceivedAt, afterOperationId) must be null together or both non-null"
        }

        val activeSession = SessionManager.currentSession.value
        if (activeSession?.user?.role == com.dailymoneyregister.core.model.enums.UserRole.CASHIER) {
            val cashierMembershipId = activeSession.membership?.id
                ?: throw SecurityException("CASHIER_MEMBERSHIP_REQUIRED")
            if (CashierCloudSession.tokenFor(cashierMembershipId) == null) {
                // Do not fall through to the Admin Supabase RPC with a publishable key.
                throw SecurityException("CASHIER_CLOUD_SESSION_REQUIRED")
            }
            val rows = CashierCloudSession.download(
                cashierMembershipId,
                afterReceivedAt,
                afterOperationId,
                limit.coerceAtMost(100)
            )
            return rows.toEnvelopeList()
        }

        // The Admin download RPC also requires an authenticated Supabase identity.
        if (supabase.auth.currentSessionOrNull() == null) {
            throw SecurityException("ADMIN_SUPABASE_AUTH_REQUIRED")
        }

        val params = FetchEnvelopesV2Params(
            businessId = businessId,
            afterReceivedAt = afterReceivedAt,
            afterOperationId = afterOperationId,
            limit = limit.coerceAtMost(100)
        )

        val paramJsonObj = json.parseToJsonElement(
            json.encodeToString(FetchEnvelopesV2Params.serializer(), params)
        ).jsonObject

        val dtoList = supabase.postgrest.rpc(
            function = "fetch_financial_envelopes_v3",
            parameters = paramJsonObj
        ).decodeList<RemoteEnvelopeV2Dto>()

        return dtoList.map { dto ->
            val jsonPayload = if (dto.canonicalPayload != null) {
                JSONObject(dto.canonicalPayload.toString())
            } else {
                JSONObject(dto.canonicalText)
            }
            RemoteEnvelopeV2(
                operationId = dto.operationId,
                businessId = dto.businessId,
                branchId = dto.branchId,
                operationType = dto.operationType,
                occurredAtIso = dto.occurredAt,
                receivedAtIso = dto.receivedAt,
                canonicalText = dto.canonicalText,
                payloadJson = jsonPayload,
                canonicalSha256 = dto.canonicalSha256,
                actorMembershipId = dto.actorMembershipId,
                actorLocalUserId = dto.actorLocalUserId,
                actorRole = dto.actorRole
            )
        }
    }

    private fun org.json.JSONArray.toEnvelopeList(): List<RemoteEnvelopeV2> = (0 until length()).map { i ->
        val row = getJSONObject(i)
        val text = row.getString("canonical_text")
        RemoteEnvelopeV2(
            operationId = row.getString("operation_id"),
            businessId = row.getString("business_id"),
            branchId = row.optString("branch_id", null),
            operationType = row.getString("operation_type"),
            occurredAtIso = row.getString("occurred_at"),
            receivedAtIso = row.getString("received_at"),
            canonicalText = text,
            payloadJson = JSONObject(row.optJSONObject("canonical_payload")?.toString() ?: text),
            canonicalSha256 = row.optString("canonical_sha256").ifBlank { null },
            actorMembershipId = row.optString("actor_membership_id").ifBlank { null },
            actorLocalUserId = row.optString("actor_local_user_id").ifBlank { null },
            actorRole = row.optString("actor_role").ifBlank { null }
        )
    }

    private suspend fun fetchRemoteEnvelopes(
        businessId: String,
        sinceReceivedAtIso: String = "1970-01-01T00:00:00Z",
        limit: Int = 100
    ): List<JSONObject> {
        val params = FetchEnvelopesParams(businessId, sinceReceivedAtIso, limit)
        val paramJsonObj = json.parseToJsonElement(json.encodeToString(FetchEnvelopesParams.serializer(), params)).jsonObject

        val dtoList = supabase.postgrest.rpc(
                function = "fetch_financial_envelopes",
                parameters = paramJsonObj
            ).decodeList<RemoteEnvelopeDto>()

        return dtoList.map { JSONObject(it.canonicalText) }
    }
}

internal object FinancialSyncErrorClassifier {
    private val permanentOutcomes = setOf(
        "INVALID_ENVELOPE",
        "UNSUPPORTED_OPERATION_TYPE",
        "OPERATION_NOT_PERMITTED",
        "OPERATION_COLLISION",
        "DIGEST_MISMATCH",
        "LOCAL_DATE_MISMATCH",
        "BRANCH_TIMEZONE_MISMATCH",
        "INVALID_ADMIN_MEMBERSHIP"
    )

    fun extractOutcome(vararg structuredFields: String?): String? =
        structuredFields.asSequence()
            .filterNotNull()
            .flatMap { field -> Regex("[A-Z][A-Z0-9_]+").findAll(field).map { it.value } }
            .firstOrNull { it in permanentOutcomes }

    fun classify(
        operationId: String,
        statusCode: Int,
        code: String?,
        outcome: String?,
        serverMessage: String
    ): FinancialSyncReceipt {
        val normalizedOutcome = outcome?.trim()?.uppercase()
        if (normalizedOutcome in permanentOutcomes) {
            return FinancialSyncReceipt.NeedsAttention(
                operationId,
                serverMessage.ifBlank { normalizedOutcome!! }
            )
        }
        if (statusCode == 401 || statusCode == 403 || code == "42501") {
            return FinancialSyncReceipt.Unauthorized(operationId)
        }
        return if (statusCode >= 500 || statusCode == 408 || statusCode == 429) {
            FinancialSyncReceipt.Retryable(operationId, RetryReason.SERVER_UNAVAILABLE)
        } else {
            FinancialSyncReceipt.NeedsAttention(
                operationId,
                serverMessage.ifBlank { "Financial upload rejected (HTTP $statusCode)" }
            )
        }
    }
}

internal fun businessLocalDateFor(occurredAtMillis: Long, timezoneId: String): String =
    java.time.Instant.ofEpochMilli(occurredAtMillis)
        .atZone(java.time.ZoneId.of(timezoneId))
        .toLocalDate()
        .toString()

@Serializable
private data class AdminSessionScopeDto(
    @SerialName("membership_id") val membershipId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("branch_id") val branchId: String
)

@Serializable
data class FetchEnvelopesV2Params(
    @SerialName("p_business_id") val businessId: String,
    @SerialName("p_after_received_at") val afterReceivedAt: String? = null,
    @SerialName("p_after_operation_id") val afterOperationId: String? = null,
    @SerialName("p_limit") val limit: Int = 100
)

@Serializable
data class RemoteEnvelopeV2Dto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("operation_type") val operationType: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("canonical_text") val canonicalText: String,
    @SerialName("canonical_payload") val canonicalPayload: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("canonical_sha256") val canonicalSha256: String? = null,
    @SerialName("actor_membership_id") val actorMembershipId: String? = null,
    @SerialName("actor_local_user_id") val actorLocalUserId: String? = null,
    @SerialName("actor_role") val actorRole: String? = null
)

data class RemoteEnvelopeV2(
    val operationId: String,
    val businessId: String,
    val branchId: String?,
    val operationType: String,
    val occurredAtIso: String,
    val receivedAtIso: String,
    val canonicalText: String,
    val payloadJson: JSONObject,
    val canonicalSha256: String? = null,
    val actorMembershipId: String? = null,
    val actorLocalUserId: String? = null,
    val actorRole: String? = null
)


