package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver

data class ValidatedRefundFinancialState(
    val remainingRefundableMinor: Long,
    val fundingCategory: PaymentCategory,
    val fundingChannel: PaymentChannel
)

/**
 * Authoritative financial integrity gate for Refund admission/execution.
 *
 * Refund does not create an independent financial truth.
 * It validates against the same complete transaction lifetime used by
 * the already-hardened Void path.
 *
 * No financial state is written here.
 */
object RefundFinancialIntegrity {

    /**
     * Bind the admitted Refund operation to the exact authoritative
     * transaction lifetime that existed at admission.
     *
     * The additional Refund namespace prevents a Void fingerprint from
     * being mistaken for a Refund fingerprint even though both intentionally
     * cover the same raw financial evidence.
     */
    fun fingerprint(
        transactionId: String,
        record: TransactionFinancialRecord?,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): String {
        val underlyingState =
            VoidFinancialIntegrity.fingerprint(
                transactionId = transactionId,
                record = record,
                creditAllocations = creditAllocations
            )

        return CommandFingerprint.sha256(
            "refund-financial-state-v1|$underlyingState"
        )
    }

    /**
     * Validate the current lifetime before a Refund may be written.
     *
     * Initial safe Refund scope:
     * - CASH single-funding transactions
     * - ONLINE single-funding transactions
     *
     * Fail closed:
     * - already voided transaction
     * - CREDIT
     * - COUPON
     * - mixed/ambiguous funding
     * - payment-method-changed transaction
     * - malformed lifecycle/settlement reconciliation
     * - transaction with credit repayment allocations
     */
    fun validate(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>,
        refundDestination: PaymentCategory? = null
    ): ValidatedRefundFinancialState {

        val transactionId =
            record.transaction.id

        if (record.voidEvents.isNotEmpty()) {
            throw RefundAfterVoidException(
                transactionId
            )
        }

        val validated =
            try {
                VoidFinancialIntegrity.validate(
                    record = record,
                    creditAllocations = creditAllocations
                )
            } catch (_: VoidAlreadyExistsException) {
                throw RefundAfterVoidException(
                    transactionId
                )
            } catch (_: VoidNothingReversibleException) {
                throw RefundNothingRefundableException(
                    transactionId
                )
            } catch (_: VoidCreditRepaymentExistsException) {
                /*
                 * BOARDLOCK Credit Return B2 destination admission.
                 *
                 * Authoritative repayment allocations prove this is no longer
                 * the frozen zero-repayment B1 case. The repository B2 planner
                 * remains responsible for calculating:
                 *
                 *   unpaid Credit reduction
                 *   + actually-paid refundable value.
                 *
                 * The cashier-selected destination applies only to that
                 * actually-paid refundable value.
                 *
                 * This admission does not itself create or redirect financial
                 * evidence.
                 */
                if (
                    refundDestination == PaymentCategory.CREDIT ||
                    refundDestination == PaymentCategory.CASH ||
                    refundDestination == PaymentCategory.ONLINE ||
                    refundDestination == PaymentCategory.COUPON
                ) {
                    return validateCreditFunded(
                        record = record,
                        creditAllocations = creditAllocations
                    )
                }

                throw RefundCreditRepaymentExistsException(transactionId)
        } catch (_: VoidUnsupportedCreditException) {
                if (
                    (
                    refundDestination == PaymentCategory.CREDIT ||
                    refundDestination == PaymentCategory.CASH ||
                    refundDestination == PaymentCategory.ONLINE ||
                    refundDestination == PaymentCategory.COUPON
                )
                ) {
                    return validateCreditFunded(
                        record = record,
                        creditAllocations =
                            creditAllocations
                    )
                }

                throw RefundUnsupportedCreditException(
                    transactionId
                )
            } catch (_: VoidUnsupportedCouponException) {
                if (refundDestination == PaymentCategory.COUPON) return validateCouponFunded(record, creditAllocations)
                throw RefundUnsupportedCouponException(transactionId)
            } catch (_: VoidAmbiguousSettlementException) {
                if (refundDestination == PaymentCategory.COUPON) return validateCouponFunded(record, creditAllocations)
                throw RefundAmbiguousSettlementException(transactionId)
            }

        if (
            validated.fundingCategory != PaymentCategory.CASH &&
            validated.fundingCategory != PaymentCategory.ONLINE
        ) {
            throw RefundAmbiguousSettlementException(
                transactionId
            )
        }

        return ValidatedRefundFinancialState(
            remainingRefundableMinor =
                validated.reversibleAmountMinor,
            fundingCategory =
                validated.fundingCategory,
            fundingChannel =
                validated.fundingChannel
        )
    }

