package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

/**
 * BOARDLOCK Credit Phase 1C-C.
 *
 * One authoritative Credit repayment funded by an existing Coupon wallet.
 *
 * This command is intentionally separate from CreditRepaymentCommand so the
 * already-released Cash/Online durable command schema remains unchanged.
 */
data class CouponCreditRepaymentCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,

    val repaymentId: String,
    val customerId: String,
    val amountMinor: Long,
    val currencyCode: String,

    val walletId: String,
    val authorizedUserId: String,
    val authorizedUserName: String,

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

        require(walletId.isNotBlank()) {
            "Coupon wallet is required."
        }

        require(authorizedUserId.isNotBlank()) {
            "Coupon authorized user is required."
        }

        require(authorizedUserName.isNotBlank()) {
            "Coupon authorized user name is required."
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
        CouponCreditRepaymentPayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() =
            buildString {
                append("v1")
                append("|type:COUPON_CREDIT_REPAYMENT")
                append("|biz:").append(businessId)
                append("|br:").append(branchId)
                append("|actor:").append(actorUserId)
                append("|repayment:").append(repaymentId)
                append("|customer:").append(customerId)
                append("|amount:").append(amountMinor)
                append("|currency:").append(currencyCode)
                append("|wallet:").append(walletId)
                append("|authorizedUser:").append(authorizedUserId)
                append("|authorizedName:").append(authorizedUserName)
                append("|device:").append(deviceId)
                append("|time:").append(occurredAt)
            }

    override val intentPayload: String
        get() =
            CouponCreditRepaymentPayloadCodec.encode(this)

    /**
     * Reuses the already-verified 1C-B repayment evidence constructors.
     *
     * This object is NOT submitted to CommandExecutor. The authoritative
     * parent operation remains CouponCreditRepaymentCommand.
     */
    fun repaymentEvidenceCommand(): CreditRepaymentCommand =
        CreditRepaymentCommand(
            operationId = operationId,
            businessId = businessId,
            branchId = branchId,
            actorUserId = actorUserId,
            repaymentId = repaymentId,
            customerId = customerId,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            paymentCategory = PaymentCategory.COUPON,
            paymentChannel = PaymentChannel.COUPON_WALLET,
            deviceId = deviceId,
            occurredAt = occurredAt
        )
}
