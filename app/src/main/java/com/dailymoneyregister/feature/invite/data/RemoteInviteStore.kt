package com.dailymoneyregister.feature.invite.data

import com.dailymoneyregister.BuildConfig
import com.dailymoneyregister.core.model.enums.UserRole
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.content
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RemoteInvitePreview(
    val invite: StaffInvite,
    val businessName: String,
    val branchName: String
)

data class RemoteStaffActivation(
    val staffIdentityId: String,
    val membershipId: String,
    val localUserId: String,

    val businessId: String,
    val businessName: String,

    val branchId: String,
    val branchName: String,
    val branchCode: String,

    val countryCode: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val currencySymbol: String,
    val timezoneId: String,

    val taxRoundingMode: String,
    val defaultTaxRateBps: Int,

    val branchAddress: String?,
    val branchPhone: String?,
    val taxRegistrationNumber: String?,

    val displayName: String,
    val canonicalPhone: String,
    val permissions: Map<String, Boolean>,

    val activatedAtMillis: Long
)

sealed interface RemoteStaffActivationResult {

    data class Activated(
        val activation: RemoteStaffActivation,
        val wasAlreadyActivated: Boolean
    ) : RemoteStaffActivationResult

    data object InvalidInvite :
        RemoteStaffActivationResult

    data object PhoneAlreadyRegistered :
        RemoteStaffActivationResult
}

/**
 * Supabase is the shared authoritative employee-invite store.
 *
 * Raw tokens are never stored here.
 */
