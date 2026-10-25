package com.dailymoneyregister.core.model.financial

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.money.MoneyMinorUnits

enum class RecentActivityType {
    MERCHANDISE_SALE,
    COUPON_SELL,
    COUPON_TOP_UP,
    CREDIT_REPAYMENT,
    REFUND,
    REFUND_TO_COUPON,
    VOID,
    AMOUNT_CORRECTION,
    PAYMENT_METHOD_CHANGE,
    EXPENSE
}

enum class RecentActivitySourceType {
    TRANSACTION,
    CREDIT_REPAYMENT,
    REFUND_EVENT,
    VOID_EVENT,
    AMOUNT_CORRECTION_EVENT,
    PAYMENT_METHOD_CHANGE_EVENT,
    EXPENSE
}

enum class RecentActivityStatus {
    RECORDED,
    VOIDED
}

data class RecentFinancialActivity(
    val activityId: String,
    val transactionId: String?,
    val operationId: String?,
    val type: RecentActivityType,
    val signedEffect: MoneyMinorUnits,
    val fundingBreakdown: List<FinancialFundingPart>,
    val occurredAt: Long,
    val customerId: String?,
    val customerName: String?,
    val status: RecentActivityStatus,
    val originalTransactionId: String?,
    val sourceType: RecentActivitySourceType,
    val sourceReference: String,
    val displayRef: String,
    /** Sales reversal amount may differ from the actual payout on a credit refund. */
    val totalEffectOverride: MoneyMinorUnits? = null,
    val reason: String? = null
) {
    val isRevenue: Boolean
        get() = type == RecentActivityType.MERCHANDISE_SALE

    /**
     * Daily activity total is a sales-activity measure, not cash movement.
     * Merchandise sales count at full value for every tender, including Coupon
     * and Credit. Coupon purchase/top-up counts only the actual Cash/Online
     * funding received. Refunds and voids reverse sales; expenses and credit
     * repayments do not alter sales. Payment-method changes are classification
     * events only. A credit refund can therefore reverse sales without creating
     * a Cash/Online outflow.
     */
    val recentTotalEffect: MoneyMinorUnits
        get() {
            totalEffectOverride?.let { return it }
            return when (type) {
                RecentActivityType.MERCHANDISE_SALE ->
                    signedEffect

                RecentActivityType.COUPON_SELL,
                RecentActivityType.COUPON_TOP_UP -> {
                    if (fundingBreakdown.isEmpty()) {
                        signedEffect
                    } else {
                        val received = fundingBreakdown
                            .asSequence()
                            .filter {
                                it.category == PaymentCategory.CASH ||
                                    it.category == PaymentCategory.ONLINE
                            }
                            .fold(0L) { total, part ->
                                Math.addExact(total, part.amountMinor)
                            }
                        MoneyMinorUnits(received)
                    }
                }

                RecentActivityType.REFUND,
                RecentActivityType.REFUND_TO_COUPON,
                RecentActivityType.VOID,
                RecentActivityType.AMOUNT_CORRECTION ->
                    signedEffect

                RecentActivityType.CREDIT_REPAYMENT,
                RecentActivityType.PAYMENT_METHOD_CHANGE,
                RecentActivityType.EXPENSE ->
                    MoneyMinorUnits.ZERO
            }
        }

}

object RecentActivityTotals {

    fun todayTotal(
        activities: List<RecentFinancialActivity>
    ): MoneyMinorUnits =
        activities.fold(MoneyMinorUnits.ZERO) { total, activity ->
            total + activity.recentTotalEffect
        }
}

object RecentActivityReadModelResolver {

