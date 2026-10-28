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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AmountCorrectionCouponSplitTest {

    private fun funding(
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
        confirmationType = ConfirmationType.ADMIN_CONFIRMED,
        occurredAt = 100L,
        actorUserId = "user-1"
    )

    private fun sale(vararg parts: SettlementEntryEntity) = TransactionFinancialRecord(
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

    private val coupon2000 = funding(
        "s-coupon", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, 2_000L
    )
    private val cash3000 = funding(
        "s-cash", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 3_000L
    )
    private val online3000 = funding(
        "s-online", PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR, 3_000L
    )
    private val credit3000 = funding(
        "s-credit", PaymentCategory.CREDIT, PaymentChannel.CREDIT_ACCOUNT, 3_000L
    )
    private val couponOnly5000 = funding(
        "s-coupon-only", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, 5_000L
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
    fun couponSaleWithCashRemainderCorrectsTheCashPartOnly() {
        val state = AmountCorrectionFinancialIntegrity.validate(
            sale(coupon2000, cash3000), emptyList(), 6_000L
        )
        assertEquals(5_000L, state.previousAmountMinor)
        assertEquals(1_000L, state.deltaMinor)
        assertEquals(PaymentCategory.CASH, state.currentCategory)
        assertEquals(PaymentChannel.CASH_TENDER, state.currentChannel)
    }

    @Test
    fun couponSaleWithOnlineRemainderCorrectsTheOnlinePartOnly() {
        val state = AmountCorrectionFinancialIntegrity.validate(
            sale(coupon2000, online3000), emptyList(), 4_000L
        )
        assertEquals(-1_000L, state.deltaMinor)
        assertEquals(PaymentCategory.ONLINE, state.currentCategory)
        assertEquals(PaymentChannel.MANUAL_QR, state.currentChannel)
    }

    @Test
    fun correctionDownToExactlyTheCouponAmountIsAllowed() {
        val state = AmountCorrectionFinancialIntegrity.validate(
            sale(coupon2000, cash3000), emptyList(), 2_000L
        )
        assertEquals(-3_000L, state.deltaMinor)
        assertEquals(PaymentCategory.CASH, state.currentCategory)
    }

    @Test
    fun correctionBelowTheCouponAmountIsRejected() {
        assertRejectedWith<AmountCorrectionBelowCouponAmountException> {
            AmountCorrectionFinancialIntegrity.validate(
                sale(coupon2000, cash3000), emptyList(), 1_999L
            )
        }
    }

    @Test
    fun couponOnlySaleStillGetsNoCorrection() {
        assertRejectedWith<AmountCorrectionUnsupportedFundingException> {
            AmountCorrectionFinancialIntegrity.validate(
                sale(couponOnly5000), emptyList(), 4_000L
            )
        }
    }

    @Test
    fun couponSaleWithCreditRemainderStillGetsNoCorrection() {
        assertRejectedWith<AmountCorrectionUnsupportedFundingException> {
            AmountCorrectionFinancialIntegrity.validate(
                sale(coupon2000, credit3000), emptyList(), 4_000L
            )
        }
    }
}