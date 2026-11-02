package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CashShiftDao
import com.dailymoneyregister.core.database.dao.CloudStaffIdentityDao
import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.dao.LedgerDao
import com.dailymoneyregister.core.database.dao.ExpenseDao
import com.dailymoneyregister.core.database.dao.FinancialSyncOutboxDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.command.CommandFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RemoteExpenseEnvelopeMaterializerTest : RemoteEnvelopeMaterializerTestSupport() {

    @Test
    fun appliesExpenseWithSyncedCashierIdentityWhenLocalUserRowIsAbsent() = runTest {
        val shiftId = "expense-default-shift"
        val envelope = expenseEnvelopeWithCashShift(shiftId)
        coEvery { cashShiftDao.getShiftById(shiftId) } returns CashShiftEntity(
            id = shiftId,
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        coEvery { operationDao.markSucceededIfPending(any(), any(), any(), any()) } coAnswers {
            storedOperation = storedOperation?.copy(status = OperationStatus.SUCCEEDED)
            1
        }

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        assertEquals(null, storedOperation?.transactionId)
        coVerify(exactly = 1) { expenseDao.insertExpense(any()) }
        coVerify(exactly = 1) { operationDao.insertOperationIfAbsent(any()) }
        coVerifyOrder {
            operationDao.insertOperationIfAbsent(any())
            transactionDao.insertSettlements(any())
        }
        coVerify(exactly = 0) { operationDao.markSucceededIfPending(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            transactionDao.insertSettlements(match { settlements ->
                settlements.size == 1 &&
                    settlements.single().entryPurpose.name == "EXPENSE_OUTFLOW" &&
                    settlements.single().amountSignedMinor == -1_000L &&
                    settlements.single().transactionId == null
            })
        }
        coVerify(exactly = 0) { userDao.getUserByIdInBusiness(actorUserId, businessId) }
        coVerify(exactly = 0) { transactionDao.insertTransaction(any()) }
    }

    @Test
    fun rejectsExpenseWithoutAuthoritativeOutflowSettlement() = runTest {
        val shiftId = "empty-settlement-shift"
        val base = expenseEnvelopeWithCashShift(shiftId)
        coEvery { cashShiftDao.getShiftById(shiftId) } returns CashShiftEntity(
            id = shiftId,
            branchId = branchId,
            userId = actorUserId,
            createdAt = base.payloadJson.getLong("occurred_at")
        )
        val payload = JSONObject(base.canonicalText).put("settlements", JSONArray())
        val envelope = envelopeFromPayload(base, "RECORD_EXPENSE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertTrue("Expense without outflow evidence must be rejected", result is InboundProcessResult.Rejected)
        coVerify(exactly = 0) { expenseDao.insertExpense(any()) }
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
    }

    @Test
    fun completesMatchingPendingExpenseOperationAfterEvidenceIsMaterialized() = runTest {
        val shiftId = "expense-default-shift"
        val envelope = expenseEnvelopeWithCashShift(shiftId)
        coEvery { cashShiftDao.getShiftById(shiftId) } returns CashShiftEntity(
            id = shiftId,
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        val payload = envelope.payloadJson
        val expenseId = payload.getJSONObject("expense").getString("id")
        storedOperation = OperationEntity(
            id = operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = OperationType.RECORD_EXPENSE,
            subjectType = SubjectType.EXPENSE,
            subjectId = expenseId,
            transactionId = null,
            actorUserId = actorUserId,
            deviceId = "cashier-device",
            occurredAt = payload.getLong("occurred_at"),
            requestFingerprint = fingerprint,
            commandSchemaVersion = 1,
            intentPayload = "{}",
            status = OperationStatus.PENDING
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        coVerifyOrder {
            outboxDao.insertOutbox(match {
                it.operationId == operationId &&
                    it.transactionId == expenseId &&
                    it.status == "ACKNOWLEDGED"
            })
            operationDao.markSucceededIfPending(
                operationId,
                fingerprint,
                "EXPENSE",
                expenseId
            )
        }
    }

    @Test
    fun rejectsExpenseWhenCashShiftIdIsMissingBeforeAnyFinancialWrites() = runTest {
        val base = expenseEnvelope()
        val payload = JSONObject(base.canonicalText).apply {
            remove("cash_shift_id")
            getJSONObject("expense").remove("cash_shift_id")
        }
        val envelope = envelopeFromPayload(base, "RECORD_EXPENSE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertNoExpenseFinancialWrites()
    }

    @Test
    fun rejectsExpenseWhenCashShiftIdIsBlankBeforeAnyFinancialWrites() = runTest {
        val base = expenseEnvelope()
        val payload = JSONObject(base.canonicalText).apply {
            put("cash_shift_id", " ")
            getJSONObject("expense").put("cash_shift_id", " ")
        }
        val envelope = envelopeFromPayload(base, "RECORD_EXPENSE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertNoExpenseFinancialWrites()
    }

    @Test
    fun rejectsExpenseWithUnknownCategoryBeforeAnyFinancialWrites() = runTest {
        val base = expenseEnvelopeWithCashShift("category-test-shift")
        val payload = JSONObject(base.canonicalText)
        payload.getJSONObject("expense").put("category", "UNSUPPORTED_CATEGORY")
        val envelope = envelopeFromPayload(base, "RECORD_EXPENSE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertNoExpenseFinancialWrites()
    }

    @Test
    fun rejectsExpenseWithMissingCashShiftBeforeAnyFinancialWrites() = runTest {
        val envelope = expenseEnvelopeWithCashShift("missing-shift")

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertNoExpenseFinancialWrites()
    }

    @Test
    fun rejectsExpenseWithCashShiftFromAnotherBranchBeforeAnyFinancialWrites() = runTest {
        val envelope = expenseEnvelopeWithCashShift("cross-branch-shift")
        coEvery { cashShiftDao.getShiftById("cross-branch-shift") } returns CashShiftEntity(
            id = "cross-branch-shift",
            branchId = "another-branch",
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertNoExpenseFinancialWrites()
    }

    @Test
    fun rejectsExpenseWithCashShiftOwnedByAnotherActorBeforeAnyFinancialWrites() = runTest {
        val envelope = expenseEnvelopeWithCashShift("wrong-actor-shift")
        coEvery { cashShiftDao.getShiftById("wrong-actor-shift") } returns CashShiftEntity(
            id = "wrong-actor-shift",
            branchId = branchId,
            userId = "another-actor",
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertNoExpenseFinancialWrites()
    }

    @Test
    fun preservesExpenseCashShiftAttributionWhenShiftBranchAndActorMatch() = runTest {
        val shiftId = "expense-attributed-shift"
        val envelope = expenseEnvelopeWithCashShift(shiftId)
        val shift = CashShiftEntity(
            id = shiftId,
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        coEvery { cashShiftDao.getShiftById(shiftId) } returns shift

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(shiftId, storedOperation?.cashShiftId)
        coVerify(exactly = 1) { expenseDao.insertExpense(match { it.cashShiftId == shiftId }) }
        coVerify(exactly = 1) {
            transactionDao.insertSettlements(match { it.single().subjectId == "0199c104-1234-7abc-8def-0123456789ab" })
        }
    }

    private fun expenseEnvelopeWithCashShift(shiftId: String): RemoteEnvelopeV2 {
        val base = expenseEnvelope()
        val payload = JSONObject(base.canonicalText)
        payload.put("cash_shift_id", shiftId)
        payload.getJSONObject("expense").put("cash_shift_id", shiftId)
        return envelopeFromPayload(base, "RECORD_EXPENSE", payload)
    }

    private fun assertNoExpenseFinancialWrites() {
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
        coVerify(exactly = 0) { expenseDao.insertExpense(any()) }
        coVerify(exactly = 0) { transactionDao.insertSettlements(any()) }
        coVerify(exactly = 0) { outboxDao.insertOutbox(any()) }
    }

    @Test
    fun rejectsExpenseWhenOutflowSettlementDoesNotMatchSnapshot() = runTest {
        val shiftId = "mismatched-settlement-shift"
        val base = expenseEnvelopeWithCashShift(shiftId)
        coEvery { cashShiftDao.getShiftById(shiftId) } returns CashShiftEntity(
            id = shiftId,
            branchId = branchId,
            userId = actorUserId,
            createdAt = base.payloadJson.getLong("occurred_at")
        )
        val payload = JSONObject(base.canonicalText)
        payload.getJSONArray("settlements").getJSONObject(0)
            .put("amount_signed_minor", 1_000L)
        val envelope = envelopeFromPayload(base, "RECORD_EXPENSE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertTrue("Mismatched expense settlement must be rejected", result is InboundProcessResult.Rejected)
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
        coVerify(exactly = 0) { expenseDao.insertExpense(any()) }
    }
}
