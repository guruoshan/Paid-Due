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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TransactionActionEligibilityResolverTest {

    private fun record(
        type: TransactionType,
        category: PaymentCategory,
        channel: PaymentChannel
    ) = TransactionFinancialRecord(
        transaction = TransactionEntity(
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
        ),
        settlements = listOf(
            SettlementEntryEntity(
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
        )
    )

    @Test
    fun creditSaleGivesCreditVoidBlockReason() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            record(TransactionType.SALE, PaymentCategory.CREDIT, PaymentChannel.CREDIT_ACCOUNT),
            emptyList()
        )
        assertFalse(eligibility.canVoid)
        assertEquals(VoidBlockReason.CREDIT_SALE, eligibility.voidBlockReason)
    }

    @Test
    fun couponFundedSaleGivesCouponVoidBlockReason() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            record(TransactionType.SALE, PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET),
            emptyList()
        )
        assertFalse(eligibility.canVoid)
        assertEquals(VoidBlockReason.COUPON_SALE, eligibility.voidBlockReason)
    }

    @Test
    fun couponPurchaseGivesNoVoidBlockReason() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            record(TransactionType.COUPON_PURCHASE, PaymentCategory.CASH, PaymentChannel.CASH_TENDER),
            emptyList()
        )
        assertFalse(eligibility.canVoid)
        assertEquals(null, eligibility.voidBlockReason)
    }

    @Test
    fun cashSaleHasNoVoidBlockReason() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            record(TransactionType.SALE, PaymentCategory.CASH, PaymentChannel.CASH_TENDER),
            emptyList()
        )
        assertEquals(null, eligibility.voidBlockReason)
    }
    private fun cashSaleWithChange(
        previousMethod: PaymentCategory,
        previousChannel: PaymentChannel,
        newMethod: PaymentCategory,
        newChannel: PaymentChannel
    ) = TransactionFinancialRecord(
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
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = 100L,
                actorUserId = "user-1"
            )
        ),
        paymentMethodChanges = listOf(
            com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity(
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
        )
    )

    @Test
    fun brokenPaymentChangeChainGivesPaymentRecordUnclearReason() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            cashSaleWithChange(
                PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR,
                PaymentCategory.CASH, PaymentChannel.CASH_TENDER
            ),
            emptyList()
        )
        assertFalse(eligibility.canVoid)
        assertEquals(VoidBlockReason.PAYMENT_RECORD_UNCLEAR, eligibility.voidBlockReason)
    }

    @Test
    fun validSwitchedSaleHasNoVoidBlockReason() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            cashSaleWithChange(
                PaymentCategory.CASH, PaymentChannel.CASH_TENDER,
                PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR
            ),
            emptyList()
        )
        assertEquals(null, eligibility.voidBlockReason)
    }
    private fun couponSale(vararg parts: SettlementEntryEntity) = TransactionFinancialRecord(
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
        settlements = parts.toList()
    )

    private fun fundingPart(
        id: String,
        category: PaymentCategory,
        channel: PaymentChannel,
        amount: Long
    ) = SettlementEntryEntity(
        id = id,
        operationId = "operation-1",
        subjectType = SubjectType.TRANSACTION,
        subjectId = "transaction-1",
        transactionId = "transaction-1",
        entryPurpose = EntryPurpose.SALE_FUNDING,
        category = category,
        channel = channel,
        amountSignedMinor = amount,
        currencyCode = "OMR",
        confirmationType = ConfirmationType.CASHIER_CONFIRMED,
        occurredAt = 100L,
        actorUserId = "user-1"
    )

    @Test
    fun couponSaleWithCashRemainderCanBeCorrected() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            couponSale(
                fundingPart("s-coupon", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, 2_000L),
                fundingPart("s-cash", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 3_000L)
            ),
            emptyList()
        )
        assertEquals(true, eligibility.canCorrectAmount)
    }

    @Test
    fun couponOnlySaleCannotBeCorrected() {
        val eligibility = TransactionActionEligibilityResolver.resolve(
            couponSale(
                fundingPart("s-coupon-only", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, 5_000L)
            ),
            emptyList()
        )
        assertEquals(false, eligibility.canCorrectAmount)
    }
}
