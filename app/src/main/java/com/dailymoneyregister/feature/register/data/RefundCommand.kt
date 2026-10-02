package com.dailymoneyregister.feature.register.data

import android.util.Base64
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

data class RefundFinancialPrecondition(
    val stateFingerprint: String,
    val expectedRemainingRefundableMinor: Long? = null,
    val fundingCategory: PaymentCategory? = null,
    val fundingChannel: PaymentChannel? = null
) {
    init {
        require(stateFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid Refund financial-state fingerprint"
        }

        val expectationParts = listOf(
            expectedRemainingRefundableMinor,
            fundingCategory,
            fundingChannel
        )

        require(
            expectationParts.all { it == null } ||
                expectationParts.none { it == null }
        ) {
            "Refund financial expectation must be complete or absent"
        }

        expectedRemainingRefundableMinor?.let {
            require(it > 0L) {
                "Refund expected remaining amount must be positive"
            }
        }
    }
}

/*
 * BOARDLOCK Refund-to-Coupon destination identity.
 *
 * One canonical phone belongs to one shared Customer ID.
 * The destination is durable Refund intent, not mutable UI state.
 */
data class RefundCouponDestination(
    val customerId: String,
    val customerName: String,
    val canonicalPhone: String,
    val createCustomer: Boolean,
    val additionalUserNames: List<String> = emptyList()
) {
    init {
        require(customerId.isNotBlank()) {
            "Refund Coupon customer ID is required"
        }

        require(
            customerName.isNotBlank() &&
                customerName == customerName.trim()
        ) {
            "Refund Coupon customer name is required"
        }

        require(
            Regex("\\+[1-9][0-9]{6,14}")
                .matches(canonicalPhone)
        ) {
            "Use an international phone number including +country code"
        }

        require(
            additionalUserNames.size <= 20 &&
                additionalUserNames.all {
                    it.isNotBlank() &&
                        it == it.trim() &&
                        it.length <= 200
                }
        ) {
            "Invalid Coupon authorized users"
        }
    }
}

data class DecodedRefundPayload(
    val schemaVersion: Int,
    val refundAmountMinor: Long,
    val destinationCategory: PaymentCategory,
    val destinationChannel: PaymentChannel,
    val reason: String?,
    val financialPrecondition: RefundFinancialPrecondition?,
    val couponDestination: RefundCouponDestination? = null
)

