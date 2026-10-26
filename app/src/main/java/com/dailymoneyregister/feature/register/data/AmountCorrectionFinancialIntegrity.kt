package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver

data class ValidatedAmountCorrectionFinancialState(
    val previousAmountMinor: Long,
    val refundedAmountMinor: Long,
    val currentCategory: PaymentCategory,
    val currentChannel: PaymentChannel,
    val deltaMinor: Long
)

class AmountCorrectionAfterVoidException(
    transactionId: String
) : IllegalStateException(
    "Voided transaction '$transactionId' cannot be amount-corrected"
)

class AmountCorrectionFullyRefundedException(
    transactionId: String
) : IllegalStateException(
    "Fully refunded transaction '$transactionId' cannot be amount-corrected"
)

class AmountCorrectionCreditRepaymentExistsException(
    transactionId: String
) : IllegalStateException(
    "Transaction '$transactionId' has credit repayments and cannot be amount-corrected safely"
)

class AmountCorrectionUnsupportedFundingException(
    transactionId: String
) : IllegalStateException(
    "Transaction '$transactionId' does not have one supported Cash/Online funding identity"
)

class AmountCorrectionBelowRefundedAmountException(
    transactionId: String
) : IllegalStateException(
    "Corrected amount for transaction '$transactionId' cannot be below already-refunded value"
)

class AmountCorrectionBelowCouponAmountException(
    transactionId: String
) : IllegalStateException(
    "Corrected amount for transaction '$transactionId' cannot be below the coupon amount used"
)

class AmountCorrectionNoOpException(
    transactionId: String
) : IllegalStateException(
    "Transaction '$transactionId' already has the requested amount"
)

class AmountCorrectionChronologyException(
    transactionId: String,
    occurredAt: Long,
    latestActivityAt: Long
) : IllegalStateException(
    "Amount Correction for transaction '$transactionId' has occurredAt=$occurredAt " +
        "which is not strictly later than latest financial activity at $latestActivityAt"
)

object AmountCorrectionFinancialIntegrity {

    fun fingerprint(
        transactionId: String,
        record: TransactionFinancialRecord?,
        creditAllocations:
            List<CreditRepaymentAllocationEntity>
    ): String {

        val underlyingState =
            PaymentMethodChangeFinancialIntegrity
                .fingerprint(
                    transactionId = transactionId,
                    record = record,
                    creditAllocations = creditAllocations
                )

        return CommandFingerprint.sha256(
            "amount-correction-financial-state-v1|$underlyingState"
        )
    }

    fun requireChronologicalAppend(
        record: TransactionFinancialRecord,
        occurredAt: Long
    ) {

        val view =
            FinancialReadModelResolver.resolve(
                record
            )

        if (
            occurredAt <=
                view.latestActivityAt
        ) {
            throw AmountCorrectionChronologyException(
                transactionId =
                    record.transaction.id,
                occurredAt =
                    occurredAt,
                latestActivityAt =
                    view.latestActivityAt
            )
        }
    }

    fun validate(
        record: TransactionFinancialRecord,
        creditAllocations:
            List<CreditRepaymentAllocationEntity>,
        correctedAmountMinor: Long
    ): ValidatedAmountCorrectionFinancialState {

        val transaction =
            record.transaction

        require(
            transaction.type ==
                TransactionType.SALE
        ) {
            "Only SALE transactions may be amount-corrected"
        }

        require(correctedAmountMinor >= 0L) {
            "Corrected amount cannot be negative"
        }

        if (creditAllocations.isNotEmpty()) {
            throw AmountCorrectionCreditRepaymentExistsException(
                transaction.id
            )
        }

        val view =
            FinancialReadModelResolver.resolve(
                record
            )

        if (view.isVoided) {
            throw AmountCorrectionAfterVoidException(
                transaction.id
            )
        }

        if (
            view.refundedAmount.amount > 0L &&
            view.refundedAmount.amount ==
                view.currentAmount.amount
        ) {
            throw AmountCorrectionFullyRefundedException(
                transaction.id
            )
        }

        if (
            correctedAmountMinor <
                view.refundedAmount.amount
        ) {
            throw AmountCorrectionBelowRefundedAmountException(
                transaction.id
            )
        }

                // A coupon sale with a remainder has exactly two funding parts.
        // The correction applies to the remainder only; the coupon wallet
        // is never touched.
        val couponPart =
            view.fundingBreakdown.singleOrNull {
                it.category == PaymentCategory.COUPON
            }

        val remainderPart =
            if (couponPart != null && view.fundingBreakdown.size == 2) {
                view.fundingBreakdown.first { it !== couponPart }
            } else {
                null
            }

        val category =
            remainderPart?.category
                ?: view.currentPaymentCategory
                ?: throw AmountCorrectionUnsupportedFundingException(
                    transaction.id
                )

        val channel =
            remainderPart?.channel
                ?: view.currentPaymentChannel
                ?: throw AmountCorrectionUnsupportedFundingException(
                    transaction.id
                )

        try {
            requireSupportedAmountCorrectionIdentity(
                category = category,
                channel = channel
            )
        } catch (
            error: IllegalArgumentException
        ) {
            throw AmountCorrectionUnsupportedFundingException(
                transaction.id
            )
        }

        if (
            couponPart != null &&
            remainderPart != null &&
            correctedAmountMinor < couponPart.amountMinor
        ) {
            throw AmountCorrectionBelowCouponAmountException(
                transaction.id
            )
        }

        val delta =
            Math.subtractExact(
                correctedAmountMinor,
                view.currentAmount.amount
            )

        if (delta == 0L) {
            throw AmountCorrectionNoOpException(
                transaction.id
            )
        }

        return ValidatedAmountCorrectionFinancialState(
            previousAmountMinor =
                view.currentAmount.amount,
            refundedAmountMinor =
                view.refundedAmount.amount,
            currentCategory =
                category,
            currentChannel =
                channel,
            deltaMinor =
                delta
        )
    }
}