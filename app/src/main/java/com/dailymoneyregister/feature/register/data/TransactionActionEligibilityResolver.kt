package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver

/**
 * Read-only adapter from authoritative financial-integrity validators to
 * Transaction Details presentation eligibility.
 *
 * BOARDLOCK:
 * - defines no independent financial rules;
 * - writes no financial state;
 * - does not replace command-time validation;
 * - fails closed when an authoritative validator rejects the current lifetime.
 */
internal object TransactionActionEligibilityResolver {

    fun resolve(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): TransactionActionEligibility =
        TransactionActionEligibility(
            canCorrectAmount =
                canCorrectAmount(
                    record = record,
                    creditAllocations = creditAllocations
                ),
            canRefund =
                canRefund(
                    record = record,
                    creditAllocations = creditAllocations
                ),
            canVoid =
                canVoid(
                    record = record,
                    creditAllocations = creditAllocations
                ),
            canChangePaymentMethod =
                canChangePaymentMethod(
                    record = record,
                    creditAllocations = creditAllocations
                ),
            voidBlockReason =
                voidBlockReason(
                    record = record,
                    creditAllocations = creditAllocations
                )
        )

    private fun canCorrectAmount(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): Boolean =
        try {
            val currentAmountMinor =
                FinancialReadModelResolver
                    .resolve(record)
                    .currentAmount
                    .amount

            val probeAmountMinor =
                if (currentAmountMinor < Long.MAX_VALUE) {
                    currentAmountMinor + 1L
                } else {
                    currentAmountMinor - 1L
                }

            AmountCorrectionFinancialIntegrity.validate(
                record = record,
                creditAllocations = creditAllocations,
                correctedAmountMinor = probeAmountMinor
            )

            true
        } catch (_: RuntimeException) {
            false
        }

    private fun canRefund(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): Boolean =
        enumValues<PaymentCategory>()
            .any { destination ->
                try {
                    RefundFinancialIntegrity.validate(
                        record = record,
                        creditAllocations = creditAllocations,
                        refundDestination = destination
                    )

                    true
                } catch (_: RuntimeException) {
                    false
                }
            }

    private fun voidBlockReason(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): VoidBlockReason? =
        try {
            VoidFinancialIntegrity.validate(
                record = record,
                creditAllocations = creditAllocations
            )

            null
        } catch (_: VoidUnsupportedCreditException) {
            VoidBlockReason.CREDIT_SALE
        } catch (_: VoidCreditRepaymentExistsException) {
            VoidBlockReason.CREDIT_SALE
        } catch (_: VoidUnsupportedCouponException) {
            if (record.transaction.type == TransactionType.SALE) {
                VoidBlockReason.COUPON_SALE
            } else {
                null
            }
        } catch (_: RuntimeException) {
            null
        }

    private fun canVoid(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): Boolean =
        try {
            VoidFinancialIntegrity.validate(
                record = record,
                creditAllocations = creditAllocations
            )

            true
        } catch (_: RuntimeException) {
            false
        }

    private fun canChangePaymentMethod(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): Boolean =
        try {
            PaymentMethodChangeFinancialIntegrity.validate(
                record = record,
                creditAllocations = creditAllocations
            )

            true
        } catch (_: RuntimeException) {
            false
        }
}
