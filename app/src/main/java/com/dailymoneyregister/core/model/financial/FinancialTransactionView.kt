package com.dailymoneyregister.core.model.financial

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
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
    val originalPaymentChannel: PaymentChannel?,
    val currentPaymentCategory: PaymentCategory?,
    val currentPaymentChannel: PaymentChannel?,
    val latestActivityAt: Long,
    /** Signed financial impact whose authoritative activity occurred in the requested period. */
    val periodFinancialEffect: MoneyMinorUnits = netAmount,
    /** Lifecycle meaning resolved from the authoritative transaction record. */
    val lifecycleStates: Set<FinancialLifecycleState> = emptySet(),
    val fundingBreakdown: List<FinancialFundingPart> = emptyList()
) {
    val id: String get() = transaction.id
    val displayRef: String get() = transaction.displayRef
    val createdAt: Long get() = transaction.createdAt
    val description: String? get() = transaction.description
    val isPartiallyRefunded: Boolean
        get() = refundedAmount.isPositive && refundedAmount < currentAmount
    val isFullyRefunded: Boolean
        get() = refundedAmount.isPositive && refundedAmount == currentAmount

    /** Stable transaction classification shared by non-UI read projections. */
    val financialKind: FinancialTransactionKind
        get() = when (transaction.type) {
            TransactionType.SALE -> FinancialTransactionKind.MERCHANDISE_SALE
            TransactionType.COUPON_PURCHASE ->
                if (transaction.description == "Coupon Top Up") {
                    FinancialTransactionKind.COUPON_TOP_UP
                } else {
                    FinancialTransactionKind.COUPON_SALE
                }
        }
}

enum class FinancialTransactionKind {
    MERCHANDISE_SALE,
    COUPON_SALE,
    COUPON_TOP_UP
}

enum class FinancialLifecycleState {
    CORRECTED,
    PAYMENT_METHOD_CHANGED,
    PARTIALLY_REFUNDED,
    FULLY_REFUNDED,
    VOIDED
}

data class FinancialFundingPart(val category: PaymentCategory, val channel: PaymentChannel, val amountMinor: Long)

object FinancialReadModelResolver {

