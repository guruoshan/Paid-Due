package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoidFinancialIntegrityTest {

    private fun transaction(type: TransactionType) = TransactionEntity(
        id = "transaction-1",
        branchId = "branch-1",
        type = type,
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
    )

    private fun funding(category: PaymentCategory, channel: PaymentChannel) = SettlementEntryEntity(
        id = "settlement-1",
        operationId = "operation-1",
        subjectType = SubjectType.TRANSACTION,
        subjectId = "transaction-1",
        transactionId = "transaction-1",
        entryPurpose = EntryPurpose.SALE_FUNDING,
        category = category,
        channel = channel,
        amountSignedMinor = 5_000L,
        currencyCode = "OMR",
        confirmationType = ConfirmationType.CASHIER_CONFIRMED,
        occurredAt = 100L,
        actorUserId = "user-1"
    )

    private fun record(
        type: TransactionType,
        category: PaymentCategory,
        channel: PaymentChannel
    ) = TransactionFinancialRecord(
        transaction = transaction(type),
        settlements = listOf(funding(category, channel))
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
    fun cashSaleVoidIsAcceptedAsControl() {
        VoidFinancialIntegrity.validate(
            record(TransactionType.SALE, PaymentCategory.CASH, PaymentChannel.CASH_TENDER),
            emptyList()
        )
    }

    @Test
    fun creditFundedSaleVoidIsRejected() {
        assertRejectedWith<VoidUnsupportedCreditException> {
            VoidFinancialIntegrity.validate(
                record(TransactionType.SALE, PaymentCategory.CREDIT, PaymentChannel.CREDIT_ACCOUNT),
                emptyList()
            )
        }
    }

    @Test
    fun couponFundedSaleVoidIsRejected() {
        assertRejectedWith<VoidUnsupportedCouponException> {
            VoidFinancialIntegrity.validate(
                record(TransactionType.SALE, PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET),
                emptyList()
            )
        }
    }

    @Test
    fun couponPurchaseVoidIsRejected() {
        assertRejectedWith<VoidUnsupportedCouponException> {
            VoidFinancialIntegrity.validate(
                record(TransactionType.COUPON_PURCHASE, PaymentCategory.CASH, PaymentChannel.CASH_TENDER),
                emptyList()
            )
        }
    }
}