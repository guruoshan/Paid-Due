package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.ExpenseDao
import com.dailymoneyregister.core.database.dao.FinancialSyncOutboxDao
import com.dailymoneyregister.core.database.dao.InboundEnvelopeAuditDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity
import com.dailymoneyregister.core.database.entity.InboundEnvelopeAuditEventEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
import org.junit.Test

class FinancialSyncEngineTest {
    @Before fun enableInboundForTests() { InboundApplicationGate.testOverride = true }
    @After fun resetInboundForTests() {
        InboundApplicationGate.testOverride = null
        SessionManager.clearSession()
    }

    private val db = mockk<DmrDatabase>()
    private val outboxDao = mockk<FinancialSyncOutboxDao>()
    private val operationDao = mockk<OperationDao>()
    private val transactionDao = mockk<TransactionDao>()
    private val expenseDao = mockk<ExpenseDao>()
    private val inboundAuditDao = mockk<InboundEnvelopeAuditDao>()
    private val openAuditRejections = mutableSetOf<String>()
    private val businessBranchDao = mockk<com.dailymoneyregister.core.database.dao.BusinessBranchDao>()
    private val userDao = mockk<com.dailymoneyregister.core.database.dao.UserDao>()
    private val remoteRepo = mockk<RemoteFinancialSyncRepository>()
    private val inboundApplier = FakeInboundEnvelopeApplier()

