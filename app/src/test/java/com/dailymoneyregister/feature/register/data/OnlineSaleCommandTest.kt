package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSaleCommandTest {
    private fun command(draft: DraftState = DraftState(manualAmount = MoneyMinorUnits(5000L))) =
        OnlineSaleCommand(
            operationId = OperationId("online-op"),
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = "cashier-1",
            transactionIdVal = TransactionId("online-tx"),
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "device-1",
            occurredAt = 1700000000000L
        )

    @Test
    fun commandCarriesOnlineSaleIdentityAndDurableIntent() {
        val command = command()
        assertEquals(OperationType.RECORD_SALE, command.operationType)
        assertEquals(SubjectType.TRANSACTION, command.subjectType)
        assertTrue(command.intentPayload.contains("commandType=ONLINE_SALE"))
        assertTrue(command.requestFingerprint.startsWith("online-v1|"))
    }

    @Test
    fun payloadRoundTripPreservesExactDraftAndFingerprint() {
        val original = command(
            DraftState(
                items = listOf(DraftItem("item-1", "Tea", unitPrice = MoneyMinorUnits(1500L), quantity = 2)),
                taxRateBps = 500,
                isTaxEnabled = true,
                txShipping = MoneyMinorUnits(300L),
                txPacking = MoneyMinorUnits(200L),
                manualDescription = "manual QR"
            )
        )
        val decoded = OnlineSalePayloadCodec.decode(original.intentPayload)
        assertEquals(original.requestFingerprint, decoded.requestFingerprint)
        assertEquals(original.draftState.grandTotal, decoded.draftState.grandTotal)
        assertEquals("Tea", decoded.draftState.items.single().name)
        assertEquals(2, decoded.draftState.items.single().quantity)
    }

    @Test
    fun alteredAmountChangesDurableFingerprint() {
        assertNotEquals(
            command().requestFingerprint,
            command(DraftState(manualAmount = MoneyMinorUnits(6000L))).requestFingerprint
        )
    }

    @Test
    fun malformedPayloadFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            OnlineSalePayloadCodec.decode("v=1\ncommandType=ONLINE_SALE\nitemCount=bad\n")
        }
    }

    @Test
    fun writerRejectsUnsupportedFundingCombinations() {
        assertThrows(IllegalArgumentException::class.java) {
            SaleFinancialWriter.validateFundingIdentity(
                SaleFinancialWriter.FundingIdentity(
                    PaymentCategory.ONLINE,
                    PaymentChannel.SOFTPOS,
                    ConfirmationType.CASHIER_CONFIRMED
                )
            )
        }
    }
}
