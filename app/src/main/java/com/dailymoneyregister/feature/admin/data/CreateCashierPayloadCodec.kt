package com.dailymoneyregister.feature.admin.data

import com.dailymoneyregister.core.command.CommandFingerprint

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
        appendLine("operationId=${escape(payload.operationId)}")
        appendLine("businessId=${escape(payload.businessId)}")
        appendLine("branchId=${escape(payload.branchId)}")
        appendLine("adminUserId=${escape(payload.adminUserId)}")
        appendLine("cashierUserId=${escape(payload.cashierUserId)}")
        appendLine("membershipId=${escape(payload.membershipId)}")
        appendLine("occurredAt=${payload.occurredAt}")
        appendLine("deviceId=${escape(payload.deviceId)}")

        appendLine("displayName=${escape(payload.displayName)}")
        appendLine("username=${escape(payload.username)}")
        payload.canonicalPhone?.let { appendLine("canonicalPhone=${escape(it)}") }
        appendLine("credentialHash=${escape(payload.credentialHash)}")
        appendLine("credentialSalt=${escape(payload.credentialSalt)}")
        appendLine("preferredLanguage=${escape(payload.preferredLanguage)}")

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
                    unescape(line.substring(index + 1))
            }
        }

        return result
    }

    private fun required(map: Map<String, String>, key: String): String =
        map[key] ?: throw IllegalStateException(
            "Cashier recovery payload is missing '$key'"
        )

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun unescape(value: String): String {
        val result = StringBuilder()
        var index = 0

        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> {
                        result.append('\n')
                        index += 2
                        continue
                    }
                    'r' -> {
                        result.append('\r')
                        index += 2
                        continue
                    }
                    '\\' -> {
                        result.append('\\')
                        index += 2
                        continue
                    }
                }
            }

            result.append(value[index])
            index++
        }

        return result.toString()
    }
}
