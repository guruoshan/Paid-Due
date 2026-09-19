package com.dailymoneyregister.feature.setup.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.serialization.DurableEnvelopeCodec
import com.dailymoneyregister.core.model.tax.TaxRoundingMode

data class SetupRecoveryPayload(
    val operationId: String,
    val businessId: String,
    val branchId: String,
    val adminUserId: String,
    val membershipId: String,
    val occurredAt: Long,
    val deviceId: String,

    val businessName: String,
    val branchName: String,
    val branchCode: String,
    val countryCode: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val currencySymbol: String,
    val timezoneId: String,
    val taxRoundingMode: TaxRoundingMode,
    val defaultTaxRateBps: Int,

    val adminDisplayName: String,
    val adminUsername: String,
    val adminCanonicalPhone: String? = null,
    val adminCredentialHash: String,
    val adminCredentialSalt: String,
    val languageCode: String
)

object SetupPayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(payload: SetupRecoveryPayload): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("operationId=${DurableEnvelopeCodec.escape(payload.operationId)}")
        appendLine("businessId=${DurableEnvelopeCodec.escape(payload.businessId)}")
        appendLine("branchId=${DurableEnvelopeCodec.escape(payload.branchId)}")
        appendLine("adminUserId=${DurableEnvelopeCodec.escape(payload.adminUserId)}")
        appendLine("membershipId=${DurableEnvelopeCodec.escape(payload.membershipId)}")
        appendLine("occurredAt=${payload.occurredAt}")
        appendLine("deviceId=${DurableEnvelopeCodec.escape(payload.deviceId)}")

        appendLine("businessName=${DurableEnvelopeCodec.escape(payload.businessName)}")
        appendLine("branchName=${DurableEnvelopeCodec.escape(payload.branchName)}")
        appendLine("branchCode=${DurableEnvelopeCodec.escape(payload.branchCode)}")
        appendLine("countryCode=${DurableEnvelopeCodec.escape(payload.countryCode)}")
        appendLine("currencyCode=${DurableEnvelopeCodec.escape(payload.currencyCode)}")
        appendLine("currencyDecimalPlaces=${payload.currencyDecimalPlaces}")
        appendLine("currencySymbol=${DurableEnvelopeCodec.escape(payload.currencySymbol)}")
        appendLine("timezoneId=${DurableEnvelopeCodec.escape(payload.timezoneId)}")
        appendLine("taxRoundingMode=${payload.taxRoundingMode.name}")
        appendLine("defaultTaxRateBps=${payload.defaultTaxRateBps}")

        appendLine("adminDisplayName=${DurableEnvelopeCodec.escape(payload.adminDisplayName)}")
        appendLine("adminUsername=${DurableEnvelopeCodec.escape(payload.adminUsername)}")
        payload.adminCanonicalPhone?.let { appendLine("adminCanonicalPhone=${DurableEnvelopeCodec.escape(it)}") }
        appendLine("adminCredentialHash=${DurableEnvelopeCodec.escape(payload.adminCredentialHash)}")
        appendLine("adminCredentialSalt=${DurableEnvelopeCodec.escape(payload.adminCredentialSalt)}")
        appendLine("languageCode=${DurableEnvelopeCodec.escape(payload.languageCode)}")
    }

    fun decode(intentPayload: String): SetupRecoveryPayload {
        val map = parse(intentPayload)

        val version = required(map, "v").toIntOrNull()
            ?: throw IllegalStateException("Invalid Setup payload schema version")

        if (version != CURRENT_SCHEMA_VERSION) {
            throw IllegalStateException("Unsupported Setup payload schema version: $version")
        }

        return SetupRecoveryPayload(
            operationId = required(map, "operationId"),
            businessId = required(map, "businessId"),
            branchId = required(map, "branchId"),
            adminUserId = required(map, "adminUserId"),
            membershipId = required(map, "membershipId"),
            occurredAt = required(map, "occurredAt").toLong(),
            deviceId = required(map, "deviceId"),

            businessName = required(map, "businessName"),
            branchName = required(map, "branchName"),
            branchCode = required(map, "branchCode"),
            countryCode = required(map, "countryCode"),
            currencyCode = required(map, "currencyCode"),
            currencyDecimalPlaces = required(map, "currencyDecimalPlaces").toInt(),
            currencySymbol = required(map, "currencySymbol"),
            timezoneId = required(map, "timezoneId"),
            taxRoundingMode = TaxRoundingMode.valueOf(required(map, "taxRoundingMode")),
            defaultTaxRateBps = required(map, "defaultTaxRateBps").toInt(),

            adminDisplayName = required(map, "adminDisplayName"),
            adminUsername = required(map, "adminUsername"),
            adminCanonicalPhone = map["adminCanonicalPhone"],
            adminCredentialHash = required(map, "adminCredentialHash"),
            adminCredentialSalt = required(map, "adminCredentialSalt"),
            languageCode = required(map, "languageCode")
        )
    }

    fun fingerprint(params: SetupParams): String {
        val values = listOf(
            CURRENT_SCHEMA_VERSION.toString(),
            params.businessName,
            params.branchName,
            params.branchCode,
            params.countryCode,
            params.currencyCode,
            params.currencyDecimalPlaces.toString(),
            params.currencySymbol,
            params.timezoneId,
            params.taxRoundingMode.name,
            params.defaultTaxRateBps.toString(),
            params.adminDisplayName,
            params.adminUsername,
            params.adminPhone.orEmpty(),
            params.languageCode
        )

        val canonical = values.joinToString("|") { value ->
            "${value.length}:$value"
        }

        return CommandFingerprint.sha256(canonical)
    }

    private fun parse(payload: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        payload.lines().forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) {
                val key = line.substring(0, index)
                val value = line.substring(index + 1)
                result[key] = DurableEnvelopeCodec.unescape(value)
            }
        }

        return result
    }

    private fun required(map: Map<String, String>, key: String): String {
        return map[key]
            ?: throw IllegalStateException("Setup recovery payload is missing '$key'")
    }
}