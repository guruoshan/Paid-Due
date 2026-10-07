package com.dailymoneyregister.core.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RemoteAdminBootstrapParams(

    @SerialName("p_business_id")
    val businessId: String,

    @SerialName("p_business_name")
    val businessName: String,

    @SerialName("p_branch_id")
    val branchId: String,

    @SerialName("p_branch_name")
    val branchName: String,

    @SerialName("p_branch_code")
    val branchCode: String,

    @SerialName("p_country_code")
    val countryCode: String,

    @SerialName("p_currency_code")
    val currencyCode: String,

    @SerialName("p_currency_decimal_places")
    val currencyDecimalPlaces: Int,

    @SerialName("p_currency_symbol")
    val currencySymbol: String,

    @SerialName("p_timezone_id")
    val timezoneId: String,

    @SerialName("p_tax_rounding_mode")
    val taxRoundingMode: String,

    @SerialName("p_default_tax_rate_bps")
    val defaultTaxRateBps: Int,

    @SerialName("p_branch_address")
    val branchAddress: String? = null,

    @SerialName("p_branch_phone")
    val branchPhone: String? = null,

    @SerialName("p_tax_registration_number")
    val taxRegistrationNumber: String? = null,

    @SerialName("p_local_admin_user_id")
    val localAdminUserId: String,

    @SerialName("p_admin_display_name")
    val adminDisplayName: String,

    @SerialName("p_admin_username")
    val adminUsername: String,

    @SerialName("p_admin_canonical_phone")
    val adminCanonicalPhone: String? = null,

    @SerialName("p_language_code")
    val languageCode: String,

    @SerialName("p_membership_id")
    val membershipId: String
)

@Serializable
data class RemoteAdminBootstrapResult(

    @SerialName("business_id")
    val businessId: String,

    @SerialName("branch_id")
    val branchId: String,

    @SerialName("local_admin_user_id")
    val localAdminUserId: String,

    @SerialName("membership_id")
    val membershipId: String,

    @SerialName("auth_user_id")
    val authUserId: String
)

sealed interface RemoteBootstrapResult {

    data class Success(
        val value: RemoteAdminBootstrapResult
    ) : RemoteBootstrapResult

    data class Failed(
        val message: String
    ) : RemoteBootstrapResult
}

@Singleton
class RemoteAdminBootstrapRepository @Inject constructor(
    private val supabase: SupabaseClient
) {

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    suspend fun bootstrap(
        params: RemoteAdminBootstrapParams
    ): RemoteBootstrapResult {

        return try {

            val parameterObject =
                json
                    .parseToJsonElement(
                        json.encodeToString(params)
                    )
                    .jsonObject

            val rows =
                supabase.postgrest
                    .rpc(
                        function = "bootstrap_admin_tenant",
                        parameters = parameterObject
                    )
                    .decodeList<RemoteAdminBootstrapResult>()

            val result =
                rows.singleOrNull()
                    ?: return RemoteBootstrapResult.Failed(
                        "Admin bootstrap returned an unexpected result"
                    )

            if (
                result.businessId != params.businessId ||
                result.branchId != params.branchId ||
                result.localAdminUserId != params.localAdminUserId ||
                result.membershipId != params.membershipId
            ) {
                return RemoteBootstrapResult.Failed(
                    "Remote Admin bootstrap identity mismatch"
                )
            }

            RemoteBootstrapResult.Success(
                value = result
            )

        } catch (error: Throwable) {

            RemoteBootstrapResult.Failed(
                error.message ?: "Remote Admin bootstrap failed"
            )
        }
    }
}