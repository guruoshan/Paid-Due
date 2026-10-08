package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.ExpenseDao
import com.dailymoneyregister.core.database.dao.FinancialSyncOutboxDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FinancialSyncEngineTest {

    private val db = mockk<DmrDatabase>(relaxed = true)
    private val outboxDao = mockk<FinancialSyncOutboxDao>(relaxed = true)
    private val operationDao = mockk<OperationDao>(relaxed = true)
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val remoteRepo = mockk<RemoteFinancialSyncRepository>(relaxed = true)

    private val engine = FinancialSyncEngine(db, remoteRepo)

    private val opId = "0199c001-1234-7abc-8def-0123456789ab"
    private val txId = "tx-0199c001-1234-7abc-8def-0123456789ab"

    private val outboxItem = FinancialSyncOutboxEntity(
        operationId = opId,
        businessId = "biz-1",
        branchId = "branch-1",
        transactionId = txId,
        actorUserId = "user-1",
        deviceId = "device-1",
        requestFingerprint = "fingerprint-1",
        commandSchemaVersion = 1,
        occurredAt = 1000L,
        status = "QUEUED",
        attemptCount = 0
    )

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

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
    }

    @Test
    fun syncOutboundUploadsQueuedItemAndDeletesOnAck() = runTest {
        coEvery { db.financialSyncOutboxDao() } returns outboxDao
        coEvery { db.operationDao() } returns operationDao
        coEvery { db.transactionDao() } returns transactionDao
        coEvery { db.expenseDao() } returns expenseDao

        coEvery { outboxDao.getPendingUpload(any()) } returns listOf(outboxItem)
        coEvery { operationDao.getOperationById(opId) } returns operation
        coEvery { transactionDao.getTransactionById(txId) } returns transaction
        coEvery { transactionDao.getItemsForTransaction(txId) } returns emptyList()
        coEvery { transactionDao.getSettlementsForOperation(opId) } returns emptyList()
        coEvery { expenseDao.getExpenseByOperationId(opId) } returns null

        coEvery { remoteRepo.uploadEnvelope(any(), any(), any(), any()) } answers {
            val env = firstArg<FinancialEnvelopeCodec.EncodedEnvelope>()
            FinancialSyncReceipt.Acknowledged(env.operationId, env.canonicalSha256)
        }

        val count = engine.syncOutbound("membership-1")

        assertEquals(1, count)
        coVerify { outboxDao.updateStatus(opId, "IN_FLIGHT") }
        coVerify { remoteRepo.uploadEnvelope(any(), "membership-1", any(), any()) }
        coVerify { outboxDao.deleteAcknowledged(opId) }
    }

    @Test
    fun syncInboundFetchesAndProcessesRemoteEnvelopes() = runTest {
        val sampleJson = JSONObject().apply {
            put("operation_id", opId)
            put("business_id", "biz-1")
            put("branch_id", "branch-1")
            put("actor_user_id", "user-1")
            put("device_id", "device-1")
            put("operation_type", "RECORD_SALE")
            put("occurred_at", 1000L)
            put("command_schema_version", 1)
            put("request_fingerprint", "fingerprint-1")
            put("intent_payload", "{}")
            put("transaction", JSONObject().apply {
                put("id", txId)
                put("branch_id", "branch-1")
                put("type", "SALE")
                put("subtotal_minor", 1000L)
                put("tax_rate_bps", 0)
                put("taxable_base_minor", 1000L)
                put("tax_amount_minor", 0L)
                put("grand_total_minor", 1000L)
                put("currency_code", "OMR")
                put("currency_decimal_places", 3)
                put("created_by_user_id", "user-1")
                put("device_id", "device-1")
                put("created_at", 1000L)
                put("display_ref", "REF-1")
            })
            put("settlements", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "s-1")
                    put("operation_id", opId)
                    put("subject_type", "TRANSACTION")
                    put("subject_id", txId)
                    put("entry_purpose", "SALE_FUNDING")
                    put("category", "CASH")
                    put("channel", "CASH_TENDER")
                    put("amount_signed_minor", 1000L)
                    put("currency_code", "OMR")
                    put("confirmation_type", "CASHIER_CONFIRMED")
                })
            })
        }

        val remoteEnvelope = RemoteEnvelopeV2(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            operationType = "RECORD_SALE",
            occurredAtIso = "2026-10-09T10:00:00Z",
            receivedAtIso = "2026-10-09T10:00:01Z",
            canonicalText = sampleJson.toString(),
            payloadJson = sampleJson
        )

        coEvery { db.operationDao() } returns operationDao
        coEvery { db.transactionDao() } returns transactionDao
        coEvery { db.expenseDao() } returns expenseDao
        coEvery { remoteRepo.fetchRemoteEnvelopesV2("biz-1", null, null, any()) } returns listOf(remoteEnvelope)
        coEvery { operationDao.getOperationById(opId) } returns null // Not existing locally yet

        val count = engine.syncInbound("biz-1")

        assertEquals(1, count)
        coVerify { remoteRepo.fetchRemoteEnvelopesV2("biz-1", null, null, any()) }
    }
}
