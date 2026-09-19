package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CashSaleCommandTest {

    @Test
    fun testCashSaleCommandProperties() {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()
        val draft = DraftState(manualAmount = MoneyMinorUnits(5000L))

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = "user-cashier-1",
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        assertEquals(opId, command.operationId)
        assertEquals(OperationType.RECORD_SALE, command.operationType)
        assertEquals(SubjectType.TRANSACTION, command.subjectType)
        assertEquals(txId.value, command.subjectId)
        assertNotNull(command.requestFingerprint)
        assertNotNull(command.intentPayload)
    }

    @Test
    fun testCanonicalFingerprintSensitivity() {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()
        val draft1 = DraftState(manualAmount = MoneyMinorUnits(5000L))
        val draft2 = DraftState(manualAmount = MoneyMinorUnits(6000L))

        val cmd1 = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = "user-1",
            transactionIdVal = txId,
            draftState = draft1,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = 1000000L
        )

        val cmd1Identical = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = "user-1",
            transactionIdVal = txId,
            draftState = draft1,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = 1000000L
        )

        val cmd2DifferentAmount = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = "user-1",
            transactionIdVal = txId,
            draftState = draft2,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = 1000000L
        )

        val cmd3DifferentActor = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = "user-2",
            transactionIdVal = txId,
            draftState = draft1,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = 1000000L
        )

        // Same inputs produce identical canonical fingerprint
        assertEquals(cmd1.requestFingerprint, cmd1Identical.requestFingerprint)

        // Altered inputs produce different fingerprint (detects idempotency conflicts)
        org.junit.Assert.assertNotEquals(cmd1.requestFingerprint, cmd2DifferentAmount.requestFingerprint)
        org.junit.Assert.assertNotEquals(cmd1.requestFingerprint, cmd3DifferentActor.requestFingerprint)
    }

    @Test
    fun testCashSalePayloadCodecRoundTrip() {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()
        val items = listOf(
            com.dailymoneyregister.feature.register.domain.DraftItem(
                id = "item-1",
                name = "Item Alpha",
                unitPrice = MoneyMinorUnits(1500L),
                quantity = 2,
                itemPackingCharge = MoneyMinorUnits(100L),
                itemShippingCharge = MoneyMinorUnits(200L),
                description = "Box 1"
            )
        )
        val draft = DraftState(
            items = items,
            taxRateBps = 500,
            isTaxEnabled = true,
            txShipping = MoneyMinorUnits(300L),
            txPacking = MoneyMinorUnits(150L)
        )

        val originalCmd = CashSaleCommand(
            operationId = opId,
            businessId = "biz-42",
            branchId = "branch-99",
            actorUserId = "cashier-007",
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = 1700000000000L
        )

        // Encode to JSON
        val encodedPayload = originalCmd.intentPayload

        // Decode back
        val decodedCmd = CashSalePayloadCodec.decode(encodedPayload)

        assertEquals(originalCmd.operationId, decodedCmd.operationId)
        assertEquals(originalCmd.businessId, decodedCmd.businessId)
        assertEquals(originalCmd.branchId, decodedCmd.branchId)
        assertEquals(originalCmd.actorUserId, decodedCmd.actorUserId)
        assertEquals(originalCmd.transactionIdVal, decodedCmd.transactionIdVal)
        assertEquals(originalCmd.currencyCode, decodedCmd.currencyCode)
        assertEquals(originalCmd.currencyDecimalPlaces, decodedCmd.currencyDecimalPlaces)
        assertEquals(originalCmd.occurredAt, decodedCmd.occurredAt)

        // Verify draft state fidelity
        assertEquals(draft.subtotal, decodedCmd.draftState.subtotal)
        assertEquals(draft.taxAmount, decodedCmd.draftState.taxAmount)
        assertEquals(draft.grandTotal, decodedCmd.draftState.grandTotal)
        assertEquals(draft.items.size, decodedCmd.draftState.items.size)
        assertEquals(draft.items[0].name, decodedCmd.draftState.items[0].name)
        assertEquals(draft.items[0].unitPrice, decodedCmd.draftState.items[0].unitPrice)
        assertEquals(draft.items[0].quantity, decodedCmd.draftState.items[0].quantity)
        assertEquals(draft.items[0].itemPackingCharge, decodedCmd.draftState.items[0].itemPackingCharge)
        assertEquals(draft.items[0].itemShippingCharge, decodedCmd.draftState.items[0].itemShippingCharge)
        assertEquals(draft.items[0].description, decodedCmd.draftState.items[0].description)

        // Verify canonical fingerprint matches after round trip
        assertEquals(originalCmd.requestFingerprint, decodedCmd.requestFingerprint)
    }
}