    fun resolveCreditRepayment(
        repayment: CreditRepaymentEntity,
        customerName: String?,
        settlements: List<SettlementEntryEntity>
    ): RecentFinancialActivity {
        val funding =
            fundingParts(
                settlements.filter {
                    it.operationId == repayment.operationId &&
                        it.entryPurpose == EntryPurpose.CREDIT_REPAYMENT_COLLECTION
                }
            )

        return RecentFinancialActivity(
            activityId = "credit-repayment:${repayment.id}",
            // A repayment is authoritative financial activity, but it is not
            // a TransactionEntity and must not route to Transaction Details.
            transactionId = null,
            operationId = repayment.operationId,
            type = RecentActivityType.CREDIT_REPAYMENT,
            signedEffect = MoneyMinorUnits(repayment.amountMinor),
            fundingBreakdown = funding,
            occurredAt = repayment.occurredAt,
            customerId = repayment.customerId,
            customerName = customerName,
            status = RecentActivityStatus.RECORDED,
            originalTransactionId = null,
            sourceType = RecentActivitySourceType.CREDIT_REPAYMENT,
            sourceReference = repayment.id,
            displayRef = repayment.id
        )
    }
    fun resolve(
        records: List<TransactionFinancialRecord>,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): List<RecentFinancialActivity> {
        require(periodStartMillis < periodEndMillis) { "Invalid recent activity period" }
        return records
            .flatMap { resolve(it, periodStartMillis, periodEndMillis) }
            .sortedWith(
                compareByDescending<RecentFinancialActivity> { it.occurredAt }
                    .thenByDescending { it.activityId }
            )
    }

    fun resolve(
        record: TransactionFinancialRecord,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): List<RecentFinancialActivity> {
        require(periodStartMillis < periodEndMillis) { "Invalid recent activity period" }
        val transaction = record.transaction
        val activities = mutableListOf<RecentFinancialActivity>()
        val customerName = record.customer?.takeIf { it.id == transaction.customerId }?.name

        if (transaction.createdAt in periodStartMillis until periodEndMillis) {
            val fundingPurpose = if (transaction.type == TransactionType.COUPON_PURCHASE) {
                EntryPurpose.COUPON_PURCHASE_FUNDING
            } else {
                EntryPurpose.SALE_FUNDING
            }
            val funding = fundingParts(record.settlements.filter { it.entryPurpose == fundingPurpose })
            val type = when (transaction.type) {
                TransactionType.SALE -> RecentActivityType.MERCHANDISE_SALE
                TransactionType.COUPON_PURCHASE -> {
                    if (transaction.description == "Coupon Top Up") {
                        RecentActivityType.COUPON_TOP_UP
                    } else {
                        RecentActivityType.COUPON_SELL
                    }
                }
            }
            activities += RecentFinancialActivity(
                activityId = "transaction:${transaction.id}",
                transactionId = transaction.id,
                operationId = singleOperationId(record.settlements.filter { it.entryPurpose == fundingPurpose }),
                type = type,
                signedEffect = MoneyMinorUnits(transaction.grandTotalMinor),
                fundingBreakdown = funding,
                occurredAt = transaction.createdAt,
                customerId = transaction.customerId,
                customerName = customerName,
                status = RecentActivityStatus.RECORDED,
                originalTransactionId = null,
                sourceType = RecentActivitySourceType.TRANSACTION,
                sourceReference = transaction.id,
                displayRef = transaction.displayRef
            )
        }

        record.refundEvents
            .filter { it.occurredAt in periodStartMillis until periodEndMillis }
            .forEach { refund -> activities += refundActivity(record, refund, customerName) }

        record.voidEvents
            .filter { it.occurredAt in periodStartMillis until periodEndMillis }
            .forEach { void -> activities += voidActivity(record, void, customerName) }

        record.amountCorrections
            .filter { it.occurredAt in periodStartMillis until periodEndMillis }
            .forEach { correction -> activities += correctionActivity(record, correction, customerName) }

        record.paymentMethodChanges
            .filter { it.occurredAt in periodStartMillis until periodEndMillis }
            .forEach { change -> activities += paymentChangeActivity(record, change, customerName) }

        return activities
    }

