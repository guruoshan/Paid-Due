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
        val categories = enumValues<PaymentCategory>()
        assertTrue(categories.size >= 2)

        val originalCategory = categories[0]
        val newCategory = categories[1]

        val transaction = transaction(10_000L)

        val settlement =
            SettlementEntryEntity(
                id = "settlement-1",
                operationId = "operation-1",
                subjectType = enumValues<SubjectType>().first(),
                subjectId = transaction.id,
                transactionId = transaction.id,
                entryPurpose = enumValues<EntryPurpose>().first(),
                category = originalCategory,
                channel = enumValues<PaymentChannel>().first(),
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
                newMethod = newCategory,
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
            newCategory,
            view.currentPaymentCategory
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