    /**
     * BOARDLOCK Credit Return B1.
     *
     * Only a pure Credit-funded sale with zero repayment allocations
     * is eligible.
     */
    private fun validateCreditFunded(
        record: TransactionFinancialRecord,
        creditAllocations:
            List<CreditRepaymentAllocationEntity>
    ): ValidatedRefundFinancialState {

        val id =
            record.transaction.id

        if (record.voidEvents.isNotEmpty()) {
            throw RefundAfterVoidException(id)
        }

        /*
         * BOARDLOCK Credit Return B1 / B2-B boundary.
         *
         * Zero repayment allocations remain the frozen B1 path.
         *
         * When authoritative repayment allocations exist, this integrity
         * layer only proves that the ORIGINAL transaction is a pure
         * Credit-funded sale. The repository must then reconstruct the
         * exact debt-reduction / paid-value split from:
         *
         *   - Credit OBLIGATION
         *   - repayment records
         *   - repayment allocations
         *   - repayment collection settlements
         *   - prior RefundEvents
         *   - prior Credit Return ADJUSTMENTs
         *
         * No repayment allocation is mutated here.
         */
        if (
            record.paymentMethodChanges.isNotEmpty()
        ) {
            throw RefundAmbiguousSettlementException(
                id
            )
        }

        val funding =
            record.settlements.filter {
                it.entryPurpose ==
                    EntryPurpose.SALE_FUNDING &&
                    it.amountSignedMinor > 0L
            }

        if (
            funding.size != 1 ||
            funding.single().category !=
                PaymentCategory.CREDIT ||
            funding.single().channel !=
                PaymentChannel.CREDIT_ACCOUNT ||
            funding.single().amountSignedMinor !=
                record.transaction.grandTotalMinor
        ) {
            throw RefundUnsupportedCreditException(
                id
            )
        }

        /*
         * Any earlier Refund on this pure Credit sale must itself have
         * been a Credit-account reversal.
         */
        val refundOutflows =
            record.settlements.filter {
                it.entryPurpose ==
                    EntryPurpose.REFUND_OUTFLOW
            }

        /*
         * B1 historical returns contain only CREDIT/CREDIT_ACCOUNT.
         *
         * B2-B may additionally contain paid-value outflows using the
         * authoritative repayment funding identity. Mixed funding is
         * therefore legitimate only for these explicitly supported pairs.
         */
        if (
            refundOutflows.any {
                it.amountSignedMinor >= 0L ||
                    when (it.category) {
                        PaymentCategory.CREDIT ->
                            it.channel !=
                                PaymentChannel.CREDIT_ACCOUNT

                        PaymentCategory.CASH ->
                            it.channel !=
                                PaymentChannel.CASH_TENDER

                        PaymentCategory.ONLINE ->
                            it.channel !=
                                PaymentChannel.MANUAL_QR &&
                                it.channel !=
                                PaymentChannel.SOFTPOS

                        PaymentCategory.COUPON ->
                            it.channel !=
                                PaymentChannel.COUPON_WALLET
                    }
            }
        ) {
            throw RefundAmbiguousSettlementException(
                id
            )
        }

        val view =
            FinancialReadModelResolver.resolve(
                record
            )

        if (!view.netAmount.isPositive) {
            throw RefundNothingRefundableException(
                id
            )
        }

        val refundOutflowAmount =
            refundOutflows.fold(0L) {
                    total,
                    entry ->

                Math.addExact(
                    total,
                    Math.negateExact(
                        entry.amountSignedMinor
                    )
                )
            }

        if (
            refundOutflowAmount !=
                view.refundedAmount.amount
        ) {
            throw RefundAmbiguousSettlementException(
                id
            )
        }

        return ValidatedRefundFinancialState(
            remainingRefundableMinor =
                view.netAmount.amount,
            fundingCategory =
                PaymentCategory.CREDIT,
            fundingChannel =
                PaymentChannel.CREDIT_ACCOUNT
        )
    }
    private fun validateCouponFunded(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): ValidatedRefundFinancialState {
        val id = record.transaction.id
        if (record.voidEvents.isNotEmpty()) throw RefundAfterVoidException(id)
        if (record.paymentMethodChanges.isNotEmpty() || creditAllocations.isNotEmpty()) {
            throw RefundAmbiguousSettlementException(id)
        }
        val funding = record.settlements.filter {
            it.entryPurpose == EntryPurpose.SALE_FUNDING && it.amountSignedMinor > 0
        }
        if (funding.none { it.category == PaymentCategory.COUPON && it.channel == PaymentChannel.COUPON_WALLET } ||
            funding.any { it.category to it.channel !in setOf(
                PaymentCategory.COUPON to PaymentChannel.COUPON_WALLET,
                PaymentCategory.CASH to PaymentChannel.CASH_TENDER,
                PaymentCategory.ONLINE to PaymentChannel.MANUAL_QR,
                PaymentCategory.CREDIT to PaymentChannel.CREDIT_ACCOUNT
            ) } || funding.fold(0L) { total, entry -> Math.addExact(total, entry.amountSignedMinor) } !=
            record.transaction.grandTotalMinor
        ) throw RefundAmbiguousSettlementException(id)

        val view = FinancialReadModelResolver.resolve(record)
        if (!view.netAmount.isPositive) throw RefundNothingRefundableException(id)
        val refundOutflow = record.settlements.filter { it.entryPurpose == EntryPurpose.REFUND_OUTFLOW }
            .fold(0L) { total, entry -> Math.addExact(total, Math.negateExact(entry.amountSignedMinor)) }
        if (refundOutflow != view.refundedAmount.amount) throw RefundAmbiguousSettlementException(id)
        return ValidatedRefundFinancialState(view.netAmount.amount, PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET)
    }
}