    private fun refundActivity(
        record: TransactionFinancialRecord,
        refund: RefundEventEntity,
        customerName: String?
    ): RecentFinancialActivity {
        val settlements = record.settlements.filter {
            it.operationId == refund.operationId && it.entryPurpose == EntryPurpose.REFUND_OUTFLOW
        }
        val funding = fundingParts(settlements)
        val toCoupon = settlements.any { it.category == PaymentCategory.COUPON }

        /*
         * BOARDLOCK Recent financial truth.
         *
         * A Credit return can contain two different economic components:
         *
         * 1. CREDIT settlement:
         *    cancellation/reduction of unpaid debt.
         *
         * 2. CASH / ONLINE / COUPON settlement:
         *    value actually refunded to the customer or Coupon recipient.
         *
         * The row breakdown shows both components, but the prominent
         * signed amount must show the value actually refunded.
         *
         * Example:
         * original Credit sale       26
         * previously repaid          10
         * unpaid Credit reduction    16
         * Coupon value refunded      10
         *
         * Breakdown:
         * 10 Coupon refunded + 16 Credit reduced
         *
         * Prominent Recent amount:
         * -10, not -26.
         *
         * For ordinary non-Credit refunds there is no CREDIT component,
         * so this remains equal to the complete refund outflow.
         */
        val actualRefundedValueMinor =
            funding
                .asSequence()
                .filter {
                    it.category != PaymentCategory.CREDIT
                }
                .fold(0L) { total, part ->
                    Math.addExact(
                        total,
                        part.amountMinor
                    )
                }

        /*
         * Legacy evidence may not contain REFUND_OUTFLOW settlements.
         * Preserve the historical fallback only when no settlement
         * breakdown exists. Never guess from a partial modern breakdown.
         */
        /*
         * BOARDLOCK_UNPAID_CREDIT_RECENT_FINAL
         *
         * A zero-repayment Credit return intentionally has no
         * REFUND_OUTFLOW settlement because no external value was paid.
         *
         * Distinguish that modern authoritative case from genuinely
         * legacy refund evidence that may also lack settlements.
         */
        val originalCreditFunding =
            record.settlements.any {
                it.entryPurpose == EntryPurpose.SALE_FUNDING &&
                    it.category == PaymentCategory.CREDIT
            }

        val unpaidCreditReturn =
            funding.isEmpty() &&
                originalCreditFunding

        val projectedFunding =
            if (unpaidCreditReturn) {
                listOf(
                    FinancialFundingPart(
                        category = PaymentCategory.CREDIT,
                        channel = PaymentChannel.CREDIT_ACCOUNT,
                        amountMinor = refund.refundAmountMinor
                    )
                )
            } else {
                funding
            }

        val recentRefundEffectMinor =
            when {
                unpaidCreditReturn ->
                    refund.refundAmountMinor

                funding.isEmpty() ->
                    refund.refundAmountMinor

                else ->
                    actualRefundedValueMinor
            }

        return lifecycleActivity(
            record = record,
            activityId = "refund:${refund.id}",
            operationId = refund.operationId,
            type = if (toCoupon) RecentActivityType.REFUND_TO_COUPON else RecentActivityType.REFUND,
            signedEffect =
                MoneyMinorUnits(
                    Math.negateExact(
                        recentRefundEffectMinor
                    )
                ),
            fundingBreakdown = projectedFunding,
            occurredAt = refund.occurredAt,
            customerName = customerName,
            sourceType = RecentActivitySourceType.REFUND_EVENT,
            sourceReference = refund.id,
            totalEffectOverride = MoneyMinorUnits(Math.negateExact(refund.refundAmountMinor))
        )
    }

    private fun voidActivity(
        record: TransactionFinancialRecord,
        void: VoidEventEntity,
        customerName: String?
    ): RecentFinancialActivity {
        val settlements = record.settlements.filter {
            it.operationId == void.operationId && it.entryPurpose == EntryPurpose.VOID_REVERSAL
        }
        val signedEffect = if (settlements.isNotEmpty()) {
            settlements.fold(0L) { total, settlement ->
                Math.addExact(total, settlement.amountSignedMinor)
            }
        } else {
            legacyVoidEffect(record, void.occurredAt)
        }
        return lifecycleActivity(
            record = record,
            activityId = "void:${void.id}",
            operationId = void.operationId,
            type = RecentActivityType.VOID,
            signedEffect = MoneyMinorUnits(signedEffect),
            fundingBreakdown = fundingParts(settlements),
            occurredAt = void.occurredAt,
            customerName = customerName,
            sourceType = RecentActivitySourceType.VOID_EVENT,
            sourceReference = void.id
        )
    }

