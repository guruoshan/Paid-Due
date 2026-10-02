package com.dailymoneyregister.feature.register.data

import android.util.Base64
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

data class PaymentMethodChangeFinancialPrecondition(
    val stateFingerprint: String,
    val expectedCurrentCategory: PaymentCategory,
    val expectedCurrentChannel: PaymentChannel,
    val expectedReclassificationAmountMinor: Long
) {
    init {
        require(stateFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid Payment Method Change financial-state fingerprint"
        }

        require(expectedReclassificationAmountMinor > 0L) {
            "Payment Method Change expected reclassification amount must be positive"
        }

        requireSupportedPaymentIdentity(
            category = expectedCurrentCategory,
            channel = expectedCurrentChannel,
            label = "current"
        )
    }
}

data class DecodedPaymentMethodChangePayload(
    val schemaVersion: Int,
    val newCategory: PaymentCategory,
    val newChannel: PaymentChannel,
    val reason: String?,
    val financialPrecondition: PaymentMethodChangeFinancialPrecondition?
)

data class PaymentMethodChangeCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val subjectId: String,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long,
    val newCategory: PaymentCategory,
    val newChannel: PaymentChannel,
    val reason: String? = null,
    val financialPrecondition: PaymentMethodChangeFinancialPrecondition? = null
) : OperationCommand {

    init {
        requireSupportedPaymentIdentity(
            category = newCategory,
            channel = newChannel,
            label = "destination"
        )

        financialPrecondition?.let { precondition ->
            require(
                precondition.expectedCurrentCategory != newCategory ||
                    precondition.expectedCurrentChannel != newChannel
            ) {
                "Payment Method Change destination is already the current payment identity"
            }
        }
    }

    override val operationType: OperationType =
        OperationType.RECORD_PAYMENT_METHOD_CHANGE

    override val subjectType: SubjectType =
        SubjectType.TRANSACTION

    override val transactionId: String =
        subjectId

    override val commandSchemaVersion: Int =
        PaymentMethodChangePayloadCodec.CURRENT_SCHEMA_VERSION

    /*
     * The fingerprint represents the cashier's requested action only.
     *
     * The authoritative financial-state snapshot is admitted later and persisted
     * in intentPayload. Keeping it out of requestFingerprint allows a retry of
     * the same user action to resolve the already-admitted durable operation.
     */
    override val requestFingerprint: String
        get() = CommandFingerprint.sha256(
            "v1|biz:$businessId|br:$branchId|tx:$subjectId|" +
                "act:$actorUserId|device:$deviceId|time:$occurredAt|" +
                "newCategory:${newCategory.name}|" +
                "newChannel:${newChannel.name}|" +
                "reason:${reason.orEmpty()}"
        )

    override val intentPayload: String
        get() = PaymentMethodChangePayloadCodec.encode(
            newCategory = newCategory,
            newChannel = newChannel,
            reason = reason,
            financialPrecondition = financialPrecondition
        )
}

object PaymentMethodChangePayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    private const val PREFIX = "v1"

    fun encode(
        newCategory: PaymentCategory,
        newChannel: PaymentChannel,
        reason: String?,
        financialPrecondition: PaymentMethodChangeFinancialPrecondition?
    ): String {
        requireSupportedPaymentIdentity(
            category = newCategory,
            channel = newChannel,
            label = "destination"
        )

        val precondition = financialPrecondition

        return buildString {
            append(PREFIX)
            append("|newCategory:").append(newCategory.name)
            append("|newChannel:").append(newChannel.name)
            append("|reason:").append(encodeReason(reason))
            append("|state:")
                .append(precondition?.stateFingerprint.orEmpty())
            append("|currentCategory:")
                .append(precondition?.expectedCurrentCategory?.name.orEmpty())
            append("|currentChannel:")
                .append(precondition?.expectedCurrentChannel?.name.orEmpty())
            append("|amount:")
                .append(
                    precondition
                        ?.expectedReclassificationAmountMinor
                        ?.toString()
                        .orEmpty()
                )
        }
    }

    fun decode(payload: String): DecodedPaymentMethodChangePayload {
        val parts = payload.split('|')

        require(parts.isNotEmpty() && parts.first() == PREFIX) {
            "Unsupported Payment Method Change payload"
        }

        val values = mutableMapOf<String, String>()

        parts.drop(1).forEach { part ->
            val separator = part.indexOf(':')

            require(separator > 0) {
                "Malformed Payment Method Change payload"
            }

            val key = part.substring(0, separator)

            require(key !in values) {
                "Duplicate Payment Method Change payload field '$key'"
            }

            values[key] =
                part.substring(separator + 1)
        }

        val expectedKeys = setOf(
            "newCategory",
            "newChannel",
            "reason",
            "state",
            "currentCategory",
            "currentChannel",
            "amount"
        )

        require(values.keys == expectedKeys) {
            "Malformed Payment Method Change payload fields"
        }

        val newCategory =
            enumValueOrNull<PaymentCategory>(
                values.getValue("newCategory")
            ) ?: throw IllegalArgumentException(
                "Invalid Payment Method Change destination category"
            )

        val newChannel =
            enumValueOrNull<PaymentChannel>(
                values.getValue("newChannel")
            ) ?: throw IllegalArgumentException(
                "Invalid Payment Method Change destination channel"
            )

        requireSupportedPaymentIdentity(
            category = newCategory,
            channel = newChannel,
            label = "destination"
        )

        val stateText =
            values.getValue("state")

        val currentCategoryText =
            values.getValue("currentCategory")

        val currentChannelText =
            values.getValue("currentChannel")

        val amountText =
            values.getValue("amount")

        val preconditionAbsent =
            stateText.isEmpty() &&
                currentCategoryText.isEmpty() &&
                currentChannelText.isEmpty() &&
                amountText.isEmpty()

        val preconditionComplete =
            stateText.isNotEmpty() &&
                currentCategoryText.isNotEmpty() &&
                currentChannelText.isNotEmpty() &&
                amountText.isNotEmpty()

        require(preconditionAbsent || preconditionComplete) {
            "Incomplete Payment Method Change financial precondition"
        }

        val precondition =
            if (preconditionAbsent) {
                null
            } else {
                val currentCategory =
                    enumValueOrNull<PaymentCategory>(
                        currentCategoryText
                    ) ?: throw IllegalArgumentException(
                        "Invalid Payment Method Change current category"
                    )

                val currentChannel =
                    enumValueOrNull<PaymentChannel>(
                        currentChannelText
                    ) ?: throw IllegalArgumentException(
                        "Invalid Payment Method Change current channel"
                    )

                val amount =
                    amountText.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid Payment Method Change reclassification amount"
                        )

                PaymentMethodChangeFinancialPrecondition(
                    stateFingerprint = stateText,
                    expectedCurrentCategory = currentCategory,
                    expectedCurrentChannel = currentChannel,
                    expectedReclassificationAmountMinor = amount
                )
            }

        if (precondition != null) {
            require(
                precondition.expectedCurrentCategory != newCategory ||
                    precondition.expectedCurrentChannel != newChannel
            ) {
                "Payment Method Change destination is already the current payment identity"
            }
        }

        return DecodedPaymentMethodChangePayload(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            newCategory = newCategory,
            newChannel = newChannel,
            reason = decodeReason(values.getValue("reason")),
            financialPrecondition = precondition
        )
    }

    private fun encodeReason(reason: String?): String =
        reason?.toByteArray(Charsets.UTF_8)?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }.orEmpty()

    private fun decodeReason(encoded: String): String? {
        if (encoded.isEmpty()) return null

        return try {
            String(
                Base64.decode(encoded, Base64.DEFAULT),
                Charsets.UTF_8
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid Payment Method Change reason encoding",
                error
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(
        value: String
    ): T? =
        enumValues<T>().firstOrNull {
            it.name == value
        }
}

private fun requireSupportedPaymentIdentity(
    category: PaymentCategory,
    channel: PaymentChannel,
    label: String
) {
    when (category) {
        PaymentCategory.CASH -> {
            require(channel == PaymentChannel.CASH_TENDER) {
                "Payment Method Change $label CASH identity must use CASH_TENDER"
            }
        }

        PaymentCategory.ONLINE -> {
            require(
                channel == PaymentChannel.MANUAL_QR ||
                    channel == PaymentChannel.SOFTPOS
            ) {
                "Payment Method Change $label ONLINE identity must use MANUAL_QR or SOFTPOS"
            }
        }

        PaymentCategory.CREDIT,
        PaymentCategory.COUPON -> {
            throw IllegalArgumentException(
                "Payment Method Change $label identity must be CASH or ONLINE"
            )
        }
    }
}
