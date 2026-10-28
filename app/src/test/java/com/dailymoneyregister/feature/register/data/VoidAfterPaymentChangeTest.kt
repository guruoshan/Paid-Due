package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoidAfterPaymentChangeTest {

    private fun cashSale(changes: List<PaymentMethodChangeEventEntity>) = TransactionFinancialRecord(
        transaction = TransactionEntity(
            id = "transaction-1",
            branchId = "branch-1",
            type = TransactionType.SALE,
            subtotalMinor = 5_000L,
            taxRateBps = 0,
            taxableBaseMinor = 5_000L,
            taxAmountMinor = 0L,
            grandTotalMinor = 5_000L,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            createdByUserId = "user-1",
            deviceId = "device-1",
            createdAt = 1L,
            displayRef = "TX-1"
        ),
        settlements = listOf(
            SettlementEntryEntity(
                id = "settlement-1",
                operationId = "operation-1",
                subjectType = SubjectType.TRANSACTION,
                subjectId = "transaction-1",
                transactionId = "transaction-1",
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = 5_000L,
                currencyCode = "OMR",
                confirmationType = ConfirmationType.ADMIN_CONFIRMED,
                occurredAt = 100L,
                actorUserId = "user-1"
            )
        ),
        paymentMethodChanges = changes
    )

    private fun change(
        previousMethod: PaymentCategory,
        previousChannel: PaymentChannel?,
        newMethod: PaymentCategory,
        newChannel: PaymentChannel?
    ) = PaymentMethodChangeEventEntity(
        id = "change-1",
        transactionId = "transaction-1",
        operationId = "change-op-1",
        previousMethod = previousMethod,
        previousChannel = previousChannel,
        newMethod = newMethod,
        newChannel = newChannel,
        actorUserId = "user-1",
        occurredAt = 200L
    )

    private inline fun <reified T : Throwable> assertRejectedWith(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            assertTrue(
                "expected ${T::class.java.simpleName}, got ${e::class.java.simpleName}",
                e is T
            )
            return
        }
        fail("expected ${T::class.java.simpleName}, but no exception was thrown")
    }

    @Test
    fun switchedCashSaleVoidReversesTheCurrentOnlineIdentity() {
        val state = VoidFinancialIntegrity.validate(
            cashSale(
                listOf(
                    change(
                        PaymentCategory.CASH, PaymentChannel.CASH_TENDER,
                        PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR
                    )
                )
            ),
            emptyList()
        )
        assertEquals(5_000L, state.reversibleAmountMinor)
        assertEquals(PaymentCategory.ONLINE, state.fundingCategory)
        assertEquals(PaymentChannel.MANUAL_QR, state.fundingChannel)
    }

    @Test
    fun brokenPaymentChangeChainIsRejectedAsAmbiguous() {
        assertRejectedWith<VoidAmbiguousSettlementException> {
            VoidFinancialIntegrity.validate(
                cashSale(
                    listOf(
                        change(
                            PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR,
                            PaymentCategory.CASH, PaymentChannel.CASH_TENDER
                        )
                    )
                ),
                emptyList()
            )
        }
    }
}
