package com.dailymoneyregister.core.model.financial

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.money.MoneyMinorUnits

/**
 * Current financial meaning of an immutable transaction history.
 *
 * Screens and reports should consume this model instead of re-calculating
 * lifecycle state independently.
 */
data class FinancialTransactionView(
    val transaction: TransactionEntity,
    val originalAmount: MoneyMinorUnits,
    val currentAmount: MoneyMinorUnits,
    val refundedAmount: MoneyMinorUnits,
    val netAmount: MoneyMinorUnits,
    val settlementTotal: MoneyMinorUnits,
    val isVoided: Boolean,
    val hasAmountCorrection: Boolean,
    val originalPaymentCategory: PaymentCategory?,
    val currentPaymentCategory: PaymentCategory?,
    val latestActivityAt: Long
) {
    val id: String get() = transaction.id
    val displayRef: String get() = transaction.displayRef
    val createdAt: Long get() = transaction.createdAt
    val description: String? get() = transaction.description
    val isPartiallyRefunded: Boolean
        get() = refundedAmount.isPositive && refundedAmount < currentAmount
    val isFullyRefunded: Boolean
        get() = refundedAmount.isPositive && refundedAmount == currentAmount
}

object FinancialReadModelResolver {

    fun resolve(
        record: TransactionFinancialRecord
    ): FinancialTransactionView {

        val transaction = record.transaction

        val corrections = record.amountCorrections.sortedWith(
            compareBy<AmountCorrectionEventEntity> { it.occurredAt }
                .thenBy { it.id }
        )

        var currentAmount =
            MoneyMinorUnits(transaction.grandTotalMinor)

        corrections.forEach { correction ->

            if (correction.previousAmountMinor != currentAmount.amount) {
                throw IllegalStateException(
                    "Broken amount-correction chain for transaction ${transaction.id}"
                )
            }

            val corrected =
                MoneyMinorUnits(correction.correctedAmountMinor)

            if (corrected.isNegative) {
                throw IllegalStateException(
                    "Negative corrected amount for transaction ${transaction.id}"
                )
            }

            currentAmount = corrected
        }

        val refunds = record.refundEvents.sortedWith(
            compareBy<RefundEventEntity> { it.occurredAt }
                .thenBy { it.id }
        )

        val refundedAmount =
            refunds.fold(MoneyMinorUnits.ZERO) { total, refund ->

                val amount =
                    MoneyMinorUnits(refund.refundAmountMinor)

                if (!amount.isPositive) {
                    throw IllegalStateException(
                        "Invalid refund amount for transaction ${transaction.id}"
                    )
                }

                total + amount
            }

        if (refundedAmount > currentAmount) {
            throw IllegalStateException(
                "Refunds exceed current transaction amount for ${transaction.id}"
            )
        }

        val amountAfterRefunds =
            currentAmount - refundedAmount

        val isVoided =
            record.voidEvents.isNotEmpty()

        val netAmount =
            if (isVoided) {
                MoneyMinorUnits.ZERO
            } else {
                amountAfterRefunds
            }

        val settlementTotal =
            record.settlements.fold(MoneyMinorUnits.ZERO) { total, settlement ->
                total + MoneyMinorUnits(settlement.amountSignedMinor)
            }

        val paymentChanges =
            record.paymentMethodChanges.sortedWith(
                compareBy<PaymentMethodChangeEventEntity> { it.occurredAt }
                    .thenBy { it.id }
            )

        val firstPositiveSettlement =
            record.settlements
                .filter { it.amountSignedMinor > 0L }
                .minWithOrNull(
                    compareBy<SettlementEntryEntity> { it.occurredAt }
                        .thenBy { it.id }
                )

        val originalPaymentCategory =
            firstPositiveSettlement?.category
                ?: paymentChanges.firstOrNull()?.previousMethod

        var currentPaymentCategory =
            originalPaymentCategory

        paymentChanges.forEach { change ->

            if (
                currentPaymentCategory != null &&
                change.previousMethod != currentPaymentCategory
            ) {
                throw IllegalStateException(
                    "Broken payment-method chain for transaction ${transaction.id}"
                )
            }

            currentPaymentCategory =
                change.newMethod
        }

        var latestActivityAt =
            transaction.createdAt

        corrections.forEach {
            latestActivityAt =
                maxOf(latestActivityAt, it.occurredAt)
        }

        paymentChanges.forEach {
            latestActivityAt =
                maxOf(latestActivityAt, it.occurredAt)
        }

        record.voidEvents.forEach {
            latestActivityAt =
                maxOf(latestActivityAt, it.occurredAt)
        }

        refunds.forEach {
            latestActivityAt =
                maxOf(latestActivityAt, it.occurredAt)
        }

        record.settlements.forEach {
            latestActivityAt =
                maxOf(latestActivityAt, it.occurredAt)
        }

        return FinancialTransactionView(
            transaction = transaction,
            originalAmount =
                MoneyMinorUnits(transaction.grandTotalMinor),
            currentAmount = currentAmount,
            refundedAmount = refundedAmount,
            netAmount = netAmount,
            settlementTotal = settlementTotal,
            isVoided = isVoided,
            hasAmountCorrection = record.amountCorrections.isNotEmpty(),
            originalPaymentCategory = originalPaymentCategory,
            currentPaymentCategory = currentPaymentCategory,
            latestActivityAt = latestActivityAt
        )
    }
}