data class RefundCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val subjectId: String,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long,
    val refundAmountMinor: Long,
    val destinationCategory: PaymentCategory,
    val destinationChannel: PaymentChannel,
    val reason: String? = null,
    val financialPrecondition: RefundFinancialPrecondition? = null,
    val couponDestination: RefundCouponDestination? = null
) : OperationCommand {

    init {
        require(refundAmountMinor > 0L) {
            "Refund amount must be positive"
        }

        require(
            destinationCategory == PaymentCategory.CASH ||
                destinationCategory == PaymentCategory.ONLINE ||
                destinationCategory == PaymentCategory.COUPON ||
                destinationCategory == PaymentCategory.CREDIT
        ) {
            "Refund destination must be CASH, ONLINE, COUPON or CREDIT"
        }

        when (destinationCategory) {
            PaymentCategory.CASH -> {
                require(
                    destinationChannel ==
                        PaymentChannel.CASH_TENDER
                ) {
                    "Cash Refund must use CASH_TENDER"
                }

                require(couponDestination == null) {
                    "Cash Refund cannot contain Coupon destination"
                }
            }

            PaymentCategory.ONLINE -> {
                require(
                    destinationChannel ==
                        PaymentChannel.MANUAL_QR ||
                        destinationChannel ==
                        PaymentChannel.SOFTPOS
                ) {
                    "Online Refund must use an online payment channel"
                }

                require(couponDestination == null) {
                    "Online Refund cannot contain Coupon destination"
                }
            }

            PaymentCategory.COUPON -> {
                require(
                    destinationChannel ==
                        PaymentChannel.COUPON_WALLET
                ) {
                    "Coupon Refund must use COUPON_WALLET"
                }
            }

            PaymentCategory.CREDIT -> {
                require(
                    destinationChannel ==
                        PaymentChannel.CREDIT_ACCOUNT
                ) {
                    "Credit Return must use CREDIT_ACCOUNT"
                }

                require(couponDestination == null) {
                    "Credit Return cannot contain Coupon destination"
                }
            }
        }
    }

    override val operationType: OperationType =
        OperationType.RECORD_REFUND

    override val subjectType: SubjectType =
        SubjectType.TRANSACTION

    override val transactionId: String =
        subjectId

    /*
     * Historical Refunds remain schema v1.
     * New Refund-to-Coupon destination commands are schema v2.
     */
    override val commandSchemaVersion: Int =
        if (couponDestination == null) {
            RefundPayloadCodec.LEGACY_SCHEMA_VERSION
        } else {
            RefundPayloadCodec.COUPON_SCHEMA_VERSION
        }

    /*
     * Financial precondition deliberately remains outside the request
     * fingerprint. It is captured during durable admission.
     */
    override val requestFingerprint: String
        get() {
            val destination = couponDestination

            if (destination == null) {
                return CommandFingerprint.sha256(
                    "v1|biz:$businessId|br:$branchId|tx:$subjectId|" +
                        "act:$actorUserId|device:$deviceId|time:$occurredAt|" +
                        "amount:$refundAmountMinor|" +
                        "destCategory:${destinationCategory.name}|" +
                        "destChannel:${destinationChannel.name}|" +
                        "reason:${reason.orEmpty()}"
                )
            }

            return CommandFingerprint.sha256(
                buildString {
                    append("v2")
                    append("|biz:").append(businessId)
                    append("|br:").append(branchId)
                    append("|tx:").append(subjectId)
                    append("|act:").append(actorUserId)
                    append("|device:").append(deviceId)
                    append("|time:").append(occurredAt)
                    append("|amount:").append(refundAmountMinor)
                    append("|destCategory:")
                        .append(destinationCategory.name)
                    append("|destChannel:")
                        .append(destinationChannel.name)
                    append("|reason:")
                        .append(
                            RefundPayloadCodec.encodeTextForFingerprint(
                                reason.orEmpty()
                            )
                        )
                    append("|customerId:")
                        .append(
                            RefundPayloadCodec.encodeTextForFingerprint(
                                destination.customerId
                            )
                        )
                    append("|customerName:")
                        .append(
                            RefundPayloadCodec.encodeTextForFingerprint(
                                destination.customerName
                            )
                        )
                    append("|phone:")
                        .append(
                            RefundPayloadCodec.encodeTextForFingerprint(
                                destination.canonicalPhone
                            )
                        )
                    append("|create:")
                        .append(destination.createCustomer)
                    append("|users:")
                        .append(
                            destination.additionalUserNames
                                .joinToString(",") {
                                    RefundPayloadCodec
                                        .encodeTextForFingerprint(it)
                                }
                        )
                }
            )
        }

    override val intentPayload: String
        get() =
            RefundPayloadCodec.encode(
                refundAmountMinor =
                    refundAmountMinor,
                destinationCategory =
                    destinationCategory,
                destinationChannel =
                    destinationChannel,
                reason =
                    reason,
                financialPrecondition =
                    financialPrecondition,
                couponDestination =
                    couponDestination
            )
}

object RefundPayloadCodec {

    const val LEGACY_SCHEMA_VERSION = 1
    const val COUPON_SCHEMA_VERSION = 2
    const val CURRENT_SCHEMA_VERSION = COUPON_SCHEMA_VERSION

    private const val PREFIX_V1 = "v1"
    private const val PREFIX_V2 = "v2"