    private val engine = FinancialSyncEngine(db, remoteRepo, inboundApplier)

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
        every { db.inboundEnvelopeAuditDao() } returns inboundAuditDao
        coEvery { inboundAuditDao.hasRejection(any()) } coAnswers {
            firstArg<String>() in openAuditRejections
        }
        coEvery { inboundAuditDao.append(any()) } coAnswers {
            val event = firstArg<InboundEnvelopeAuditEventEntity>()
            when (event.eventType) {
                "REJECTED" -> openAuditRejections.add(event.operationId)
                "RECOVERED" -> openAuditRejections.remove(event.operationId)
            }
            Unit
        }
    }

    @Test
    fun syncOutboundUploadsQueuedItemAndDeletesOnAck() = runTest {
        coEvery { db.financialSyncOutboxDao() } returns outboxDao
        coEvery { db.operationDao() } returns operationDao
        coEvery { db.transactionDao() } returns transactionDao
        coEvery { db.expenseDao() } returns expenseDao
        coEvery { db.businessBranchDao() } returns businessBranchDao

        coEvery { outboxDao.classifyExistingRows() } returns 0
        coEvery { outboxDao.resetStaleInFlight() } returns 0
        coEvery { outboxDao.getPendingUpload(any()) } returns listOf(
            outboxItem,
            outboxItem.copy(
                operationId = "other-actor-op",
                transactionId = "other-actor-tx",
                actorUserId = "other-user"
            )
        )
        coEvery { outboxDao.getStatusCounts() } returns emptyList()
        coEvery { outboxDao.updateStatus(any(), any(), any()) } returns 1
        coEvery { outboxDao.deleteAcknowledged(any()) } returns 1
        coEvery { operationDao.getOperationById(opId) } returns operation
        coEvery { transactionDao.getTransactionById(txId) } returns transaction
        coEvery { transactionDao.getItemsForTransaction(txId) } returns emptyList()
        coEvery { transactionDao.getSettlementsForOperation(opId) } returns emptyList()
        coEvery { expenseDao.getExpenseByOperationId(opId) } returns null
        val branch = BranchEntity(
            id = "branch-1",
            businessId = "biz-1",
            name = "Main",
            code = "MAIN",
            countryCode = "AE",
            currencyCode = "AED",
            currencyDecimalPlaces = 2,
            currencySymbol = "AED",
            timezoneId = "Asia/Dubai",
            taxRoundingMode = com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { businessBranchDao.getBranchById("branch-1") } returns branch

        SessionManager.setSession(
            ActiveSession(
                business = BusinessEntity("biz-1", "Shop", 1L, 1L),
                branch = branch,
                user = UserAccountEntity(
                    id = "user-1",
                    businessId = "biz-1",
                    displayName = "Admin",
                    username = "admin",
                    role = UserRole.ADMIN,
                    authType = AuthType.PIN,
                    credentialHash = "test-hash",
                    credentialSalt = null,
                    createdAt = 1L,
                    updatedAt = 1L
                ),
                membership = UserBranchMembershipEntity(
                    id = "membership-1",
                    userId = "user-1",
                    branchId = "branch-1",
                    role = UserRole.ADMIN,
                    createdAt = 1L
                )
            )
        )

        coEvery { remoteRepo.uploadEnvelope(any(), any(), any(), any()) } answers {
            val env = firstArg<FinancialEnvelopeCodec.EncodedEnvelope>()
            FinancialSyncReceipt.Acknowledged(env.operationId, env.canonicalSha256)
        }

        val count = engine.syncOutbound("membership-1")

        assertEquals(1, count)
        coVerify { outboxDao.updateStatus(opId, "IN_FLIGHT") }
        coVerify(exactly = 1) { remoteRepo.uploadEnvelope(any(), "membership-1", "Asia/Dubai", any()) }
        coVerify(exactly = 0) { operationDao.getOperationById("other-actor-op") }
        coVerify { outboxDao.deleteAcknowledged(opId) }
    }

    @Test
    fun syncInboundFetchesAndProcessesRemoteEnvelopes() = runTest {
        coEvery { db.businessBranchDao() } returns businessBranchDao
        coEvery { db.userDao() } returns userDao
        coEvery { businessBranchDao.getBusinessById("biz-1") } returns mockk()
        coEvery { userDao.getUserByIdInBusiness("test-admin", "biz-1") } returns mockk()
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
            payloadJson = sampleJson,
            canonicalSha256 = com.dailymoneyregister.core.command.CommandFingerprint.sha256(sampleJson.toString())
        )

        coEvery { remoteRepo.fetchRemoteEnvelopesV2("biz-1", null, null, any()) } returns listOf(remoteEnvelope)
        inboundApplier.result = InboundProcessResult.Applied

        val count = engine.syncInbound("biz-1")

        assertEquals(1, count)
        coVerify { remoteRepo.fetchRemoteEnvelopesV2("biz-1", null, null, any()) }
        assertEquals(listOf(opId), inboundApplier.appliedOperationIds)
        coEvery {
            remoteRepo.fetchRemoteEnvelopesV2(
                "biz-1",
                "2026-10-09T09:59:56Z",
                opId,
                any()
            )
        } returns emptyList()
        assertEquals(0, engine.syncInbound("biz-1"))
        coVerify(exactly = 0) { db.financialSyncOutboxDao() }
    }

    @Test
    fun syncInboundRejectsMissingOrMismatchedRemoteDigestBeforeApplying() = runTest {
        coEvery { db.businessBranchDao() } returns businessBranchDao
        coEvery { db.userDao() } returns userDao
        coEvery { businessBranchDao.getBusinessById("biz-1") } returns mockk()
        coEvery { userDao.getUserByIdInBusiness("test-admin", "biz-1") } returns mockk()

        val payload = JSONObject().put("operation_id", opId).put("business_id", "biz-1")
        val remoteEnvelope = RemoteEnvelopeV2(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            operationType = "RECORD_SALE",
            occurredAtIso = "2026-10-09T10:00:00Z",
            receivedAtIso = "2026-10-09T10:00:01Z",
            canonicalText = payload.toString(),
            payloadJson = payload,
            canonicalSha256 = "0".repeat(64)
        )
        coEvery { remoteRepo.fetchRemoteEnvelopesV2("biz-1", null, null, any()) } returns listOf(remoteEnvelope)

        val count = engine.syncInbound("biz-1")

        assertEquals(0, count)
        assertEquals(emptyList<String>(), inboundApplier.appliedOperationIds)
    }

    @Test
    fun rejectedEnvelopeDoesNotBlockLaterValidEnvelope() = runTest {
        coEvery { db.businessBranchDao() } returns businessBranchDao
        coEvery { db.userDao() } returns userDao
        coEvery { businessBranchDao.getBusinessById("biz-1") } returns mockk()
        coEvery { userDao.getUserByIdInBusiness("test-admin", "biz-1") } returns mockk()

        val poisonId = "0199c002-1234-7abc-8def-0123456789ab"
        val validId = "0199c003-1234-7abc-8def-0123456789ab"
        fun envelope(id: String, receivedAt: String): RemoteEnvelopeV2 {
            val payload = JSONObject()
                .put("operation_id", id)
                .put("business_id", "biz-1")
            val canonical = payload.toString()
            return RemoteEnvelopeV2(
                operationId = id,
                businessId = "biz-1",
                branchId = "branch-1",
                operationType = "RECORD_SALE",
                occurredAtIso = "2026-10-09T10:00:00Z",
                receivedAtIso = receivedAt,
                canonicalText = canonical,
                payloadJson = payload,
                canonicalSha256 = com.dailymoneyregister.core.command.CommandFingerprint.sha256(canonical)
            )
        }
        val poison = envelope(poisonId, "2026-10-09T10:00:01Z")
        val valid = envelope(validId, "2026-10-09T10:00:02Z")
        coEvery { remoteRepo.fetchRemoteEnvelopesV2("biz-1", null, null, any()) } returns listOf(poison, valid)
        inboundApplier.resultFor = { row ->
            if (row.operationId == poisonId) {
                InboundProcessResult.Rejected("controlled poison fixture")
            } else {
                InboundProcessResult.Applied
            }
        }

        val applied = engine.syncInbound("biz-1")

        // Later good evidence applies in this pass, but the durable checkpoint must
        // remain before the first rejected envelope so that item can be retried.
        assertEquals(1, applied)
        assertEquals(listOf(poisonId, validId), inboundApplier.appliedOperationIds)

        val cursorField = FinancialSyncEngine::class.java.getDeclaredField("inMemoryCursors")
        cursorField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cursors = cursorField.get(engine) as Map<String, VersionedInboundCursor>
        assertEquals(0, cursors.size)

        // Simulate the defect being corrected on a subsequent run. The rejected sale
        // is retried, while the already-applied later operation is safely skipped.
        inboundApplier.resultFor = { row ->
            if (row.operationId == poisonId) InboundProcessResult.Applied
            else InboundProcessResult.Skipped
        }
        val recovered = engine.syncInbound("biz-1")
        assertEquals(1, recovered)
        assertEquals(listOf(poisonId, validId, poisonId, validId), inboundApplier.appliedOperationIds)
        assertEquals(validId, cursors.values.singleOrNull()?.operationId)
        coVerify(exactly = 1) {
            inboundAuditDao.append(match {
                it.operationId == poisonId && it.eventType == "REJECTED"
            })
        }
        coVerify(exactly = 1) {
            inboundAuditDao.append(match {
                it.operationId == poisonId && it.eventType == "RECOVERED"
            })
        }
    }

    @Test
    fun cursorV1IsInvalidatedSoOldRejectedEnvelopesAreRevisited() {
        assertEquals(
            null,
            InboundCursorCodec.decode("1|2026-10-09T10:00:01Z|previously-rejected-operation")
        )
    }

    @Test
    fun retryBackoffIsExponentialAndCapped() {
        assertEquals(15_000L, engine.retryDelayMillis(1))
        assertEquals(30_000L, engine.retryDelayMillis(2))
        assertEquals(900_000L, engine.retryDelayMillis(99))
    }

    private class FakeInboundEnvelopeApplier : InboundEnvelopeApplier {
        var result: InboundProcessResult = InboundProcessResult.Skipped
        var resultFor: (RemoteEnvelopeV2) -> InboundProcessResult = { result }
        val appliedOperationIds = mutableListOf<String>()

        override suspend fun apply(
            database: DmrDatabase,
            envelope: RemoteEnvelopeV2,
            sessionBusinessId: String,
            sessionBranchId: String?
        ): InboundProcessResult {
            appliedOperationIds += envelope.operationId
            return resultFor(envelope)
        }
    }
}
