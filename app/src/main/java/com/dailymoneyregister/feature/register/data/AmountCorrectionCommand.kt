package com.dailymoneyregister.feature.register.data

import android.util.Base64
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

data class AmountCorrectionFinancialPrecondition(
    val stateFingerprint: String,
    val expectedPreviousAmountMinor: Long,
    val expectedRefundedAmountMinor: Long,
    val expectedCurrentCategory: PaymentCategory,
    val expectedCurrentChannel: PaymentChannel,
    val expectedDeltaMinor: Long
) {
    init {
        require(stateFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid Amount Correction financial-state fingerprint"
        }

        require(expectedPreviousAmountMinor >= 0L) {
            "Amount Correction previous amount cannot be negative"
        }

        require(expectedRefundedAmountMinor >= 0L) {
            "Amount Correction refunded amount cannot be negative"
        }

        require(expectedDeltaMinor != 0L) {
            "Amount Correction delta cannot be zero"
        }

        requireSupportedAmountCorrectionIdentity(
            category = expectedCurrentCategory,
            channel = expectedCurrentChannel
        )
    }
}

data class DecodedAmountCorrectionPayload(
    val schemaVersion: Int,
    val correctedAmountMinor: Long,
    val reason: String?,
    val financialPrecondition: AmountCorrectionFinancialPrecondition?
)

data class AmountCorrectionCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val subjectId: String,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long,
    val correctedAmountMinor: Long,
    val reason: String? = null,
    val financialPrecondition: AmountCorrectionFinancialPrecondition? = null
) : OperationCommand {

    init {
        require(correctedAmountMinor >= 0L) {
            "Corrected amount cannot be negative"
        }

        financialPrecondition?.let { precondition ->

            val expectedCorrected =
                Math.addExact(
                    precondition.expectedPreviousAmountMinor,
                    precondition.expectedDeltaMinor
                )

            require(expectedCorrected == correctedAmountMinor) {
                "Amount Correction delta does not reach requested corrected amount"
            }

            require(
                correctedAmountMinor >=
                    precondition.expectedRefundedAmountMinor
            ) {
                "Corrected amount cannot fall below already-refunded value"
            }
        }
    }

    override val operationType =
        OperationType.RECORD_AMOUNT_CORRECTION

    override val subjectType =
        SubjectType.TRANSACTION

    override val transactionId =
        subjectId

    override val commandSchemaVersion =
        AmountCorrectionPayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() =
            CommandFingerprint.sha256(
                "v1|biz:$businessId|" +
                    "br:$branchId|" +
                    "tx:$subjectId|" +
                    "act:$actorUserId|" +
                    "device:$deviceId|" +
                    "time:$occurredAt|" +
                    "corrected:$correctedAmountMinor|" +
                    "reason:${reason.orEmpty()}"
            )

    override val intentPayload: String
        get() =
            AmountCorrectionPayloadCodec.encode(
                correctedAmountMinor = correctedAmountMinor,
                reason = reason,
                financialPrecondition = financialPrecondition
            )
}

object AmountCorrectionPayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1
    private const val PREFIX = "v1"

    fun encode(
        correctedAmountMinor: Long,
        reason: String?,
        financialPrecondition: AmountCorrectionFinancialPrecondition?
    ): String {

        val precondition =
            financialPrecondition

        return buildString {
            append(PREFIX)
            append("|corrected:")
                .append(correctedAmountMinor)

            append("|reason:")
                .append(encodeReason(reason))

            append("|state:")
                .append(precondition?.stateFingerprint.orEmpty())

            append("|previous:")
                .append(
                    precondition
                        ?.expectedPreviousAmountMinor
                        ?.toString()
                        .orEmpty()
                )

            append("|refunded:")
                .append(
                    precondition
                        ?.expectedRefundedAmountMinor
                        ?.toString()
                        .orEmpty()
                )

            append("|category:")
                .append(
                    precondition
                        ?.expectedCurrentCategory
                        ?.name
                        .orEmpty()
                )

            append("|channel:")
                .append(
                    precondition
                        ?.expectedCurrentChannel
                        ?.name
                        .orEmpty()
                )

            append("|delta:")
                .append(
                    precondition
                        ?.expectedDeltaMinor
                        ?.toString()
                        .orEmpty()
                )
        }
    }

    fun decode(
        payload: String
    ): DecodedAmountCorrectionPayload {

        val parts =
            payload.split('|')

        require(
            parts.size == 9 &&
                parts.first() == PREFIX
        ) {
            "Invalid Amount Correction durable payload"
        }

        val values =
            mutableMapOf<String, String>()

        for (part in parts.drop(1)) {

            val separator =
                part.indexOf(':')

            require(separator > 0) {
                "Invalid Amount Correction payload field"
            }

            val key =
                part.substring(0, separator)

            val value =
                part.substring(separator + 1)

            require(key !in values) {
                "Duplicate Amount Correction payload field '$key'"
            }

            values[key] =
                value
        }

        val expectedKeys =
            setOf(
                "corrected",
                "reason",
                "state",
                "previous",
                "refunded",
                "category",
                "channel",
                "delta"
            )

        require(values.keys == expectedKeys) {
            "Amount Correction payload fields do not match schema"
        }

        val correctedAmount =
            values
                .getValue("corrected")
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid Amount Correction corrected amount"
                )

        require(correctedAmount >= 0L) {
            "Corrected amount cannot be negative"
        }

        val state =
            values.getValue("state")

        val previousText =
            values.getValue("previous")

        val refundedText =
            values.getValue("refunded")

        val categoryText =
            values.getValue("category")

        val channelText =
            values.getValue("channel")

        val deltaText =
            values.getValue("delta")

        val precondition =
            if (state.isBlank()) {

                require(
                    previousText.isBlank() &&
                        refundedText.isBlank() &&
                        categoryText.isBlank() &&
                        channelText.isBlank() &&
                        deltaText.isBlank()
                ) {
                    "Partial Amount Correction financial precondition"
                }

                null

            } else {

                val previous =
                    previousText.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid Amount Correction previous amount"
                        )

                val refunded =
                    refundedText.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid Amount Correction refunded amount"
                        )

                val delta =
                    deltaText.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid Amount Correction delta"
                        )

                val category =
                    runCatching {
                        PaymentCategory.valueOf(categoryText)
                    }.getOrElse {
                        throw IllegalArgumentException(
                            "Invalid Amount Correction payment category"
                        )
                    }

                val channel =
                    runCatching {
                        PaymentChannel.valueOf(channelText)
                    }.getOrElse {
                        throw IllegalArgumentException(
                            "Invalid Amount Correction payment channel"
                        )
                    }

                AmountCorrectionFinancialPrecondition(
                    stateFingerprint = state,
                    expectedPreviousAmountMinor = previous,
                    expectedRefundedAmountMinor = refunded,
                    expectedCurrentCategory = category,
                    expectedCurrentChannel = channel,
                    expectedDeltaMinor = delta
                )
            }

        return DecodedAmountCorrectionPayload(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            correctedAmountMinor = correctedAmount,
            reason =
                decodeReason(
                    values.getValue("reason")
                ),
            financialPrecondition = precondition
        )
    }

    private fun encodeReason(
        reason: String?
    ): String =
        reason
            ?.toByteArray(Charsets.UTF_8)
            ?.let {
                Base64.encodeToString(
                    it,
                    Base64.NO_WRAP
                )
            }
            .orEmpty()

    private fun decodeReason(
        encoded: String
    ): String? {

        if (encoded.isEmpty()) {
            return null
        }

        return try {
            String(
                Base64.decode(
                    encoded,
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )
        } catch (
            error: IllegalArgumentException
        ) {
            throw IllegalArgumentException(
                "Invalid Amount Correction reason encoding",
                error
            )
        }
    }
}

internal fun requireSupportedAmountCorrectionIdentity(
    category: PaymentCategory,
    channel: PaymentChannel
) {

    when (category) {

        PaymentCategory.CASH -> {
            require(
                channel ==
                    PaymentChannel.CASH_TENDER
            ) {
                "Cash Amount Correction must use CASH_TENDER"
            }
        }

        PaymentCategory.ONLINE -> {
            require(
                channel ==
                    PaymentChannel.MANUAL_QR ||
                    channel ==
                    PaymentChannel.SOFTPOS
            ) {
                "Online Amount Correction must use an approved online channel"
            }
        }

        PaymentCategory.CREDIT -> {
            throw IllegalArgumentException(
                "Credit Amount Correction requires separately approved credit-obligation semantics"
            )
        }

        PaymentCategory.COUPON -> {
            throw IllegalArgumentException(
                "Coupon Amount Correction requires separately approved coupon-wallet semantics"
            )
        }
    }
}