    fun encode(
        refundAmountMinor: Long,
        destinationCategory: PaymentCategory,
        destinationChannel: PaymentChannel,
        reason: String?,
        financialPrecondition: RefundFinancialPrecondition?,
        couponDestination: RefundCouponDestination? = null
    ): String {
        val precondition =
            financialPrecondition

        return buildString {
            append(
                if (couponDestination == null) {
                    PREFIX_V1
                } else {
                    PREFIX_V2
                }
            )

            append("|amount:")
                .append(refundAmountMinor)

            append("|category:")
                .append(destinationCategory.name)

            append("|channel:")
                .append(destinationChannel.name)

            append("|reason:")
                .append(encodeText(reason.orEmpty()))

            append("|state:")
                .append(
                    precondition
                        ?.stateFingerprint
                        .orEmpty()
                )

            append("|remaining:")
                .append(
                    precondition
                        ?.expectedRemainingRefundableMinor
                        ?.toString()
                        .orEmpty()
                )

            append("|fundingCategory:")
                .append(
                    precondition
                        ?.fundingCategory
                        ?.name
                        .orEmpty()
                )

            append("|fundingChannel:")
                .append(
                    precondition
                        ?.fundingChannel
                        ?.name
                        .orEmpty()
                )

            couponDestination?.let { destination ->
                append("|couponCustomerId:")
                    .append(
                        encodeText(
                            destination.customerId
                        )
                    )

                append("|couponCustomerName:")
                    .append(
                        encodeText(
                            destination.customerName
                        )
                    )

                append("|couponCanonicalPhone:")
                    .append(
                        encodeText(
                            destination.canonicalPhone
                        )
                    )

                append("|couponCreateCustomer:")
                    .append(
                        destination.createCustomer
                    )

                append("|couponAuthorizedUsers:")
                    .append(
                        destination.additionalUserNames
                            .joinToString(",") {
                                encodeText(it)
                            }
                    )
            }
        }
    }

    fun decode(
        payload: String
    ): DecodedRefundPayload {
        val parts =
            payload.split('|')

        require(
            parts.isNotEmpty() &&
                (
                    parts.first() == PREFIX_V1 ||
                        parts.first() == PREFIX_V2
                    )
        ) {
            "Unsupported Refund payload"
        }

        val schemaVersion =
            if (parts.first() == PREFIX_V2) {
                COUPON_SCHEMA_VERSION
            } else {
                LEGACY_SCHEMA_VERSION
            }

        val values =
            mutableMapOf<String, String>()

        parts
            .drop(1)
            .forEach { part ->
                val separator =
                    part.indexOf(':')

                require(separator > 0) {
                    "Malformed Refund payload"
                }

                val key =
                    part.substring(
                        0,
                        separator
                    )

                require(key !in values) {
                    "Duplicate Refund payload field '$key'"
                }

                values[key] =
                    part.substring(
                        separator + 1
                    )
            }

        val baseKeys =
            setOf(
                "amount",
                "category",
                "channel",
                "reason",
                "state",
                "remaining",
                "fundingCategory",
                "fundingChannel"
            )

        val expectedKeys =
            if (
                schemaVersion ==
                COUPON_SCHEMA_VERSION
            ) {
                baseKeys +
                    setOf(
                        "couponCustomerId",
                        "couponCustomerName",
                        "couponCanonicalPhone",
                        "couponCreateCustomer",
                        "couponAuthorizedUsers"
                    )
            } else {
                baseKeys
            }

        require(
            values.keys ==
                expectedKeys
        ) {
            "Malformed Refund payload fields"
        }

        val amount =
            values
                .getValue("amount")
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid Refund amount"
                )

        require(amount > 0L) {
            "Refund amount must be positive"
        }

        val category =
            enumValueOrNull<PaymentCategory>(
                values.getValue("category")
            ) ?: throw IllegalArgumentException(
                "Invalid Refund destination category"
            )

        val channel =
            enumValueOrNull<PaymentChannel>(
                values.getValue("channel")
            ) ?: throw IllegalArgumentException(
                "Invalid Refund destination channel"
            )

        val stateText =
            values.getValue("state")

        val remainingText =
            values.getValue("remaining")

        val fundingCategoryText =
            values.getValue(
                "fundingCategory"
            )

        val fundingChannelText =
            values.getValue(
                "fundingChannel"
            )

        val preconditionAbsent =
            stateText.isEmpty() &&
                remainingText.isEmpty() &&
                fundingCategoryText.isEmpty() &&
                fundingChannelText.isEmpty()

        val preconditionComplete =
            stateText.isNotEmpty() &&
                remainingText.isNotEmpty() &&
                fundingCategoryText.isNotEmpty() &&
                fundingChannelText.isNotEmpty()

        require(
            preconditionAbsent ||
                preconditionComplete
        ) {
            "Incomplete Refund financial precondition"
        }

