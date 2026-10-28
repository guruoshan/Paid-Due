package com.dailymoneyregister.core.model.financial

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialReadModelTest {

    @Test
    fun originalSaleRemainsOriginalAndProducesCurrentNetAmount() {
        val transaction = transaction(10_000L)

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction
            )
        )

        assertEquals(10_000L, view.originalAmount.amount)
        assertEquals(10_000L, view.currentAmount.amount)
        assertEquals(0L, view.refundedAmount.amount)
        assertEquals(10_000L, view.netAmount.amount)
        assertFalse(view.isVoided)
        assertFalse(view.hasAmountCorrection)
        assertFalse(view.isPartiallyRefunded)
        assertFalse(view.isFullyRefunded)
    }

    @Test
    fun correctionAndRefundProduceOneCurrentFinancialTruth() {
        val transaction = transaction(10_000L)

        val correction =
            AmountCorrectionEventEntity(
                id = "correction-1",
                transactionId = transaction.id,
                operationId = "operation-1",
                previousAmountMinor = 10_000L,
                correctedAmountMinor = 9_500L,
                actorUserId = "user-1",
                occurredAt = 2L
            )

        val refund =
            RefundEventEntity(
                id = "refund-1",
                transactionId = transaction.id,
                operationId = "operation-2",
                refundAmountMinor = 2_000L,
                actorUserId = "user-1",
                occurredAt = 3L
            )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                amountCorrections = listOf(correction),
                refundEvents = listOf(refund)
            )
        )

        assertEquals(10_000L, view.originalAmount.amount)
        assertEquals(9_500L, view.currentAmount.amount)
        assertEquals(2_000L, view.refundedAmount.amount)
        assertEquals(7_500L, view.netAmount.amount)
        assertTrue(view.hasAmountCorrection)
        assertTrue(view.isPartiallyRefunded)
        assertFalse(view.isFullyRefunded)
    }

    @Test
    fun fullRefundIsClassifiedFromAuthoritativeAmounts() {
        val transaction = transaction(10_000L)
        val refund = RefundEventEntity(
            id = "refund-full",
            transactionId = transaction.id,
            operationId = "operation-full-refund",
            refundAmountMinor = 10_000L,
            actorUserId = "user-1",
            occurredAt = 2L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                refundEvents = listOf(refund)
            )
        )

        assertEquals(view.currentAmount, view.refundedAmount)
        assertTrue(view.isFullyRefunded)
        assertFalse(view.isPartiallyRefunded)
    }

    @Test
    fun correctionHistoryRemainsVisibleWhenCorrectionsRestoreOriginalAmount() {
        val transaction = transaction(10_000L)
        val firstCorrection = AmountCorrectionEventEntity(
            id = "correction-restore-1",
            transactionId = transaction.id,
            operationId = "operation-restore-1",
            previousAmountMinor = 10_000L,
            correctedAmountMinor = 9_000L,
            actorUserId = "user-1",
            occurredAt = 2L
        )
        val secondCorrection = AmountCorrectionEventEntity(
            id = "correction-restore-2",
            transactionId = transaction.id,
            operationId = "operation-restore-2",
            previousAmountMinor = 9_000L,
            correctedAmountMinor = 10_000L,
            actorUserId = "user-1",
            occurredAt = 3L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                amountCorrections = listOf(firstCorrection, secondCorrection)
            )
        )

        assertEquals(view.originalAmount, view.currentAmount)
        assertTrue(view.hasAmountCorrection)
    }

    @Test
    fun voidMakesCurrentNetFinancialImpactZero() {
        val transaction = transaction(10_000L)

        val void =
            VoidEventEntity(
                id = "void-1",
                transactionId = transaction.id,
                operationId = "operation-1",
                actorUserId = "user-1",
                occurredAt = 2L
            )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                voidEvents = listOf(void)
            )
        )

        assertTrue(view.isVoided)
        assertEquals(0L, view.netAmount.amount)
        assertEquals(10_000L, view.originalAmount.amount)
    }

    @Test
    fun paymentMethodChangeProducesCurrentMethodWithoutChangingHistory() {
        val originalCategory = PaymentCategory.CASH
        val originalChannel = PaymentChannel.CASH_TENDER
        val newCategory = PaymentCategory.ONLINE
        val newChannel = PaymentChannel.MANUAL_QR

        val transaction = transaction(10_000L)

        val settlement =
            SettlementEntryEntity(
                id = "settlement-1",
                operationId = "operation-1",
                subjectType = enumValues<SubjectType>().first(),
                subjectId = transaction.id,
                transactionId = transaction.id,
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = originalCategory,
                channel = originalChannel,
                amountSignedMinor = 10_000L,
                currencyCode = "OMR",
                confirmationType = enumValues<ConfirmationType>().first(),
                occurredAt = 1L,
                actorUserId = "user-1"
            )

        val change =
            PaymentMethodChangeEventEntity(
                id = "payment-change-1",
                transactionId = transaction.id,
                operationId = "operation-2",
                previousMethod = originalCategory,
                previousChannel = originalChannel,
                newMethod = newCategory,
                newChannel = newChannel,
                actorUserId = "user-1",
                occurredAt = 2L
            )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                paymentMethodChanges = listOf(change),
                settlements = listOf(settlement)
            )
        )

        assertEquals(
            originalCategory,
            view.originalPaymentCategory
        )

        assertEquals(
            originalChannel,
            view.originalPaymentChannel
        )

        assertEquals(
            newCategory,
            view.currentPaymentCategory
        )

        assertEquals(
            newChannel,
            view.currentPaymentChannel
        )

        assertEquals(
            10_000L,
            view.settlementTotal.amount
        )
    }

    @Test
    fun brokenCorrectionHistoryFailsClosed() {
        val transaction = transaction(10_000L)

        val brokenCorrection =
            AmountCorrectionEventEntity(
                id = "correction-1",
                transactionId = transaction.id,
                operationId = "operation-1",
                previousAmountMinor = 8_000L,
                correctedAmountMinor = 9_000L,
                actorUserId = "user-1",
                occurredAt = 2L
            )

        var failed = false

        try {
            FinancialReadModelResolver.resolve(
                TransactionFinancialRecord(
                    transaction = transaction,
                    amountCorrections =
                        listOf(brokenCorrection)
                )
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
    }

    @Test
    fun yesterdaySaleAndTodayRefundUseOnlyTodaysNegativeEffect() {
        val transaction = transaction(10_000L).copy(createdAt = 1_000L)
        val saleSettlement = settlement("sale", 1_000L, 10_000L)
        val refund = RefundEventEntity(
            id = "refund-today",
            transactionId = transaction.id,
            operationId = "refund-operation",
            refundAmountMinor = 3_000L,
            actorUserId = "user-1",
            occurredAt = 2_000L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(transaction, refundEvents = listOf(refund), settlements = listOf(saleSettlement)),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(7_000L, view.netAmount.amount)
        assertEquals(-3_000L, view.periodFinancialEffect.amount)
        assertEquals(2_000L, view.latestActivityAt)
    }

    @Test
    fun saleTodayHasPositivePeriodEffect() {
        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(transaction(10_000L).copy(createdAt = 2_000L)),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(10_000L, view.periodFinancialEffect.amount)
    }

    @Test
    fun saleFundingSettlementAtSaleTimestampIsCountedOnce() {
        val transaction = transaction(10_000L).copy(createdAt = 2_000L)
        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                settlements = listOf(settlement("sale-funding", 2_000L, 10_000L))
            ),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(10_000L, view.periodFinancialEffect.amount)
    }

    @Test
    fun saleFundingSettlementAtDifferentTimestampIsCountedOnce() {
        val transaction = transaction(10_000L).copy(createdAt = 2_000L)
        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                settlements = listOf(settlement("sale-funding", 2_500L, 10_000L))
            ),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(10_000L, view.periodFinancialEffect.amount)
    }

    @Test
    fun multipleSaleFundingSettlementsUseSignedSettlementSumOnce() {
        val transaction = transaction(10_000L).copy(createdAt = 2_000L)
        val first = settlement("sale-funding-1", 2_100L, 6_000L)
        val second = settlement("sale-funding-2", 2_200L, 4_000L)

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(transaction = transaction, settlements = listOf(first, second)),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(10_000L, view.periodFinancialEffect.amount)
    }

    @Test
    fun lifecycleSettlementOperationIsCountedOnlyInSettlementPeriod() {
        val transaction = transaction(10_000L).copy(createdAt = 500L)
        val refund = RefundEventEntity(
            id = "refund-cross-period",
            transactionId = transaction.id,
            operationId = "refund-cross-period-operation",
            refundAmountMinor = 3_000L,
            actorUserId = "user-1",
            occurredAt = 1_500L
        )
        val refundSettlement = settlement(
            operationId = "refund-cross-period-operation",
            occurredAt = 2_500L,
            amount = -3_000L
        ).copy(entryPurpose = EntryPurpose.REFUND_OUTFLOW)
        val record = TransactionFinancialRecord(
            transaction = transaction,
            refundEvents = listOf(refund),
            settlements = listOf(refundSettlement)
        )

        val lifecyclePeriod = FinancialReadModelResolver.resolve(
            record,
            periodStartMillis = 1_000L,
            periodEndMillis = 2_000L
        )
        val settlementPeriod = FinancialReadModelResolver.resolve(
            record,
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(0L, lifecyclePeriod.periodFinancialEffect.amount)
        assertEquals(-3_000L, settlementPeriod.periodFinancialEffect.amount)
    }

    @Test
    fun todayVoidReversesOnlyRemainingValueAfterEarlierRefund() {
        val transaction = transaction(10_000L).copy(createdAt = 1_000L)
        val refund = RefundEventEntity(
            id = "refund-before-void",
            transactionId = transaction.id,
            operationId = "refund-operation",
            refundAmountMinor = 3_000L,
            actorUserId = "user-1",
            occurredAt = 1_500L
        )
        val void = VoidEventEntity(
            id = "void-today",
            transactionId = transaction.id,
            operationId = "void-operation",
            actorUserId = "user-1",
            occurredAt = 2_000L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(transaction, refundEvents = listOf(refund), voidEvents = listOf(void)),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(-7_000L, view.periodFinancialEffect.amount)
        assertTrue(FinancialLifecycleState.VOIDED in view.lifecycleStates)
    }

    @Test
    fun todayCorrectionContributesOnlyItsSignedDelta() {
        val transaction = transaction(10_000L).copy(createdAt = 1_000L)
        val correction = AmountCorrectionEventEntity(
            id = "correction-today",
            transactionId = transaction.id,
            operationId = "correction-operation",
            previousAmountMinor = 10_000L,
            correctedAmountMinor = 12_000L,
            actorUserId = "user-1",
            occurredAt = 2_000L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(transaction, amountCorrections = listOf(correction)),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(12_000L, view.netAmount.amount)
        assertEquals(2_000L, view.periodFinancialEffect.amount)
        assertTrue(FinancialLifecycleState.CORRECTED in view.lifecycleStates)
    }

    @Test
    fun paymentMethodChangeUpdatesActivityWithoutAddingRevenue() {
        val transaction = transaction(10_000L).copy(createdAt = 1_000L)
        val change = PaymentMethodChangeEventEntity(
            id = "payment-change-today",
            transactionId = transaction.id,
            operationId = "payment-change-operation",
            previousMethod = PaymentCategory.CASH,
            previousChannel = PaymentChannel.CASH_TENDER,
            newMethod = PaymentCategory.ONLINE,
            newChannel = PaymentChannel.MANUAL_QR,
            actorUserId = "user-1",
            occurredAt = 2_000L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(transaction, paymentMethodChanges = listOf(change)),
            periodStartMillis = 2_000L,
            periodEndMillis = 3_000L
        )

        assertEquals(0L, view.periodFinancialEffect.amount)
        assertEquals(2_000L, view.latestActivityAt)
        assertTrue(FinancialLifecycleState.PAYMENT_METHOD_CHANGED in view.lifecycleStates)
    }

    @Test
    fun paymentIdentityUsesSaleFundingNotUnrelatedPositiveSettlement() {
        val transaction = transaction(10_000L)

        val unrelatedPositive = settlement(
            operationId = "repayment-operation",
            occurredAt = 1L,
            amount = 2_000L
        ).copy(
            entryPurpose = EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
            category = PaymentCategory.ONLINE,
            channel = PaymentChannel.SOFTPOS
        )

        val saleFunding = settlement(
            operationId = "sale-operation",
            occurredAt = 2L,
            amount = 10_000L
        )

        val view = FinancialReadModelResolver.resolve(
            TransactionFinancialRecord(
                transaction = transaction,
                settlements = listOf(unrelatedPositive, saleFunding)
            )
        )

        assertEquals(PaymentCategory.CASH, view.originalPaymentCategory)
        assertEquals(PaymentChannel.CASH_TENDER, view.originalPaymentChannel)
        assertEquals(PaymentCategory.CASH, view.currentPaymentCategory)
        assertEquals(PaymentChannel.CASH_TENDER, view.currentPaymentChannel)
    }

    @Test
    fun paymentMethodChangeBrokenChannelChainFailsClosed() {
        val transaction = transaction(10_000L)
        val saleFunding = settlement(
            operationId = "sale-operation",
            occurredAt = 1L,
            amount = 10_000L
        )
        val change = PaymentMethodChangeEventEntity(
            id = "payment-change-broken-channel",
            transactionId = transaction.id,
            operationId = "payment-change-operation",
            previousMethod = PaymentCategory.CASH,
            previousChannel = PaymentChannel.MANUAL_QR,
            newMethod = PaymentCategory.ONLINE,
            newChannel = PaymentChannel.SOFTPOS,
            actorUserId = "user-1",
            occurredAt = 2L
        )

        var failed = false
        try {
            FinancialReadModelResolver.resolve(
                TransactionFinancialRecord(
                    transaction = transaction,
                    paymentMethodChanges = listOf(change),
                    settlements = listOf(saleFunding)
                )
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
    }

    @Test
    fun settlementCurrencyMismatchFailsClosed() {
        val transaction = transaction(10_000L)
        val mismatchedSettlement = settlement("wrong-currency", 2_000L, 10_000L)
            .copy(currencyCode = "INR")

        var failed = false
        try {
            FinancialReadModelResolver.resolve(
                TransactionFinancialRecord(transaction, settlements = listOf(mismatchedSettlement)),
                periodStartMillis = 2_000L,
                periodEndMillis = 3_000L
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
    }

    private fun settlement(
        operationId: String,
        occurredAt: Long,
        amount: Long
    ) = SettlementEntryEntity(
        id = "settlement-$operationId",
        operationId = operationId,
        subjectType = SubjectType.TRANSACTION,
        subjectId = "transaction-1",
        transactionId = "transaction-1",
        entryPurpose = EntryPurpose.SALE_FUNDING,
        category = PaymentCategory.CASH,
        channel = PaymentChannel.CASH_TENDER,
        amountSignedMinor = amount,
        currencyCode = "OMR",
        confirmationType = ConfirmationType.ADMIN_CONFIRMED,
        occurredAt = occurredAt,
        actorUserId = "user-1"
    )

    private fun transaction(
        totalMinor: Long
    ): TransactionEntity {
        return TransactionEntity(
            id = "transaction-1",
            branchId = "branch-1",
            type = enumValues<TransactionType>().first(),
            subtotalMinor = totalMinor,
            taxRateBps = 0,
            taxableBaseMinor = totalMinor,
            taxAmountMinor = 0L,
            grandTotalMinor = totalMinor,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            createdByUserId = "user-1",
            deviceId = "device-1",
            createdAt = 1L,
            displayRef = "TX-1"
        )
    }
}
