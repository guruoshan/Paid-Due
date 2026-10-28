package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialEnvelopeCodecTest {

    private val opId = "0199c001-1234-7abc-8def-0123456789ab"
    private val txId = "tx-0199c001-1234-7abc-8def-0123456789ab"

    private val operation = OperationEntity(
        id = opId,
        businessId = "biz-1",
        branchId = "branch-1",
        operationType = OperationType.RECORD_SALE,
        subjectType = SubjectType.TRANSACTION,
        subjectId = txId,
        transactionId = txId,
        actorUserId = "user-1",
        deviceId = "device-1",
        occurredAt = 1000L,
        requestFingerprint = "fingerprint-1",
        commandSchemaVersion = 1,
        intentPayload = "{}",
        status = OperationStatus.SUCCEEDED
    )

    private val transaction = TransactionEntity(
        id = txId,
        branchId = "branch-1",
        type = TransactionType.SALE,
        subtotalMinor = 1000L,
        taxRateBps = 0,
        taxableBaseMinor = 1000L,
        taxAmountMinor = 0L,
        grandTotalMinor = 1000L,
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        createdByUserId = "user-1",
        deviceId = "device-1",
        createdAt = 1000L,
        displayRef = "REF-1"
    )

    private val settlement = SettlementEntryEntity(
        id = "set-1",
        operationId = opId,
        subjectType = SubjectType.TRANSACTION,
        subjectId = txId,
        transactionId = txId,
        entryPurpose = EntryPurpose.SALE_FUNDING,
        category = PaymentCategory.CASH,
        channel = PaymentChannel.CASH_TENDER,
        amountSignedMinor = 1000L,
        currencyCode = "OMR",
        confirmationType = ConfirmationType.ADMIN_CONFIRMED,
        occurredAt = 1000L,
        actorUserId = "user-1"
    )

    @Test
    fun encodeProducesValidCanonicalDigestAndPayload() {
        val encoded = FinancialEnvelopeCodec.encode(
            operation = operation,
            transaction = transaction,
            settlements = listOf(settlement)
        )

        assertEquals(opId, encoded.operationId)
        assertEquals("biz-1", encoded.businessId)
        assertEquals("branch-1", encoded.branchId)
        assertEquals("RECORD_SALE", encoded.operationType)
        assertEquals(64, encoded.canonicalSha256.length)

        val json = encoded.jsonPayload
        assertEquals(opId, json.getString("operation_id"))
        assertNotNull(json.getJSONObject("transaction"))
        assertEquals(1, json.getJSONArray("settlements").length())
    }

    @Test
    fun encodeExpenseProducesValidEnvelope() {
        val expenseOp = operation.copy(
            id = "op-expense",
            operationType = OperationType.RECORD_EXPENSE,
            transactionId = null,
            subjectId = "exp-1",
            subjectType = SubjectType.EXPENSE
        )

        val expense = ExpenseEntity(
            id = "exp-1",
            operationId = "op-expense",
            businessId = "biz-1",
            branchId = "branch-1",
            cashShiftId = null,
            actorUserId = "user-1",
            payeeId = null,
            payeeNameSnapshot = "Supplier",
            payeePhoneSnapshot = null,
            payeeAccountSnapshot = null,
            category = "Office Supplies",
            amountMinor = 500L,
            paymentCategory = "CASH",
            currencyCode = "OMR",
            reference = "INV-001",
            note = "Paper",
            recurrenceId = null,
            occurredAt = 1000L,
            createdAt = 1000L
        )

        val encoded = FinancialEnvelopeCodec.encode(
            operation = expenseOp,
            expense = expense
        )

        assertEquals("RECORD_EXPENSE", encoded.operationType)
        assertNotNull(encoded.jsonPayload.getJSONObject("expense"))
        assertEquals(500L, encoded.jsonPayload.getJSONObject("expense").getLong("amount_minor"))
    }
}
