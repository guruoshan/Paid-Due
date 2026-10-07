package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

        // Encode to the custom durable key=value envelope
        val encodedPayload = originalCmd.intentPayload
        assertTrue(encodedPayload.startsWith("v=4\n"))

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

    @Test
    fun testCashSalePayloadCodecPreservesEscapedAndControlCharacters() {
        val value = "slash \\; literal \\n; literal \\r; newline \n carriage \r"
        val item = com.dailymoneyregister.feature.register.domain.DraftItem(
            id = "item-escaped",
            name = value,
            unitPrice = MoneyMinorUnits(1500L),
            quantity = 1,
            itemPackingCharge = MoneyMinorUnits.ZERO,
            itemShippingCharge = MoneyMinorUnits.ZERO,
            description = value,
            barcode = value
        )
        val original = CashSaleCommand(
            operationId = OperationId.generate(),
            businessId = "biz-escaped",
            branchId = "branch-escaped",
            actorUserId = "cashier-escaped",
            transactionIdVal = TransactionId.generate(),
            draftState = DraftState(
                items = listOf(item),
                manualAmount = MoneyMinorUnits(1500L),
                manualDescription = value
            ),
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = 1700000000000L
        )

        val decoded = CashSalePayloadCodec.decode(original.intentPayload)
        assertEquals(value, decoded.draftState.manualDescription)
        assertEquals(value, decoded.draftState.items.single().name)
        assertEquals(value, decoded.draftState.items.single().description)
        assertEquals(value, decoded.draftState.items.single().barcode)
    }

    @Test
    fun testHistoricalV2PayloadUsesLegacyUnescapeSemantics() {
        val payload = buildString {
            appendLine("v=2")
            appendLine("commandType=CASH_SALE")
            appendLine("operationId=op-v2")
            appendLine("businessId=biz-v2")
            appendLine("branchId=branch-v2")
            appendLine("actorUserId=user-v2")
            appendLine("transactionId=tx-v2")
            appendLine("currencyCode=OMR")
            appendLine("currencyDecimalPlaces=3")
            appendLine("deviceId=device-v2")
            appendLine("occurredAt=1700000000000")
            appendLine("manualAmount=1500")
            appendLine("manualDescription=literal\\r")
            appendLine("taxRateBps=0")
            appendLine("isTaxEnabled=false")
            appendLine("taxRoundingMode=HALF_UP")
            appendLine("txShipping=0")
            appendLine("txPacking=0")
            appendLine("itemCount=1")
            appendLine("item[0].id=item-v2")
            appendLine("item[0].name=two\\\\backslashes")
            appendLine("item[0].desc=historical\\nnewline")
            appendLine("item[0].unitPrice=1500")
            appendLine("item[0].qty=1")
            appendLine("item[0].barcode=barcode-v2")
            appendLine("item[0].itemPacking=0")
            appendLine("item[0].itemShipping=0")
        }

        val decoded = CashSalePayloadCodec.decode(payload)
        assertEquals("literal\\r", decoded.draftState.manualDescription)
        assertEquals("two\\\\backslashes", decoded.draftState.items.single().name)
        assertEquals("historical\nnewline", decoded.draftState.items.single().description)

        val legacyV1 = payload
            .replace("v=2\n", "v=1\n")
            .replace("commandType=CASH_SALE\n", "")
        val decodedV1 = CashSalePayloadCodec.decode(legacyV1)
        assertEquals("literal\\r", decodedV1.draftState.manualDescription)
        assertEquals("two\\\\backslashes", decodedV1.draftState.items.single().name)
        assertEquals("historical\nnewline", decodedV1.draftState.items.single().description)
    }
}
