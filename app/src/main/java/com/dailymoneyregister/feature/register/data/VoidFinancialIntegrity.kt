package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver

data class ValidatedVoidFinancialState(
    val reversibleAmountMinor: Long,
    val fundingCategory: PaymentCategory,
    val fundingChannel: PaymentChannel
)

/**
 * Financial integrity gate used by the production Void writer.
 *
 * Responsibilities are deliberately split:
 * - [fingerprint] binds an admitted Void to the exact raw financial history.
 * - [validate] proves that lifecycle events and settlement entries agree
 *   operation-by-operation before any Void reversal can be written.
 *
 * This object does not write financial state.
 */
object VoidFinancialIntegrity {

    fun fingerprint(
        transactionId: String,
        record: TransactionFinancialRecord?,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): String {
        val canonical = buildString {
            token("void-financial-state-v1")
            token(transactionId)

            if (record == null) {
                token("transaction-missing")
            } else {
                token("transaction-present")
                val tx = record.transaction
                token(tx.id)
                token(tx.branchId)
                token(tx.type.name)
                token(tx.customerId)
                token(tx.subtotalMinor)
                token(tx.taxRateBps)
                token(tx.taxableBaseMinor)
                token(tx.taxAmountMinor)
                token(tx.txShippingMinor)
                token(tx.txPackingMinor)
                token(tx.grandTotalMinor)
                token(tx.currencyCode)
                token(tx.currencyDecimalPlaces)
                token(tx.createdByUserId)
                token(tx.deviceId)
                token(tx.createdAt)
                token(tx.description)
                token(tx.displayRef)

                record.amountCorrections
                    .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                    .forEach { correction ->
                        token("correction")
                        token(correction.id)
                        token(correction.transactionId)
                        token(correction.operationId)
                        token(correction.previousAmountMinor)
                        token(correction.correctedAmountMinor)
                        token(correction.actorUserId)
                        token(correction.occurredAt)
                    }

                record.refundEvents
                    .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                    .forEach { refund ->
                        token("refund")
                        token(refund.id)
                        token(refund.transactionId)
                        token(refund.operationId)
                        token(refund.refundAmountMinor)
                        token(refund.actorUserId)
                        token(refund.occurredAt)
                    }

                record.paymentMethodChanges
                    .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                    .forEach { change ->
                        token("payment-change")
                        token(change.id)
                        token(change.transactionId)
                        token(change.operationId)
                        token(change.previousMethod.name)
                        token(change.newMethod.name)
                        token(change.actorUserId)
                        token(change.occurredAt)
                    }

                record.voidEvents
                    .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                    .forEach { void ->
                        token("void")
                        token(void.id)
                        token(void.transactionId)
                        token(void.operationId)
                        token(void.actorUserId)
                        token(void.occurredAt)
                    }

                record.settlements
                    .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                    .forEach { settlement ->
                        token("settlement")
                        token(settlement.id)
                        token(settlement.operationId)
                        token(settlement.subjectType.name)
                        token(settlement.subjectId)
                        token(settlement.transactionId)
                        token(settlement.entryPurpose.name)
                        token(settlement.category.name)
                        token(settlement.channel.name)
                        token(settlement.amountSignedMinor)
                        token(settlement.currencyCode)
                        token(settlement.confirmationType.name)
                        token(settlement.qrProfileIdSnapshot)
                        token(settlement.occurredAt)
                        token(settlement.actorUserId)
                        token(settlement.providerRef)
                    }
            }

            creditAllocations
                .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                .forEach { allocation ->
                    token("credit-allocation")
                    token(allocation.id)
                    token(allocation.repaymentId)
                    token(allocation.originalTransactionId)
                    token(allocation.allocatedAmountMinor)
                    token(allocation.occurredAt)
                }
        }

        return CommandFingerprint.sha256(canonical)
    }