    fun resolve(
        record: TransactionFinancialRecord,
        periodStartMillis: Long? = null,
        periodEndMillis: Long? = null
    ): FinancialTransactionView {

        val transaction = record.transaction
        val periodStart = periodStartMillis ?: Long.MIN_VALUE
        val periodEnd = periodEndMillis ?: Long.MAX_VALUE
        require(periodStart < periodEnd) { "Invalid financial activity period" }

        record.settlements.forEach { settlement ->
            if (settlement.currencyCode != transaction.currencyCode) {
                throw IllegalStateException(
                    "Currency mismatch for transaction ${transaction.id}"
                )
            }
        }

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

        /*
         * Payment identity comes from authoritative SALE_FUNDING, never from an
         * unrelated positive settlement such as a repayment collection. Multiple
         * sale-funding rows are acceptable only when they represent the same
         * category + channel identity; mixed funding is intentionally fail-closed
         * until the read model grows an explicit split-tender representation.
         */
        val fundingPurpose = if (transaction.type == TransactionType.COUPON_PURCHASE)
            EntryPurpose.COUPON_PURCHASE_FUNDING else EntryPurpose.SALE_FUNDING
        val saleFundingIdentities =
            record.settlements
                .asSequence()
                .filter {
                    it.entryPurpose == fundingPurpose &&
                        it.amountSignedMinor > 0L
                }
                .map { it.category to it.channel }
                .distinct()
                .toList()

        // Start from the original sale-funding evidence, then apply each
        // persisted payment-method reclassification as a signed delta. Reporting
        // must reflect the current tender snapshot without counting a reclass as
        // another sale or discarding its immutable audit evidence.
        val fundingAmountsByIdentity = linkedMapOf<Pair<PaymentCategory, PaymentChannel>, Long>()
        record.settlements
            .filter {
                it.entryPurpose == fundingPurpose && it.amountSignedMinor > 0L
            }
            .forEach { settlement ->
                val identity = settlement.category to settlement.channel
                fundingAmountsByIdentity[identity] = Math.addExact(
                    fundingAmountsByIdentity[identity] ?: 0L,
                    settlement.amountSignedMinor
                )
            }

        val reclassifications = record.settlements.filter {
            it.entryPurpose == EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION
        }
        reclassifications.groupBy { it.operationId }.forEach { (operationId, entries) ->
            val operationDelta = entries.fold(0L) { total, entry ->
                Math.addExact(total, entry.amountSignedMinor)
            }
            check(operationDelta == 0L) {
                "Payment-method reclassification $operationId is not balanced for transaction ${transaction.id}."
            }
        }

        reclassifications
            .groupBy { it.operationId }
            .toList()
            .sortedWith(
                compareBy<Pair<String, List<com.dailymoneyregister.core.database.entity.SettlementEntryEntity>>> {
                    it.second.minOf { entry -> entry.occurredAt }
                }.thenBy { it.first }
            )
            .forEach { (_, entries) ->
                // Apply each operation atomically per tender identity. The
                // database row order must not affect whether valid evidence
                // appears to pass through a transient negative balance.
                entries.groupBy { it.category to it.channel }
                    .forEach { (identity, identityEntries) ->
                        val delta = identityEntries.fold(0L) { total, entry ->
                            Math.addExact(total, entry.amountSignedMinor)
                        }
                        val adjusted = Math.addExact(
                            fundingAmountsByIdentity[identity] ?: 0L,
                            delta
                        )
                        check(adjusted >= 0L) {
                            "Payment-method reclassification makes tender negative for transaction ${transaction.id}."
                        }
                        fundingAmountsByIdentity[identity] = adjusted
                    }
            }

        val fundingBreakdown = fundingAmountsByIdentity
            .filterValues { it > 0L }
            .map { (identity, amount) ->
                FinancialFundingPart(identity.first, identity.second, amount)
            }

        if (reclassifications.isNotEmpty()) {
            val originalFundingTotal = record.settlements
                .filter {
                    it.entryPurpose == fundingPurpose && it.amountSignedMinor > 0L
                }
                .fold(0L) { total, entry -> Math.addExact(total, entry.amountSignedMinor) }
            val currentFundingTotal = fundingBreakdown
                .fold(0L) { total, part -> Math.addExact(total, part.amountMinor) }
            check(currentFundingTotal == originalFundingTotal) {
                "Payment-method reclassification changed total sale funding for transaction ${transaction.id}."
            }
        }
        val couponSplit = saleFundingIdentities.size == 2 &&
            saleFundingIdentities.contains(PaymentCategory.COUPON to PaymentChannel.COUPON_WALLET) &&
            saleFundingIdentities.all { it in listOf(
                PaymentCategory.COUPON to PaymentChannel.COUPON_WALLET,
                PaymentCategory.CASH to PaymentChannel.CASH_TENDER,
                PaymentCategory.ONLINE to PaymentChannel.MANUAL_QR,
                PaymentCategory.CREDIT to PaymentChannel.CREDIT_ACCOUNT) } &&
            fundingBreakdown.fold(0L) { total, part -> Math.addExact(total, part.amountMinor) } == transaction.grandTotalMinor &&
            paymentChanges.isEmpty()
        if (saleFundingIdentities.size > 1 && !couponSplit) {
            throw IllegalStateException(
                "Ambiguous sale-funding identity for transaction ${transaction.id}"
            )
        }

        val authoritativeOriginalIdentity =
            saleFundingIdentities.singleOrNull()

        val originalPaymentCategory =
            authoritativeOriginalIdentity?.first
                ?: paymentChanges.firstOrNull()?.previousMethod

        val originalPaymentChannel =
            authoritativeOriginalIdentity?.second
                ?: paymentChanges.firstOrNull()?.previousChannel

        var currentPaymentCategory =
            originalPaymentCategory

        var currentPaymentChannel =
            originalPaymentChannel

        paymentChanges.forEach { change ->

            if (
                currentPaymentCategory != null &&
                change.previousMethod != currentPaymentCategory
            ) {
                throw IllegalStateException(
                    "Broken payment-method category chain for transaction ${transaction.id}"
                )
            }

            if (
                currentPaymentChannel != null &&
                change.previousChannel != currentPaymentChannel
            ) {
                throw IllegalStateException(
                    "Broken payment-method channel chain for transaction ${transaction.id}"
                )
            }

            currentPaymentCategory =
                change.newMethod

            currentPaymentChannel =
                change.newChannel
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

        val lifecycleStates = buildSet {
            if (record.amountCorrections.isNotEmpty()) add(FinancialLifecycleState.CORRECTED)
            if (paymentChanges.isNotEmpty()) add(FinancialLifecycleState.PAYMENT_METHOD_CHANGED)
            if (isVoided) add(FinancialLifecycleState.VOIDED)
            if (refundedAmount == currentAmount && refundedAmount.isPositive) {
                add(FinancialLifecycleState.FULLY_REFUNDED)
            } else if (refundedAmount.isPositive) {
                add(FinancialLifecycleState.PARTIALLY_REFUNDED)
            }
        }

        val periodSettlements = record.settlements.filter {
            it.occurredAt >= periodStart && it.occurredAt < periodEnd
        }
        var periodFinancialEffect = periodSettlements.fold(MoneyMinorUnits.ZERO) { total, settlement ->
            total + MoneyMinorUnits(settlement.amountSignedMinor)
        }
        val settlementOperationIds = record.settlements.mapTo(mutableSetOf()) { it.operationId }
        val hasAuthoritativeSaleFunding = record.settlements.any {
            it.entryPurpose == fundingPurpose
        }

        if (transaction.createdAt >= periodStart && transaction.createdAt < periodEnd &&
            !hasAuthoritativeSaleFunding
        ) {
            periodFinancialEffect += MoneyMinorUnits(transaction.grandTotalMinor)
        }

        corrections.forEach { correction ->
            if (correction.occurredAt in periodStart until periodEnd &&
                correction.operationId !in settlementOperationIds
            ) {
                periodFinancialEffect += MoneyMinorUnits(
                    correction.correctedAmountMinor - correction.previousAmountMinor
                )
            }
        }

        refunds.forEach { refund ->
            if (refund.occurredAt in periodStart until periodEnd &&
                refund.operationId !in settlementOperationIds
            ) {
                periodFinancialEffect -= MoneyMinorUnits(refund.refundAmountMinor)
            }
        }

        record.voidEvents.forEach { void ->
            if (void.occurredAt in periodStart until periodEnd &&
                void.operationId !in settlementOperationIds
            ) {
                periodFinancialEffect -= amountAfterRefunds
            }
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
            originalPaymentChannel = originalPaymentChannel,
            currentPaymentCategory = currentPaymentCategory,
            currentPaymentChannel = currentPaymentChannel,
            latestActivityAt = latestActivityAt,
            periodFinancialEffect = periodFinancialEffect,
            lifecycleStates = lifecycleStates,
            fundingBreakdown = fundingBreakdown
        )
    }
}
