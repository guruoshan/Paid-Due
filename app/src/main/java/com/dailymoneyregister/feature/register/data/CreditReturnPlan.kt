package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel

/**
 * BOARDLOCK Credit Return B2-A.
 *
 * Pure planning only.
 *
 * This file performs no database writes and creates no independent
 * financial balance. All inputs are immutable snapshots of authoritative
 * persisted evidence that the B2-B repository layer will load.
 *
 * Fundamental rule:
 *
 * return value
 *     -> extinguish unpaid Credit attributable to this sale first
 *     -> only excess may return value actually paid by the customer
 */
data class CreditReturnRepaymentEvidence(
    val repaymentId: String,
    val customerId: String,
    val currencyCode: String,

    /**
     * Total authoritative repayment amount.
     */
    val repaymentAmountMinor: Long,

    /**
     * Total amount of this repayment allocated across ALL Credit sales.
     *
     * B2-B must derive this from authoritative allocation rows.
     */
    val totalAllocatedAcrossCreditMinor: Long,

    /**
     * Amount of this repayment allocated specifically to the Credit sale
     * being returned.
     */
    val allocatedToTransactionMinor: Long,

    /**
     * Authoritative repayment collection funding identity.
     */
    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,

    val repaymentOccurredAt: Long
)

data class CreditReturnPaidValuePortion(
    val repaymentId: String,
    val amountMinor: Long,
    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,
    val repaymentOccurredAt: Long
)

data class CreditReturnPlan(
    val originalTransactionId: String,
    val customerId: String,
    val currencyCode: String,

    val originalCreditAmountMinor: Long,

    /**
     * Historical return value already recorded against this sale.
     */
    val alreadyReturnedAmountMinor: Long,

    /**
     * Historical Credit-account reduction already caused by earlier
     * returns against this sale.
     */
    val priorCreditReductionMinor: Long,

    /**
     * Historical value already returned from customer-paid repayment value.
     */
    val priorPaidValueRefundMinor: Long,

    val allocatedRepaymentAmountMinor: Long,

    /**
     * Credit still unpaid for THIS original transaction immediately
     * before this requested return.
     */
    val unpaidCreditBeforeReturnMinor: Long,

    val remainingReturnableAmountMinor: Long,
    val requestedReturnAmountMinor: Long,

    /**
     * Amount of this return that extinguishes Credit.
     */
    val creditReductionMinor: Long,

    /**
     * Amount of this return that gives back value actually paid.
     */
    val paidValueRefundMinor: Long,

    /**
     * Deterministically ordered funding portions for paid-value refund.
     *
     * B2-A plans these only. It does not write refund settlements.
     */
    val paidValuePortions: List<CreditReturnPaidValuePortion>
)

class CreditReturnPlanException(
    message: String
) : IllegalStateException(message)

/**
 * Pure deterministic planner for B2.
 *
 * IMPORTANT:
 * This object does NOT replace the existing B1 Refund writer.
 * B1 remains the production execution path for zero-repayment Credit returns.
 */
object CreditReturnPlanner {

