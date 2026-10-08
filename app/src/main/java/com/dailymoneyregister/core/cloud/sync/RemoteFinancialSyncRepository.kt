package com.dailymoneyregister.core.cloud.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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
    @SerialName("p_canonical_payload") val canonicalPayloadJson: String
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
        branchTimezoneId: String = "Asia/Muscat",
        businessLocalDate: String = java.time.Instant.ofEpochMilli(envelope.occurredAt)
            .atZone(java.time.ZoneId.of(branchTimezoneId)).toLocalDate().toString()
    ): FinancialSyncReceipt {
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
                canonicalPayloadJson = envelope.canonicalText
            )

            val paramJsonObj = kotlinx.serialization.json.JsonObject(
                json.parseToJsonElement(
                    json.encodeToString(
                        IngestEnvelopeParams.serializer(),
                        params
                    )
                ).jsonObject +
                    ("p_canonical_payload" to
                        json.parseToJsonElement(envelope.canonicalText))
            )

            val responseList = supabase.postgrest.rpc(
                function = "ingest_financial_envelope",
                parameters = paramJsonObj
            ).decodeList<IngestReceiptDto>()

            val receipt = responseList.firstOrNull()
                ?: return FinancialSyncReceipt.Retryable(envelope.operationId, RetryReason.SERVER_UNAVAILABLE)

            FinancialSyncReceipt.Acknowledged(
                operationId = receipt.operationId,
                canonicalSha256 = receipt.canonicalSha256,
                duplicate = receipt.duplicate
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "PaidDueFinancialSync",
                "UPLOAD_FAILED",
                e
            )
            val msg = e.message.orEmpty()
            if (msg.contains("Integrity conflict")) {
                FinancialSyncReceipt.IntegrityConflict(envelope.operationId)
            } else if (msg.contains("Access denied") || msg.contains("42501")) {
                FinancialSyncReceipt.Unauthorized(envelope.operationId)
            } else {
                FinancialSyncReceipt.Retryable(envelope.operationId, RetryReason.NETWORK)
            }
        }
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
            function = "fetch_financial_envelopes_v2",
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
                payloadJson = jsonPayload
            )
        }
    }

    suspend fun fetchRemoteEnvelopes(
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
    @SerialName("canonical_payload") val canonicalPayload: kotlinx.serialization.json.JsonElement? = null
)

data class RemoteEnvelopeV2(
    val operationId: String,
    val businessId: String,
    val branchId: String?,
    val operationType: String,
    val occurredAtIso: String,
    val receivedAtIso: String,
    val canonicalText: String,
    val payloadJson: JSONObject
)