    private fun correctionActivity(
        record: TransactionFinancialRecord,
        correction: AmountCorrectionEventEntity,
        customerName: String?
    ): RecentFinancialActivity {
        // BOARDLOCK_CORRECTION_FUNDING_FOR_RECENT_TOTAL
        // Existing authoritative FUNDING_CORRECTION settlement evidence
        // is projected read-only so Today Total keeps the same payment
        // semantics after a correction.
        val settlements = record.settlements.filter {
            it.operationId == correction.operationId &&
                it.entryPurpose == EntryPurpose.FUNDING_CORRECTION
        }

        return lifecycleActivity(
            record = record,
            activityId = "correction:${correction.id}",
            operationId = correction.operationId,
            type = RecentActivityType.AMOUNT_CORRECTION,
            signedEffect = MoneyMinorUnits(
                Math.subtractExact(
                    correction.correctedAmountMinor,
                    correction.previousAmountMinor
                )
            ),
            fundingBreakdown = fundingParts(settlements),
            occurredAt = correction.occurredAt,
            customerName = customerName,
            sourceType = RecentActivitySourceType.AMOUNT_CORRECTION_EVENT,
            sourceReference = correction.id
        )
    }

    private fun paymentChangeActivity(
        record: TransactionFinancialRecord,
        change: PaymentMethodChangeEventEntity,
        customerName: String?
    ) = lifecycleActivity(
        record = record,
        activityId = "payment-change:${change.id}",
        operationId = change.operationId,
        type = RecentActivityType.PAYMENT_METHOD_CHANGE,
        signedEffect = MoneyMinorUnits.ZERO,
        fundingBreakdown = change.newChannel?.let { channel ->
            listOf(FinancialFundingPart(change.newMethod, channel, 0L))
        }.orEmpty(),
        occurredAt = change.occurredAt,
        customerName = customerName,
        sourceType = RecentActivitySourceType.PAYMENT_METHOD_CHANGE_EVENT,
        sourceReference = change.id
    )

    private fun lifecycleActivity(
        record: TransactionFinancialRecord,
        activityId: String,
        operationId: String,
        type: RecentActivityType,
        signedEffect: MoneyMinorUnits,
        fundingBreakdown: List<FinancialFundingPart>,
        occurredAt: Long,
        customerName: String?,
        sourceType: RecentActivitySourceType,
        sourceReference: String,
        totalEffectOverride: MoneyMinorUnits? = null
    ) = RecentFinancialActivity(
        activityId = activityId,
        transactionId = record.transaction.id,
        operationId = operationId,
        type = type,
        signedEffect = signedEffect,
        fundingBreakdown = fundingBreakdown,
        occurredAt = occurredAt,
        customerId = record.transaction.customerId,
        customerName = customerName,
        status = RecentActivityStatus.RECORDED,
        originalTransactionId = record.transaction.id,
        sourceType = sourceType,
        sourceReference = sourceReference,
        displayRef = record.transaction.displayRef,
        totalEffectOverride = totalEffectOverride
    )

    private fun fundingParts(settlements: List<SettlementEntryEntity>): List<FinancialFundingPart> =
        settlements
            .groupBy { it.category to it.channel }
            .map { (identity, entries) ->
                FinancialFundingPart(
                    category = identity.first,
                    channel = identity.second,
                    amountMinor = entries.fold(0L) { total, entry ->
                        Math.addExact(total, kotlin.math.abs(entry.amountSignedMinor))
                    }
                )
            }
            .sortedWith(compareBy({ it.category.name }, { it.channel.name }))

    private fun singleOperationId(settlements: List<SettlementEntryEntity>): String? =
        settlements.map { it.operationId }.distinct().singleOrNull()

    private fun legacyVoidEffect(record: TransactionFinancialRecord, occurredAt: Long): Long {
        var amount = record.transaction.grandTotalMinor
        record.amountCorrections
            .filter { it.occurredAt <= occurredAt }
            .sortedWith(compareBy<AmountCorrectionEventEntity> { it.occurredAt }.thenBy { it.id })
            .forEach { amount = it.correctedAmountMinor }
        val refunded = record.refundEvents
            .filter { it.occurredAt <= occurredAt }
            .fold(0L) { total, refund -> Math.addExact(total, refund.refundAmountMinor) }
        return Math.negateExact(Math.subtractExact(amount, refunded))
    }
}
