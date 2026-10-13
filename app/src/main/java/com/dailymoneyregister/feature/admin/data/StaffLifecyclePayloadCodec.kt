package com.dailymoneyregister.feature.admin.data

import com.dailymoneyregister.core.serialization.DurableEnvelopeCodec

/**
 * Durable intent saved on the Operation row for every staff lifecycle command
 * (edit details, edit permissions, disable, enable, admin PIN reset).
 *
 * A PIN is never stored in clear text. For PIN reset only the salted hash is kept.
 */
data class StaffLifecyclePayload(
    val kind: String,
    val operationId: String,
    val businessId: String,
    val branchId: String,
    val adminUserId: String,
    val cashierUserId: String,
    val occurredAt: Long,
    val deviceId: String,

    val displayName: String? = null,
    val username: String? = null,
    val canonicalPhone: String? = null,
    val preferredLanguage: String? = null,
    val permissions: CashierPermissionsConfig? = null,
    val credentialHash: String? = null,
    val credentialSalt: String? = null
)

object StaffLifecyclePayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    private fun esc(value: String): String = DurableEnvelopeCodec.escape(value)

    private fun CashierPermissionsConfig.asOrderedPairs(): List<Pair<String, Boolean>> = listOf(
        "canCreateSale" to canCreateSale,
        "canAcceptCash" to canAcceptCash,
        "canAcceptOnline" to canAcceptOnline,
        "canCreateCreditSale" to canCreateCreditSale,
        "canUseCoupon" to canUseCoupon,
        "canIssueInvoice" to canIssueInvoice,
        "canIssueReceipt" to canIssueReceipt,
        "canRefund" to canRefund,
        "canVoid" to canVoid,
        "canCorrectAmount" to canCorrectAmount,
        "canChangePaymentMethod" to canChangePaymentMethod,
        "canViewBroadHistory" to canViewBroadHistory,
        "canAccessCustomerDirectory" to canAccessCustomerDirectory,
        "canManageProducts" to canManageProducts,
        "canViewReports" to canViewReports
    )

    fun encode(payload: StaffLifecyclePayload): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("kind=${esc(payload.kind)}")
        appendLine("operationId=${esc(payload.operationId)}")
        appendLine("businessId=${esc(payload.businessId)}")
        appendLine("branchId=${esc(payload.branchId)}")
        appendLine("adminUserId=${esc(payload.adminUserId)}")
        appendLine("cashierUserId=${esc(payload.cashierUserId)}")
        appendLine("occurredAt=${payload.occurredAt}")
        appendLine("deviceId=${esc(payload.deviceId)}")

        payload.displayName?.let { appendLine("displayName=${esc(it)}") }
        payload.username?.let { appendLine("username=${esc(it)}") }
        payload.canonicalPhone?.let { appendLine("canonicalPhone=${esc(it)}") }
        payload.preferredLanguage?.let { appendLine("preferredLanguage=${esc(it)}") }
        payload.credentialHash?.let { appendLine("credentialHash=${esc(it)}") }
        payload.credentialSalt?.let { appendLine("credentialSalt=${esc(it)}") }
        payload.permissions?.asOrderedPairs()?.forEach { (key, value) ->
            appendLine("$key=$value")
        }
    }

    fun decode(intentPayload: String): StaffLifecyclePayload {
        val map = parse(intentPayload)

        val version = required(map, "v").toIntOrNull()
            ?: throw IllegalStateException("Invalid staff payload schema version")

        if (version != CURRENT_SCHEMA_VERSION) {
            throw IllegalStateException("Unsupported staff payload schema version: $version")
        }

        val permissions = if (map["canCreateSale"] == null) {
            null
        } else {
            CashierPermissionsConfig(
                canCreateSale = flag(map, "canCreateSale"),
                canAcceptCash = flag(map, "canAcceptCash"),
                canAcceptOnline = flag(map, "canAcceptOnline"),
                canCreateCreditSale = flag(map, "canCreateCreditSale"),
                canUseCoupon = flag(map, "canUseCoupon"),
                canIssueInvoice = flag(map, "canIssueInvoice"),
                canIssueReceipt = flag(map, "canIssueReceipt"),
                canRefund = flag(map, "canRefund"),
                canVoid = flag(map, "canVoid"),
                canCorrectAmount = flag(map, "canCorrectAmount"),
                canChangePaymentMethod = flag(map, "canChangePaymentMethod"),
                canViewBroadHistory = flag(map, "canViewBroadHistory"),
                canAccessCustomerDirectory = map["canAccessCustomerDirectory"]?.toBooleanStrictOrNull() ?: false,
                canManageProducts = flag(map, "canManageProducts"),
                canViewReports = flag(map, "canViewReports")
            )
        }

        return StaffLifecyclePayload(
            kind = required(map, "kind"),
            operationId = required(map, "operationId"),
            businessId = required(map, "businessId"),
            branchId = required(map, "branchId"),
            adminUserId = required(map, "adminUserId"),
            cashierUserId = required(map, "cashierUserId"),
            occurredAt = required(map, "occurredAt").toLong(),
            deviceId = required(map, "deviceId"),
            displayName = map["displayName"],
            username = map["username"],
            canonicalPhone = map["canonicalPhone"],
            preferredLanguage = map["preferredLanguage"],
            permissions = permissions,
            credentialHash = map["credentialHash"],
            credentialSalt = map["credentialSalt"]
        )
    }

    private fun parse(payload: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        payload.lines().forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) {
                result[line.substring(0, index)] =
                    DurableEnvelopeCodec.unescape(line.substring(index + 1))
            }
        }
        return result
    }

    private fun required(map: Map<String, String>, key: String): String =
        map[key] ?: throw IllegalStateException("Staff payload is missing '$key'")

    private fun flag(map: Map<String, String>, key: String): Boolean =
        required(map, key).toBooleanStrict()
}