@Singleton
class RemoteInviteStore @Inject constructor(
    private val supabase: SupabaseClient
) : InviteStore, StaffInviteLinkCreator {

    private val activationHttpClient =
        HttpClient(Android) {
            expectSuccess = false
        }

    private val activationJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Serializable
    private data class PreviewInviteRpcRow(
        @SerialName("business_id")
        val businessId: String,

        @SerialName("business_name")
        val businessName: String,

        @SerialName("branch_id")
        val branchId: String,

        @SerialName("branch_name")
        val branchName: String,

        @SerialName("display_name")
        val displayName: String,

        @SerialName("canonical_phone")
        val canonicalPhone: String,

        @SerialName("role")
        val role: String,

        @SerialName("permissions")
        val permissions: Map<String, Boolean>,

        @SerialName("expires_at")
        val expiresAt: String
    )

    @Serializable
    private data class ActivateStaffInviteRpcRow(
        val outcome: String,

        @SerialName("staff_identity_id")
        val staffIdentityId: String? = null,

        @SerialName("membership_id")
        val membershipId: String? = null,

        @SerialName("local_user_id")
        val localUserId: String? = null,

        @SerialName("business_id")
        val businessId: String? = null,

        @SerialName("business_name")
        val businessName: String? = null,

        @SerialName("branch_id")
        val branchId: String? = null,

        @SerialName("branch_name")
        val branchName: String? = null,

        @SerialName("branch_code")
        val branchCode: String? = null,

        @SerialName("country_code")
        val countryCode: String? = null,

        @SerialName("currency_code")
        val currencyCode: String? = null,

        @SerialName("currency_decimal_places")
        val currencyDecimalPlaces: Int? = null,

        @SerialName("currency_symbol")
        val currencySymbol: String? = null,

        @SerialName("timezone_id")
        val timezoneId: String? = null,

        @SerialName("tax_rounding_mode")
        val taxRoundingMode: String? = null,

        @SerialName("default_tax_rate_bps")
        val defaultTaxRateBps: Int? = null,

        @SerialName("branch_address")
        val branchAddress: String? = null,

        @SerialName("branch_phone")
        val branchPhone: String? = null,

        @SerialName("tax_registration_number")
        val taxRegistrationNumber: String? = null,

        @SerialName("display_name")
        val displayName: String? = null,

        @SerialName("canonical_phone")
        val canonicalPhone: String? = null,

        val role: String? = null,

        val permissions: Map<String, Boolean>? = null,

        @SerialName("activated_at")
        val activatedAt: String? = null
    )

    override suspend fun create(
        invite: StaffInvite
    ) {
        error("Staff invitations must be created through the authenticated Supabase invitation service.")
    }

    override suspend fun createInviteLink(
        businessId: String,
        branchId: String,
        displayName: String,
        canonicalPhone: String,
        permissions: Map<String, Boolean>
    ): CreatedInvite {
        val accessToken = supabase.auth.currentSessionOrNull()?.accessToken
            ?: error("Sign in as Admin before inviting staff.")

        val supabaseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trim().trimEnd('/')
        check(supabaseUrl.startsWith("https://")) {
            "Paid&Due Supabase is not configured."
        }

        val requestBody = buildJsonObject {
            put("business_id", JsonPrimitive(businessId))
            put("branch_id", JsonPrimitive(branchId))
            put("display_name", JsonPrimitive(displayName.trim()))
            put("canonical_phone", JsonPrimitive(canonicalPhone))
            put("permissions", permissionsJson(permissions))
        }.toString()

        val response = activationHttpClient.post("$supabaseUrl/functions/v1/staff-invite") {
            header(HttpHeaders.ContentType, "application/json")
            header("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(requestBody)
        }

        val body = response.bodyAsText()
        val responseJson = runCatching {
            activationJson.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: error("Supabase returned an invalid invitation response.")
        val outcome = responseJson["outcome"]?.jsonPrimitive?.content
        if (response.status.value !in 200..299 || outcome != "INVITATION_CREATED") {
            val message = when (outcome) {
                "RATE_LIMITED" -> "Too many invitations were created. Try again later."
                "UNAUTHORIZED" -> "Your Admin session expired. Sign in again."
                "BACKEND_NOT_CONFIGURED" -> "Supabase staff invitation settings are incomplete."
                else -> "Unable to create a staff invitation link."
            }
            error(message)
        }

        val inviteUrl = responseJson["invite_url"]?.jsonPrimitive?.content
            ?: error("Supabase did not return an invitation link.")
        val expiresAt = responseJson["expires_at"]?.jsonPrimitive?.content
            ?: error("Supabase did not return the invitation expiry.")
        check(inviteUrl.startsWith("$supabaseUrl/functions/v1/staff-open?t=")) {
            "Supabase returned an unexpected invitation URL."
        }

        return CreatedInvite(
            displayName = displayName.trim(),
            canonicalPhone = canonicalPhone,
            inviteUrl = inviteUrl,
            expiresAt = OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli()
        )
    }

    suspend fun previewByTokenHash(
        hash: String
    ): RemoteInvitePreview? {

        if (!TOKEN_HASH_REGEX.matches(hash)) {
            return null
        }

        val parameters =
            buildJsonObject {
                put(
                    "p_token_hash",
                    JsonPrimitive(hash)
                )
            }

        val rows =
            supabase.postgrest
                .rpc(
                    function = "preview_staff_invite",
                    parameters = parameters
                )
                .decodeList<PreviewInviteRpcRow>()

        val row =
            rows.singleOrNull()
                ?: return null

        if (row.role != UserRole.CASHIER.name) {
            return null
        }

        val expiresAtMillis =
            runCatching {
                OffsetDateTime
                    .parse(row.expiresAt)
                    .toInstant()
                    .toEpochMilli()
            }.getOrElse {
                return null
            }

        val invite =
            StaffInvite(
                tokenHash = hash,
                businessId = row.businessId,
                branchId = row.branchId,
                displayName = row.displayName,
                canonicalPhone = row.canonicalPhone,
                role = UserRole.CASHIER,
                permissions = row.permissions,
                createdAt =
                    expiresAtMillis -
                        INVITE_LIFETIME_MILLIS,
                expiresAt = expiresAtMillis,
                usedAt = null,
                revokedAt = null
            )

        return RemoteInvitePreview(
            invite = invite,
            businessName = row.businessName,
            branchName = row.branchName
        )
    }

    suspend fun activate(
        token: String,
        pin: String
    ): RemoteStaffActivationResult {
        require(TOKEN_REGEX.matches(token)) { "Invalid invitation token" }
        require(PIN_REGEX.matches(pin)) { "Invalid Cashier PIN" }

        val supabaseUrl = BuildConfig.PAID_DUE_SUPABASE_URL.trim().trimEnd('/')
        check(supabaseUrl.startsWith("https://")) { "Paid&Due Supabase is not configured" }

        val requestBody = buildJsonObject {
            put("token", JsonPrimitive(token))
            put("pin", JsonPrimitive(pin))
        }.toString()

        val response = activationHttpClient.post("$supabaseUrl/functions/v1/staff-activate") {
            header(HttpHeaders.ContentType, "application/json")
            header("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            setBody(requestBody)
        }

        val body = response.bodyAsText()
        val row = runCatching {
            activationJson.decodeFromString(ActivateStaffInviteRpcRow.serializer(), body)
        }.getOrElse {
            error("Paid&Due activation service returned an invalid response")
        }

        return when (row.outcome) {
            "INVALID_INVITE" -> RemoteStaffActivationResult.InvalidInvite
            "PHONE_ALREADY_REGISTERED" -> RemoteStaffActivationResult.PhoneAlreadyRegistered
            "ACTIVATED", "ALREADY_ACTIVATED" -> {
                check(response.status.value == 200) {
                    "Paid&Due activation service returned an unexpected HTTP status"
                }
                RemoteStaffActivationResult.Activated(
                    activation = requireActivation(row),
                    wasAlreadyActivated = row.outcome == "ALREADY_ACTIVATED"
                )
            }
            "RATE_LIMITED" -> error("Too many activation attempts. Try again later.")
            else -> error("Paid&Due activation service is unavailable")
        }
    }

    override suspend fun findByTokenHash(
        hash: String
    ): StaffInvite? =
        previewByTokenHash(hash)?.invite

    /**
     * Direct token consumption is intentionally disabled.
     *
     * Invitation consumption may occur only inside
     * activate_staff_invite().
     */
    override suspend fun markUsedIfUnused(
        hash: String,
        now: Long
    ): Boolean = false

    override suspend fun revoke(
        hash: String,
        now: Long
    ) {
        throw IllegalStateException(
            "Remote invitation revocation requires authoritative invite ID"
        )
    }

    override suspend fun listForBusiness(
        businessId: String
    ): List<StaffInvite> {
        throw IllegalStateException(
            "Remote invitation listing requires remote invite identity projection"
        )
    }

    private fun requireActivation(
        row: ActivateStaffInviteRpcRow
    ): RemoteStaffActivation {

        check(
            row.role == UserRole.CASHIER.name
        ) {
            "Remote activation returned a non-CASHIER role"
        }

        val activatedAtMillis =
            OffsetDateTime
                .parse(
                    requireNotNull(row.activatedAt)
                )
                .toInstant()
                .toEpochMilli()

        return RemoteStaffActivation(
            staffIdentityId =
                requireNotNull(row.staffIdentityId),

            membershipId =
                requireNotNull(row.membershipId),

            localUserId =
                requireNotNull(row.localUserId),

            businessId =
                requireNotNull(row.businessId),

            businessName =
                requireNotNull(row.businessName),

            branchId =
                requireNotNull(row.branchId),

            branchName =
                requireNotNull(row.branchName),

            branchCode =
                requireNotNull(row.branchCode),

            countryCode =
                requireNotNull(row.countryCode),

            currencyCode =
                requireNotNull(row.currencyCode),

            currencyDecimalPlaces =
                requireNotNull(
                    row.currencyDecimalPlaces
                ),

            currencySymbol =
                requireNotNull(row.currencySymbol),

            timezoneId =
                requireNotNull(row.timezoneId),

            taxRoundingMode =
                requireNotNull(
                    row.taxRoundingMode
                ),

            defaultTaxRateBps =
                requireNotNull(
                    row.defaultTaxRateBps
                ),

            branchAddress =
                row.branchAddress,

            branchPhone =
                row.branchPhone,

            taxRegistrationNumber =
                row.taxRegistrationNumber,

            displayName =
                requireNotNull(row.displayName),

            canonicalPhone =
                requireNotNull(row.canonicalPhone),

            permissions =
                requireNotNull(row.permissions),

            activatedAtMillis =
                activatedAtMillis
        )
    }

    private fun permissionsJson(
        permissions: Map<String, Boolean>
    ): JsonObject =
        JsonObject(
            permissions.mapValues { (_, granted) ->
                JsonPrimitive(granted)
            }
        )

    private companion object {

        val TOKEN_HASH_REGEX =
            Regex("^[0-9a-f]{64}$")

        val TOKEN_REGEX =
            Regex("""^[A-Za-z0-9_-]{32,128}$""")

        val PIN_REGEX =
            Regex("""^[0-9]{6}$""")

        val CANONICAL_PHONE_REGEX =
            Regex("""^\+[1-9][0-9]{7,14}$""")

        val CREDENTIAL_HASH_REGEX =
            Regex(
                "^pbkdf2-sha256:600000:[0-9a-f]{64}$"
            )

        val CREDENTIAL_SALT_REGEX =
            Regex("^[0-9a-f]{32}$")

        const val INVITE_LIFETIME_MILLIS =
            48L * 60L * 60L * 1000L
    }
}