    fun plan(
        originalTransactionId: String,
        customerId: String,
        currencyCode: String,
        originalCreditAmountMinor: Long,
        requestedReturnAmountMinor: Long,

        /**
         * Total authoritative RefundEvent value already recorded against
         * this original transaction.
         */
        alreadyReturnedAmountMinor: Long,

        /**
         * Historical negative Credit ADJUSTMENT value attributable to
         * returns against this original transaction, expressed positively.
         */
        priorCreditReductionMinor: Long,

        /**
         * Historical customer-paid value already returned, expressed
         * positively.
         *
         * B2-B will derive this from historical return evidence.
         */
        priorPaidValueRefundMinor: Long,

        repayments: List<CreditReturnRepaymentEvidence>
    ): CreditReturnPlan {

        requireNonBlank(
            originalTransactionId,
            "Original Credit transaction ID"
        )

        requireNonBlank(
            customerId,
            "Credit customer ID"
        )

        requireNonBlank(
            currencyCode,
            "Credit currency"
        )

        if (originalCreditAmountMinor <= 0L) {
            fail("Original Credit amount must be positive.")
        }

        if (requestedReturnAmountMinor <= 0L) {
            fail("Requested Credit return amount must be positive.")
        }

        if (alreadyReturnedAmountMinor < 0L) {
            fail("Historical returned amount cannot be negative.")
        }

        if (priorCreditReductionMinor < 0L) {
            fail("Historical Credit reduction cannot be negative.")
        }

        if (priorPaidValueRefundMinor < 0L) {
            fail("Historical paid-value refund cannot be negative.")
        }

        val reconstructedHistoricalReturn =
            addExact(
                priorCreditReductionMinor,
                priorPaidValueRefundMinor,
                "Historical Credit return arithmetic overflow."
            )

        if (
            reconstructedHistoricalReturn !=
                alreadyReturnedAmountMinor
        ) {
            fail(
                "Historical Credit return evidence does not reconcile."
            )
        }

        if (
            alreadyReturnedAmountMinor >
                originalCreditAmountMinor
        ) {
            fail(
                "Historical Credit returns exceed original Credit amount."
            )
        }

        validateRepayments(
            customerId = customerId,
            currencyCode = currencyCode,
            repayments = repayments
        )

        val allocatedRepaymentAmount =
            repayments.fold(0L) { total, repayment ->
                addExact(
                    total,
                    repayment.allocatedToTransactionMinor,
                    "Allocated repayment arithmetic overflow."
                )
            }

        /*
         * A repayment can only pay value that remained after historical
         * Credit-return reductions.
         */
        val obligationAfterPriorCreditReturns =
            subtractExact(
                originalCreditAmountMinor,
                priorCreditReductionMinor,
                "Historical Credit reduction exceeds original Credit amount."
            )

        if (
            allocatedRepaymentAmount >
                obligationAfterPriorCreditReturns
        ) {
            fail(
                "Repayment allocations exceed the remaining original " +
                    "Credit obligation."
            )
        }

        /*
         * Transaction-specific unpaid Credit.
         *
         * Never use the customer's overall Credit balance here.
         */
        val unpaidCreditBeforeReturn =
            subtractExact(
                obligationAfterPriorCreditReturns,
                allocatedRepaymentAmount,
                "Unable to derive transaction-specific unpaid Credit."
            )

        val remainingReturnable =
            subtractExact(
                originalCreditAmountMinor,
                alreadyReturnedAmountMinor,
                "Historical returns exceed original Credit amount."
            )

        if (remainingReturnable <= 0L) {
            fail("Nothing remains returnable for this Credit sale.")
        }

        if (
            requestedReturnAmountMinor >
                remainingReturnable
        ) {
            fail(
                "Requested Credit return exceeds remaining returnable amount."
            )
        }

        val creditReduction =
            minOf(
                requestedReturnAmountMinor,
                unpaidCreditBeforeReturn
            )

        val paidValueRefund =
            subtractExact(
                requestedReturnAmountMinor,
                creditReduction,
                "Unable to derive paid-value return portion."
            )

        /*
         * Amount of transaction-specific repayment value still available
         * to refund after prior paid-value returns.
         */
        val availablePaidValue =
            subtractExact(
                allocatedRepaymentAmount,
                priorPaidValueRefundMinor,
                "Historical paid-value refund exceeds allocated repayments."
            )

        if (paidValueRefund > availablePaidValue) {
            fail(
                "Requested return would refund value the customer did not pay."
            )
        }

        val orderedRepayments =
            repayments.sortedWith(
                compareBy<CreditReturnRepaymentEvidence>(
                    { it.repaymentOccurredAt },
                    { it.repaymentId }
                )
            )

        /*
         * Historical paid-value refunds are reconstructed against the same
         * deterministic repayment order. This prevents the same repayment
         * value from being consumed twice without introducing mutable state.
         */
        val remainingAfterHistoricalConsumption =
            consumeHistoricalPaidValue(
                orderedRepayments,
                priorPaidValueRefundMinor
            )

        val portions =
            allocatePaidValueRefund(
                remainingAfterHistoricalConsumption,
                paidValueRefund
            )

        val plannedPaidTotal =
            portions.fold(0L) { total, portion ->
                addExact(
                    total,
                    portion.amountMinor,
                    "Paid-value portion arithmetic overflow."
                )
            }

        if (plannedPaidTotal != paidValueRefund) {
            fail(
                "Paid-value funding evidence cannot satisfy return plan."
            )
        }

        return CreditReturnPlan(
            originalTransactionId =
                originalTransactionId,

            customerId =
                customerId,

            currencyCode =
                currencyCode,

            originalCreditAmountMinor =
                originalCreditAmountMinor,

            alreadyReturnedAmountMinor =
                alreadyReturnedAmountMinor,

            priorCreditReductionMinor =
                priorCreditReductionMinor,

            priorPaidValueRefundMinor =
                priorPaidValueRefundMinor,

            allocatedRepaymentAmountMinor =
                allocatedRepaymentAmount,

            unpaidCreditBeforeReturnMinor =
                unpaidCreditBeforeReturn,

            remainingReturnableAmountMinor =
                remainingReturnable,

            requestedReturnAmountMinor =
                requestedReturnAmountMinor,

            creditReductionMinor =
                creditReduction,

            paidValueRefundMinor =
                paidValueRefund,

            paidValuePortions =
                portions
        )
    }

    private data class RemainingRepayment(
        val evidence: CreditReturnRepaymentEvidence,
        val remainingMinor: Long
    )

