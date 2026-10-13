package com.dailymoneyregister.feature.admin.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.serialization.DurableEnvelopeCodec

data class CreateCashierRecoveryPayload(
    val operationId: String,
    val businessId: String,
    val branchId: String,
    val adminUserId: String,
    val cashierUserId: String,
    val membershipId: String,
    val occurredAt: Long,
    val deviceId: String,

    val displayName: String,
    val username: String,
    val canonicalPhone: String? = null,
    val credentialHash: String,
    val credentialSalt: String,
    val preferredLanguage: String,
    val permissions: CashierPermissionsConfig
)

object CreateCashierPayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(payload: CreateCashierRecoveryPayload): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("operationId=${DurableEnvelopeCodec.escape(payload.operationId)}")
        appendLine("businessId=${DurableEnvelopeCodec.escape(payload.businessId)}")
        appendLine("branchId=${DurableEnvelopeCodec.escape(payload.branchId)}")
        appendLine("adminUserId=${DurableEnvelopeCodec.escape(payload.adminUserId)}")
        appendLine("cashierUserId=${DurableEnvelopeCodec.escape(payload.cashierUserId)}")
        appendLine("membershipId=${DurableEnvelopeCodec.escape(payload.membershipId)}")
        appendLine("occurredAt=${payload.occurredAt}")
        appendLine("deviceId=${DurableEnvelopeCodec.escape(payload.deviceId)}")

        appendLine("displayName=${DurableEnvelopeCodec.escape(payload.displayName)}")
        appendLine("username=${DurableEnvelopeCodec.escape(payload.username)}")
        payload.canonicalPhone?.let { appendLine("canonicalPhone=${DurableEnvelopeCodec.escape(it)}") }
        appendLine("credentialHash=${DurableEnvelopeCodec.escape(payload.credentialHash)}")
        appendLine("credentialSalt=${DurableEnvelopeCodec.escape(payload.credentialSalt)}")
        appendLine("preferredLanguage=${DurableEnvelopeCodec.escape(payload.preferredLanguage)}")

        appendLine("canCreateSale=${payload.permissions.canCreateSale}")
        appendLine("canAcceptCash=${payload.permissions.canAcceptCash}")
        appendLine("canAcceptOnline=${payload.permissions.canAcceptOnline}")
        appendLine("canCreateCreditSale=${payload.permissions.canCreateCreditSale}")
        appendLine("canUseCoupon=${payload.permissions.canUseCoupon}")
        appendLine("canIssueInvoice=${payload.permissions.canIssueInvoice}")
        appendLine("canIssueReceipt=${payload.permissions.canIssueReceipt}")
        appendLine("canRefund=${payload.permissions.canRefund}")
        appendLine("canVoid=${payload.permissions.canVoid}")
        appendLine("canCorrectAmount=${payload.permissions.canCorrectAmount}")
        appendLine("canChangePaymentMethod=${payload.permissions.canChangePaymentMethod}")
        appendLine("canViewBroadHistory=${payload.permissions.canViewBroadHistory}")
        appendLine("canAccessCustomerDirectory=${payload.permissions.canAccessCustomerDirectory}")
        appendLine("canManageProducts=${payload.permissions.canManageProducts}")
        appendLine("canViewReports=${payload.permissions.canViewReports}")
    }

    fun decode(intentPayload: String): CreateCashierRecoveryPayload {
        val map = parse(intentPayload)

        val version = required(map, "v").toIntOrNull()
            ?: throw IllegalStateException("Invalid Cashier payload schema version")

        if (version != CURRENT_SCHEMA_VERSION) {
            throw IllegalStateException("Unsupported Cashier payload schema version: $version")
        }

        return CreateCashierRecoveryPayload(
            operationId = required(map, "operationId"),
            businessId = required(map, "businessId"),
            branchId = required(map, "branchId"),
            adminUserId = required(map, "adminUserId"),
            cashierUserId = required(map, "cashierUserId"),
            membershipId = required(map, "membershipId"),
            occurredAt = required(map, "occurredAt").toLong(),
            deviceId = required(map, "deviceId"),

            displayName = required(map, "displayName"),
            username = required(map, "username"),
            canonicalPhone = map["canonicalPhone"],
            credentialHash = required(map, "credentialHash"),
            credentialSalt = required(map, "credentialSalt"),
            preferredLanguage = required(map, "preferredLanguage"),

            permissions = CashierPermissionsConfig(
                canCreateSale = required(map, "canCreateSale").toBooleanStrict(),
                canAcceptCash = required(map, "canAcceptCash").toBooleanStrict(),
                canAcceptOnline = required(map, "canAcceptOnline").toBooleanStrict(),
                canCreateCreditSale = required(map, "canCreateCreditSale").toBooleanStrict(),
                canUseCoupon = required(map, "canUseCoupon").toBooleanStrict(),
                canIssueInvoice = required(map, "canIssueInvoice").toBooleanStrict(),
                canIssueReceipt = required(map, "canIssueReceipt").toBooleanStrict(),
                canRefund = required(map, "canRefund").toBooleanStrict(),
                canVoid = required(map, "canVoid").toBooleanStrict(),
                canCorrectAmount = required(map, "canCorrectAmount").toBooleanStrict(),
                canChangePaymentMethod = required(map, "canChangePaymentMethod").toBooleanStrict(),
                canViewBroadHistory = required(map, "canViewBroadHistory").toBooleanStrict(),
                canAccessCustomerDirectory = map["canAccessCustomerDirectory"]?.toBooleanStrictOrNull() ?: false,
                canManageProducts = required(map, "canManageProducts").toBooleanStrict(),
                canViewReports = required(map, "canViewReports").toBooleanStrict()
            )
        )
    }

    fun fingerprint(params: CreateCashierParams): String {
        val p = params.permissions

        val values = listOf(
            CURRENT_SCHEMA_VERSION.toString(),
            params.businessId,
            params.branchId,
            params.adminUserId,
            params.displayName,
            params.username,
            params.phone.orEmpty(),
            params.preferredLanguage,
            p.canCreateSale.toString(),
            p.canAcceptCash.toString(),
            p.canAcceptOnline.toString(),
            p.canCreateCreditSale.toString(),
            p.canUseCoupon.toString(),
            p.canIssueInvoice.toString(),
            p.canIssueReceipt.toString(),
            p.canRefund.toString(),
            p.canVoid.toString(),
            p.canCorrectAmount.toString(),
            p.canChangePaymentMethod.toString(),
            p.canViewBroadHistory.toString(),
            p.canManageProducts.toString(),
            p.canViewReports.toString()
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
                result[line.substring(0, index)] =
                    DurableEnvelopeCodec.unescape(line.substring(index + 1))
            }
        }

        return result
    }

    private fun required(map: Map<String, String>, key: String): String =
        map[key] ?: throw IllegalStateException(
            "Cashier recovery payload is missing '$key'"
        )
}