    fun validate(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): ValidatedVoidFinancialState {
        val transaction = record.transaction

        if (record.voidEvents.isNotEmpty()) {
            throw VoidAlreadyExistsException(transaction.id)
        }

        if (creditAllocations.isNotEmpty()) {
            throw VoidCreditRepaymentExistsException(transaction.id)
        }

        if (
            transaction.type == TransactionType.COUPON_PURCHASE ||
            record.settlements.any { it.category == PaymentCategory.COUPON }
        ) {
            throw VoidUnsupportedCouponException(transaction.id)
        }

        record.settlements.forEach { settlement ->
            requireTransactionSettlement(transaction.id, transaction.currencyCode, settlement)
        }

        val saleFundingSettlements = record.settlements.filter {
            it.entryPurpose == EntryPurpose.SALE_FUNDING
        }

        if (
            saleFundingSettlements.isEmpty() ||
            saleFundingSettlements.any { it.amountSignedMinor <= 0L }
        ) {
            throw VoidAmbiguousSettlementException(transaction.id)
        }

        val fundingChannels = saleFundingSettlements
            .map { it.category to it.channel }
            .distinct()

        if (fundingChannels.size != 1) {
            throw VoidAmbiguousSettlementException(transaction.id)
        }

        // The reversal goes to the channel the money is in now, after any switches.
        val fundingChannel = currentFundingIdentity(record, fundingChannels.single())

        if (fundingChannel.first == PaymentCategory.CREDIT) {
            throw VoidUnsupportedCreditException(transaction.id)
        }

        if (fundingChannel.first == PaymentCategory.COUPON) {
            throw VoidUnsupportedCouponException(transaction.id)
        }

        val settlementsByOperation = record.settlements.groupBy { it.operationId }

        val correctionOperationIds = record.amountCorrections.map { it.operationId }
        if (correctionOperationIds.size != correctionOperationIds.distinct().size) {
            throw VoidAmbiguousSettlementException(transaction.id)
        }

        record.amountCorrections.forEach { correction ->
            val matches = settlementsByOperation[correction.operationId].orEmpty()
            if (matches.isEmpty()) {
                throw VoidAmbiguousSettlementException(transaction.id)
            }

            val expectedDelta = try {
                Math.subtractExact(
                    correction.correctedAmountMinor,
                    correction.previousAmountMinor
                )
            } catch (_: ArithmeticException) {
                throw VoidAmbiguousSettlementException(transaction.id)
            }

            if (
                matches.any {
                    it.entryPurpose != EntryPurpose.FUNDING_CORRECTION ||
                        it.category != fundingChannel.first ||
                        it.channel != fundingChannel.second
                } ||
                checkedSettlementSum(
                    transactionId = transaction.id,
                    settlements = matches
                ) != expectedDelta
            ) {
                throw VoidAmbiguousSettlementException(transaction.id)
            }
        }

        val refundOperationIds = record.refundEvents.map { it.operationId }
        if (refundOperationIds.size != refundOperationIds.distinct().size) {
            throw VoidAmbiguousSettlementException(transaction.id)
        }

        record.refundEvents.forEach { refund ->
            val matches = settlementsByOperation[refund.operationId].orEmpty()

            /*
             * A Refund is one immutable lifecycle fact with exactly one
             * corresponding financial outflow. Split rows that merely sum to
             * the same value are ambiguous evidence and must fail closed.
             */
            if (matches.size != 1) {
                throw VoidAmbiguousSettlementException(transaction.id)
            }

            val settlement = matches.single()

            val expectedAmount = try {
                Math.negateExact(refund.refundAmountMinor)
            } catch (_: ArithmeticException) {
                throw VoidAmbiguousSettlementException(transaction.id)
            }

            if (
                settlement.entryPurpose != EntryPurpose.REFUND_OUTFLOW ||
                settlement.amountSignedMinor != expectedAmount ||
                settlement.category != fundingChannel.first ||
                settlement.channel != fundingChannel.second ||
                settlement.actorUserId != refund.actorUserId ||
                settlement.occurredAt != refund.occurredAt
            ) {
                throw VoidAmbiguousSettlementException(transaction.id)
            }
        }

        val correctionOperations = correctionOperationIds.toSet()
        val refundOperations = refundOperationIds.toSet()

        record.settlements.forEach { settlement ->
            when (settlement.entryPurpose) {
                EntryPurpose.SALE_FUNDING -> Unit

                EntryPurpose.FUNDING_CORRECTION -> {
                    if (settlement.operationId !in correctionOperations) {
                        throw VoidAmbiguousSettlementException(transaction.id)
                    }
                }

                EntryPurpose.REFUND_OUTFLOW -> {
                    if (settlement.operationId !in refundOperations) {
                        throw VoidAmbiguousSettlementException(transaction.id)
                    }
                }

                EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION -> {
                    if (record.paymentMethodChanges.isEmpty()) {
                        throw VoidAmbiguousSettlementException(transaction.id)
                    }
                    // The operation-linked pair was validated above.
                }

                EntryPurpose.VOID_REVERSAL,
                EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                EntryPurpose.COUPON_PURCHASE_FUNDING,
                EntryPurpose.EXPENSE_OUTFLOW,
                EntryPurpose.EXPENSE_CORRECTION -> {
                    throw VoidAmbiguousSettlementException(transaction.id)
                }
            }
        }

        val view = FinancialReadModelResolver.resolve(record)
        val reversibleAmount = view.currentAmount - view.refundedAmount

        if (!reversibleAmount.isPositive) {
            throw VoidNothingReversibleException(transaction.id)
        }

        if (view.periodFinancialEffect != reversibleAmount) {
            throw VoidAmbiguousSettlementException(transaction.id)
        }

        return ValidatedVoidFinancialState(
            reversibleAmountMinor = reversibleAmount.amount,
            fundingCategory = fundingChannel.first,
            fundingChannel = fundingChannel.second
        )
    }

    private fun checkedSettlementSum(
        transactionId: String,
        settlements: List<SettlementEntryEntity>
    ): Long {
        return try {
            settlements.fold(0L) { total, settlement ->
                Math.addExact(total, settlement.amountSignedMinor)
            }
        } catch (_: ArithmeticException) {
            throw VoidAmbiguousSettlementException(transactionId)
        }
    }

    private fun currentFundingIdentity(
        record: TransactionFinancialRecord,
        originalChannel: Pair<PaymentCategory, PaymentChannel>
    ): Pair<PaymentCategory, PaymentChannel> {
        var current = originalChannel
        record.paymentMethodChanges
            .sortedBy { it.occurredAt }
            .forEach { change ->
                val nextChannel = change.newChannel
                if (
                    change.previousMethod != current.first ||
                    change.previousChannel != current.second ||
                    nextChannel == null
                ) {
                    throw VoidAmbiguousSettlementException(record.transaction.id)
                }
                current = change.newMethod to nextChannel
            }
        return current
    }

    private fun requireTransactionSettlement(
        transactionId: String,
        currencyCode: String,
        settlement: SettlementEntryEntity
    ) {
        if (
            settlement.subjectType != SubjectType.TRANSACTION ||
            settlement.subjectId != transactionId ||
            settlement.transactionId != transactionId ||
            settlement.currencyCode != currencyCode
        ) {
            throw VoidAmbiguousSettlementException(transactionId)
        }
    }

    private fun StringBuilder.token(value: Any?) {
        val rendered = value?.toString() ?: "<null>"
        append(rendered.length)
            .append(':')
            .append(rendered)
            .append('|')
    }
}