        val precondition =
            if (preconditionComplete) {
                RefundFinancialPrecondition(
                    stateFingerprint =
                        stateText,

                    expectedRemainingRefundableMinor =
                        remainingText
                            .toLongOrNull()
                            ?: throw IllegalArgumentException(
                                "Invalid Refund remaining amount"
                            ),

                    fundingCategory =
                        enumValueOrNull<PaymentCategory>(
                            fundingCategoryText
                        ) ?: throw IllegalArgumentException(
                            "Invalid Refund funding category"
                        ),

                    fundingChannel =
                        enumValueOrNull<PaymentChannel>(
                            fundingChannelText
                        ) ?: throw IllegalArgumentException(
                            "Invalid Refund funding channel"
                        )
                )
            } else {
                null
            }

        val couponDestination =
            if (
                schemaVersion ==
                COUPON_SCHEMA_VERSION
            ) {
                val createCustomer =
                    when (
                        values.getValue(
                            "couponCreateCustomer"
                        )
                    ) {
                        "true" -> true
                        "false" -> false

                        else ->
                            throw IllegalArgumentException(
                                "Invalid Refund Coupon create-customer flag"
                            )
                    }

                val usersText =
                    values.getValue(
                        "couponAuthorizedUsers"
                    )

                val users =
                    if (usersText.isBlank()) {
                        emptyList()
                    } else {
                        usersText
                            .split(',')
                            .map {
                                decodeText(it)
                            }
                    }

                RefundCouponDestination(
                    customerId =
                        decodeText(
                            values.getValue(
                                "couponCustomerId"
                            )
                        ),

                    customerName =
                        decodeText(
                            values.getValue(
                                "couponCustomerName"
                            )
                        ),

                    canonicalPhone =
                        decodeText(
                            values.getValue(
                                "couponCanonicalPhone"
                            )
                        ),

                    createCustomer =
                        createCustomer,

                    additionalUserNames =
                        users
                )
            } else {
                null
            }

        if (couponDestination != null) {
            require(
                category ==
                    PaymentCategory.COUPON &&
                    channel ==
                    PaymentChannel.COUPON_WALLET
            ) {
                "Refund Coupon destination requires COUPON_WALLET"
            }
        }

        return DecodedRefundPayload(
            schemaVersion =
                schemaVersion,

            refundAmountMinor =
                amount,

            destinationCategory =
                category,

            destinationChannel =
                channel,

            reason =
                decodeText(
                    values.getValue("reason")
                ).takeIf {
                    it.isNotEmpty()
                },

            financialPrecondition =
                precondition,

            couponDestination =
                couponDestination
        )
    }

    internal fun encodeTextForFingerprint(
        value: String
    ): String =
        encodeText(value)

    private fun encodeText(
        value: String
    ): String =
        Base64.encodeToString(
            value.toByteArray(
                Charsets.UTF_8
            ),
            Base64.NO_WRAP
        )

    private fun decodeText(
        encoded: String
    ): String {
        if (encoded.isEmpty()) {
            return ""
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
                "Invalid Refund text encoding",
                error
            )
        }
    }

    private inline fun <
        reified T : Enum<T>
    > enumValueOrNull(
        value: String
    ): T? =
        enumValues<T>()
            .firstOrNull {
                it.name == value
            }
}
class RefundAfterVoidException(transactionId: String) :
    IllegalStateException(
        "Transaction '$transactionId' is already voided"
    )

class RefundNothingRefundableException(transactionId: String) :
    IllegalStateException(
        "Transaction '$transactionId' has no refundable value"
    )

class RefundCreditRepaymentExistsException(
    transactionId: String
) : IllegalStateException(
    "Transaction '$transactionId' has credit repayment allocations"
)

class RefundUnsupportedCreditException(transactionId: String) :
    IllegalStateException(
        "Credit transaction '$transactionId' cannot be refunded safely"
    )

class RefundUnsupportedCouponException(transactionId: String) :
    IllegalStateException(
        "Coupon-funded transaction '$transactionId' cannot be refunded safely"
    )

class RefundAmbiguousSettlementException(transactionId: String) :
    IllegalStateException(
        "Transaction '$transactionId' has ambiguous settlement funding"
    )

class RefundAmountExceedsRemainingException(
    transactionId: String,
    requestedAmountMinor: Long,
    remainingAmountMinor: Long
) : IllegalStateException(
    "Refund amount $requestedAmountMinor exceeds remaining refundable " +
        "amount $remainingAmountMinor for transaction '$transactionId'"
)

class RefundDestinationMismatchException(
    transactionId: String
) : IllegalStateException(
    "Refund destination does not match authoritative funding for " +
        "transaction '$transactionId'"
)