    private fun validateRepayments(
        customerId: String,
        currencyCode: String,
        repayments: List<CreditReturnRepaymentEvidence>
    ) {
        val ids =
            mutableSetOf<String>()

        repayments.forEach { repayment ->

            requireNonBlank(
                repayment.repaymentId,
                "Credit repayment ID"
            )

            if (!ids.add(repayment.repaymentId)) {
                fail(
                    "Duplicate Credit repayment evidence: " +
                        repayment.repaymentId
                )
            }

            if (repayment.customerId != customerId) {
                fail(
                    "Credit repayment customer does not match original sale."
                )
            }

            if (
                !repayment.currencyCode.equals(
                    currencyCode,
                    ignoreCase = true
                )
            ) {
                fail(
                    "Credit repayment currency does not match original sale."
                )
            }

            if (repayment.repaymentAmountMinor <= 0L) {
                fail(
                    "Credit repayment amount must be positive."
                )
            }

            if (
                repayment.totalAllocatedAcrossCreditMinor <= 0L ||
                repayment.totalAllocatedAcrossCreditMinor >
                    repayment.repaymentAmountMinor
            ) {
                fail(
                    "Credit repayment allocation evidence does not " +
                        "reconcile with repayment amount."
                )
            }

            if (
                repayment.allocatedToTransactionMinor <= 0L ||
                repayment.allocatedToTransactionMinor >
                    repayment.totalAllocatedAcrossCreditMinor
            ) {
                fail(
                    "Transaction repayment allocation is invalid."
                )
            }

            validateFundingIdentity(repayment)
        }
    }

    private fun validateFundingIdentity(
        repayment: CreditReturnRepaymentEvidence
    ) {
        val valid =
            when (repayment.paymentCategory) {
                PaymentCategory.CASH ->
                    repayment.paymentChannel ==
                        PaymentChannel.CASH_TENDER

                PaymentCategory.ONLINE ->
                    repayment.paymentChannel ==
                        PaymentChannel.MANUAL_QR ||
                        repayment.paymentChannel ==
                            PaymentChannel.SOFTPOS

                PaymentCategory.COUPON ->
                    repayment.paymentChannel ==
                        PaymentChannel.COUPON_WALLET

                PaymentCategory.CREDIT ->
                    false
            }

        if (!valid) {
            fail(
                "Unsupported or ambiguous Credit repayment funding evidence."
            )
        }
    }

    private fun consumeHistoricalPaidValue(
        repayments: List<CreditReturnRepaymentEvidence>,
        historicalPaidValueMinor: Long
    ): List<RemainingRepayment> {

        var remainingHistorical =
            historicalPaidValueMinor

        val result =
            mutableListOf<RemainingRepayment>()

        repayments.forEach { repayment ->

            val consumed =
                minOf(
                    remainingHistorical,
                    repayment.allocatedToTransactionMinor
                )

            val remaining =
                subtractExact(
                    repayment.allocatedToTransactionMinor,
                    consumed,
                    "Historical repayment consumption overflow."
                )

            remainingHistorical =
                subtractExact(
                    remainingHistorical,
                    consumed,
                    "Historical paid-value consumption overflow."
                )

            result +=
                RemainingRepayment(
                    evidence = repayment,
                    remainingMinor = remaining
                )
        }

        if (remainingHistorical != 0L) {
            fail(
                "Historical paid-value refund cannot be reconciled " +
                    "to repayment allocations."
            )
        }

        return result
    }

    private fun allocatePaidValueRefund(
        repayments: List<RemainingRepayment>,
        requestedMinor: Long
    ): List<CreditReturnPaidValuePortion> {

        if (requestedMinor == 0L) {
            return emptyList()
        }

        var remaining =
            requestedMinor

        val result =
            mutableListOf<CreditReturnPaidValuePortion>()

        repayments.forEach { item ->

            if (remaining == 0L) {
                return@forEach
            }

            if (item.remainingMinor <= 0L) {
                return@forEach
            }

            val amount =
                minOf(
                    remaining,
                    item.remainingMinor
                )

            result +=
                CreditReturnPaidValuePortion(
                    repaymentId =
                        item.evidence.repaymentId,

                    amountMinor =
                        amount,

                    paymentCategory =
                        item.evidence.paymentCategory,

                    paymentChannel =
                        item.evidence.paymentChannel,

                    repaymentOccurredAt =
                        item.evidence.repaymentOccurredAt
                )

            remaining =
                subtractExact(
                    remaining,
                    amount,
                    "Paid-value allocation overflow."
                )
        }

        if (remaining != 0L) {
            fail(
                "Insufficient authoritative repayment funding for " +
                    "paid-value return."
            )
        }

        return result
    }

    private fun requireNonBlank(
        value: String,
        description: String
    ) {
        if (value.isBlank()) {
            fail("$description is required.")
        }
    }

    private fun addExact(
        first: Long,
        second: Long,
        message: String
    ): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            fail(message)
        }

    private fun subtractExact(
        first: Long,
        second: Long,
        message: String
    ): Long =
        try {
            Math.subtractExact(first, second)
        } catch (_: ArithmeticException) {
            fail(message)
        }

    private fun fail(
        message: String
    ): Nothing =
        throw CreditReturnPlanException(message)
}