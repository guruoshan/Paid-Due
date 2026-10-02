package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

/**
 * One authoritative customer Credit repayment intent.
 *
 * The repayment is NOT sales revenue and does not modify an original sale.
 * It creates new append-only repayment evidence which is allocated FIFO
 * against existing Credit obligations.
 *
 * COUPON is part of the durable command model, but repository execution must
 * atomically debit the authoritative Coupon wallet before it is enabled.
 */
data class CreditRepaymentCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,

    val repaymentId: String,
    val customerId: String,
    val amountMinor: Long,
    val currencyCode: String,

    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,

    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis()
) : OperationCommand {

    init {
        require(repaymentId.isNotBlank()) {
            "Credit repayment ID is required."
        }

        require(customerId.isNotBlank()) {
            "Credit repayment customer is required."
        }

        require(amountMinor > 0L) {
            "Credit repayment amount must be positive."
        }

        require(currencyCode.isNotBlank()) {
            "Credit repayment currency is required."
        }

        when (paymentCategory) {
            PaymentCategory.CASH ->
                require(paymentChannel == PaymentChannel.CASH_TENDER) {
                    "Cash Credit repayment requires CASH_TENDER."
                }

            PaymentCategory.ONLINE ->
                require(
                    paymentChannel == PaymentChannel.MANUAL_QR ||
                        paymentChannel == PaymentChannel.SOFTPOS
                ) {
                    "Online Credit repayment requires an Online payment channel."
                }

            PaymentCategory.COUPON ->
                require(paymentChannel == PaymentChannel.COUPON_WALLET) {
                    "Coupon Credit repayment requires COUPON_WALLET."
                }

            PaymentCategory.CREDIT ->
                error(
                    "Credit cannot be used to repay Credit."
                )
        }
    }

    override val operationType =
        OperationType.RECORD_CREDIT_REPAYMENT

    override val subjectType =
        SubjectType.REPAYMENT

    override val subjectId =
        repaymentId

    override val transactionId: String? =
        null

    override val commandSchemaVersion =
        CreditRepaymentPayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() =
            buildString {
                append("v1")
                append("|type:CREDIT_REPAYMENT")
                append("|biz:").append(businessId)
                append("|br:").append(branchId)
                append("|actor:").append(actorUserId)
                append("|repayment:").append(repaymentId)
                append("|customer:").append(customerId)
                append("|amount:").append(amountMinor)
                append("|currency:").append(currencyCode)
                append("|category:").append(paymentCategory.name)
                append("|channel:").append(paymentChannel.name)
                append("|device:").append(deviceId)
                append("|time:").append(occurredAt)
            }

    override val intentPayload: String
        get() = CreditRepaymentPayloadCodec.encode(this)
}