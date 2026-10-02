package com.dailymoneyregister.feature.register.data

import android.util.Base64
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

data class VoidFinancialPrecondition(
    val stateFingerprint: String,
    val expectedReversalAmountMinor: Long? = null,
    val fundingCategory: PaymentCategory? = null,
    val fundingChannel: PaymentChannel? = null
) {
    init {
        require(stateFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid Void financial-state fingerprint"
        }
        val expectationParts = listOf(
            expectedReversalAmountMinor,
            fundingCategory,
            fundingChannel
        )
        require(expectationParts.all { it == null } || expectationParts.none { it == null }) {
            "Void financial expectation must be complete or absent"
        }
        expectedReversalAmountMinor?.let {
            require(it > 0L) { "Void expected reversal must be positive" }
        }
    }
}

data class DecodedVoidPayload(
    val schemaVersion: Int,
    val reason: String?,
    val financialPrecondition: VoidFinancialPrecondition?
)

data class VoidCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val subjectId: String,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long,
    val reason: String? = null,
    val financialPrecondition: VoidFinancialPrecondition? = null
) : OperationCommand {
    override val operationType: OperationType = OperationType.RECORD_VOID
    override val subjectType: SubjectType = SubjectType.TRANSACTION
    override val transactionId: String = subjectId
    override val commandSchemaVersion: Int =
        if (financialPrecondition == null) {
            VoidPayloadCodec.LEGACY_SCHEMA_VERSION
        } else {
            VoidPayloadCodec.CURRENT_SCHEMA_VERSION
        }

    /*
     * Keep the user-request fingerprint stable across v1 -> v2 admission.
     *
     * The v2 financial precondition is persisted in immutable intentPayload.
     * It is deliberately not part of requestFingerprint so a caller retrying
     * the same user action can resolve the already-admitted durable intent.
     */
    override val requestFingerprint: String
        get() = CommandFingerprint.sha256(
            "v1|biz:$businessId|br:$branchId|tx:$subjectId|act:$actorUserId|" +
                "device:$deviceId|time:$occurredAt|reason:${reason.orEmpty()}"
        )

    override val intentPayload: String
        get() = VoidPayloadCodec.encode(
            reason = reason,
            financialPrecondition = financialPrecondition
        )
}

object VoidPayloadCodec {
    const val LEGACY_SCHEMA_VERSION = 1
    const val CURRENT_SCHEMA_VERSION = 2

    private const val V1_PREFIX = "v1|reason:"
    private const val V2_PREFIX = "v2|"

    fun encode(
        reason: String?,
        financialPrecondition: VoidFinancialPrecondition?
    ): String {
        val encodedReason = encodeReason(reason)
        val precondition = financialPrecondition

        if (precondition == null) {
            return V1_PREFIX + encodedReason
        }

        val amount = precondition.expectedReversalAmountMinor?.toString().orEmpty()
        val category = precondition.fundingCategory?.name.orEmpty()
        val channel = precondition.fundingChannel?.name.orEmpty()

        return buildString {
            append(V2_PREFIX)
            append("reason:").append(encodedReason)
            append("|state:").append(precondition.stateFingerprint)
            append("|amount:").append(amount)
            append("|category:").append(category)
            append("|channel:").append(channel)
        }
    }

    fun decode(payload: String): DecodedVoidPayload {
        if (payload.startsWith(V1_PREFIX)) {
            return DecodedVoidPayload(
                schemaVersion = LEGACY_SCHEMA_VERSION,
                reason = decodeReasonValue(payload.removePrefix(V1_PREFIX)),
                financialPrecondition = null
            )
        }

        require(payload.startsWith(V2_PREFIX)) {
            "Unsupported Void payload"
        }

        val parts = payload.split('|')
        require(parts.size == 6 && parts.first() == "v2") {
            "Malformed Void v2 payload"
        }

        val values = mutableMapOf<String, String>()
        parts.drop(1).forEach { part ->
            val separator = part.indexOf(':')
            require(separator > 0) { "Malformed Void v2 payload" }
            val key = part.substring(0, separator)
            require(key !in values) { "Duplicate Void v2 payload field '$key'" }
            values[key] = part.substring(separator + 1)
        }

        val expectedKeys = setOf("reason", "state", "amount", "category", "channel")
        require(values.keys == expectedKeys) { "Malformed Void v2 payload fields" }

        val amountText = values.getValue("amount")
        val categoryText = values.getValue("category")
        val channelText = values.getValue("channel")

        val expectationAbsent =
            amountText.isEmpty() && categoryText.isEmpty() && channelText.isEmpty()
        val expectationComplete =
            amountText.isNotEmpty() && categoryText.isNotEmpty() && channelText.isNotEmpty()

        require(expectationAbsent || expectationComplete) {
            "Incomplete Void v2 financial expectation"
        }

        val precondition = VoidFinancialPrecondition(
            stateFingerprint = values.getValue("state"),
            expectedReversalAmountMinor =
                if (expectationComplete) {
                    amountText.toLongOrNull()
                        ?: throw IllegalArgumentException("Invalid Void reversal amount")
                } else {
                    null
                },
            fundingCategory =
                if (expectationComplete) {
                    enumValueOrNull<PaymentCategory>(categoryText)
                        ?: throw IllegalArgumentException("Invalid Void funding category")
                } else {
                    null
                },
            fundingChannel =
                if (expectationComplete) {
                    enumValueOrNull<PaymentChannel>(channelText)
                        ?: throw IllegalArgumentException("Invalid Void funding channel")
                } else {
                    null
                }
        )

        return DecodedVoidPayload(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            reason = decodeReasonValue(values.getValue("reason")),
            financialPrecondition = precondition
        )
    }

    fun decodeReason(payload: String): String? =
        decode(payload).reason

    private fun encodeReason(reason: String?): String =
        reason?.toByteArray(Charsets.UTF_8)?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }.orEmpty()

    private fun decodeReasonValue(encoded: String): String? {
        if (encoded.isEmpty()) return null
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Void reason encoding", error)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}

class VoidAlreadyExistsException(transactionId: String) :
    IllegalStateException("Transaction '$transactionId' is already voided")

class VoidNothingReversibleException(transactionId: String) :
    IllegalStateException("Transaction '$transactionId' has no reversible value")

class VoidCreditRepaymentExistsException(transactionId: String) :
    IllegalStateException("Transaction '$transactionId' has credit repayment allocations")

class VoidUnsupportedCreditException(transactionId: String) :
    IllegalStateException("Credit transaction '$transactionId' cannot be voided safely")

class VoidUnsupportedCouponException(transactionId: String) :
    IllegalStateException("Coupon-funded transaction '$transactionId' cannot be voided")

class VoidAmbiguousSettlementException(transactionId: String) :
    IllegalStateException("Transaction '$transactionId' has ambiguous settlement funding")
