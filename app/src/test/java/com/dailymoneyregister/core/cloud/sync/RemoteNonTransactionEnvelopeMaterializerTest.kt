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

class RemoteNonTransactionEnvelopeMaterializerTest : RemoteEnvelopeMaterializerTestSupport() {

    @Test
    fun preservesExpenseCashShiftAttributionFromAuthoritativeEnvelope() = runTest {
        val base = expenseEnvelope()
        val payload = JSONObject(base.canonicalText)
        val shiftId = "expense-attributed-shift"
        payload.put("cash_shift_id", shiftId)
        payload.getJSONObject("expense").put("cash_shift_id", shiftId)
        val envelope = envelopeFromPayload(base, "RECORD_EXPENSE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(shiftId, storedOperation?.cashShiftId)
    }

    @Test
    fun appliesCreditRepaymentInboundOnceAndSkipsReplay() = runTest {
        val envelope = creditRepaymentEnvelope()

        val first = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, first)
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        assertEquals("REPAYMENT", storedOperation?.resultReferenceType)
        assertEquals("repayment-evidence-id", storedOperation?.resultReferenceId)
        coVerify(exactly = 1) { ledgerDao.insertCreditRepayment(any()) }
        coVerify(exactly = 1) { ledgerDao.insertCreditEvent(any()) }
        coVerify(exactly = 1) { ledgerDao.insertCreditRepaymentAllocations(any()) }
        coVerify(exactly = 1) { transactionDao.insertSettlements(any()) }
    }

    @Test
    fun completesMatchingPendingCreditRepaymentWithAcknowledgedOutboxBeforeSuccess() = runTest {
        val envelope = creditRepaymentEnvelope()
        val payload = envelope.payloadJson
        val repaymentId = payload.optString("subject_id")
        storedOperation = OperationEntity(
            id = envelope.operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = OperationType.RECORD_CREDIT_REPAYMENT,
            subjectType = SubjectType.REPAYMENT,
            subjectId = repaymentId,
            transactionId = null,
            cashShiftId = null,
            actorUserId = actorUserId,
            deviceId = "cashier-device",
            occurredAt = payload.getLong("occurred_at"),
            requestFingerprint = fingerprint,
            commandSchemaVersion = 1,
            intentPayload = "{}",
            status = OperationStatus.PENDING
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        coVerifyOrder {
            outboxDao.insertOutbox(match {
                it.operationId == envelope.operationId &&
                    it.transactionId == repaymentId &&
                    it.status == "ACKNOWLEDGED"
            })
            operationDao.markSucceededIfPending(
                envelope.operationId, fingerprint, "REPAYMENT", repaymentId
            )
        }
    }

    @Test
    fun appliesCouponFundedCreditRepaymentWithRedemptionEvidence() = runTest {
        val envelope = couponCreditRepaymentEnvelope()

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(InboundProcessResult.Applied, result)
        coVerify(exactly = 1) { ledgerDao.insertCreditRepayment(any()) }
        coVerify(exactly = 1) { ledgerDao.insertCreditEvent(any()) }
        coVerify(exactly = 1) { ledgerDao.insertCouponWallet(any()) }
        coVerify(exactly = 1) { ledgerDao.insertCouponEvent(match { it.type.name == "REDEMPTION" }) }
        coVerify(exactly = 0) { outboxDao.insertOutbox(any()) }
    }

    @Test
    fun rejectsCreditRepaymentWithIncompleteLedgerEvidenceBeforeWrites() = runTest {
        val base = creditRepaymentEnvelope()
        val payload = JSONObject(base.canonicalText)
        payload.getJSONObject("financial_evidence").remove("credit_ledger_events")
        val envelope = envelopeFromPayload(
            base, "RECORD_CREDIT_REPAYMENT", payload, "credit-repayment-operation"
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { ledgerDao.insertCreditRepayment(any()) }
        coVerify(exactly = 0) { ledgerDao.insertCreditEvent(any()) }
        coVerify(exactly = 0) { transactionDao.insertSettlements(any()) }
    }

    @Test
    fun appliesCashShiftOpenWithSubjectIdentityAndNoCashShiftAttribution() = runTest {
        val envelope = cashShiftEnvelope("OPEN_CASH_SHIFT")
        val shift = CashShiftEntity(
            id = "inbound-shift-id",
            branchId = branchId,
            userId = actorUserId,
            createdAt = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
        )
        coEvery { cashShiftDao.getShiftById(shift.id) } returnsMany listOf(null, shift)

        val first = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, first)
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(null, storedOperation?.cashShiftId)
        assertEquals(SubjectType.CASH_SHIFT, storedOperation?.subjectType)
        coVerify(exactly = 1) { cashShiftDao.insertShift(shift) }
        coVerify(exactly = 1) {
            cashShiftDao.insertShiftEvent(match {
                it.shiftId == shift.id && it.operationId == "open-shift-operation"
            })
        }
        coVerify(exactly = 0) { outboxDao.insertOutbox(any()) }
    }

    @Test
    fun rejectsCashShiftSnapshotOwnedByAnotherBranch() = runTest {
        val envelope = cashShiftEnvelope("OPEN_CASH_SHIFT")
        val shift = CashShiftEntity(
            id = "inbound-shift-id",
            branchId = "another-branch",
            userId = actorUserId,
            createdAt = envelope.payloadJson.getJSONObject("financial_evidence").getJSONObject("cash_shift").getLong("created_at")
        )
        coEvery { cashShiftDao.getShiftById(shift.id) } returns shift

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { cashShiftDao.insertShiftEvent(any()) }
    }

    @Test
    fun appliesCashShiftCountWithSubjectAsAuthoritativeIdentity() = runTest {
        val envelope = cashShiftEnvelope("COUNT_CASH_SHIFT", occurredAtOffset = 1_000L)
        val shift = CashShiftEntity(
            id = "inbound-shift-id",
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        coEvery { cashShiftDao.getShiftById(shift.id) } returns shift
        coEvery { cashShiftDao.getEventsForShift(shift.id) } returns listOf(
            CashShiftEventEntity(
                id = "prior-open-event",
                shiftId = shift.id,
                operationId = "prior-open-operation",
                type = CashShiftEventType.OPENED,
                amountMinor = 0L,
                occurredAt = shift.createdAt,
                actorUserId = actorUserId
            )
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(null, storedOperation?.cashShiftId)
        coVerify(exactly = 1) {
            cashShiftDao.insertShiftEvent(match {
                it.shiftId == shift.id && it.type == CashShiftEventType.COUNTED
            })
        }
    }

    @Test
    fun appliesCashShiftCloseWithSubjectAsAuthoritativeIdentity() = runTest {
        val envelope = cashShiftEnvelope("CLOSE_CASH_SHIFT", occurredAtOffset = 2_000L)
        val shift = CashShiftEntity(
            id = "inbound-shift-id",
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        coEvery { cashShiftDao.getShiftById(shift.id) } returns shift
        coEvery { cashShiftDao.getEventsForShift(shift.id) } returns listOf(
            CashShiftEventEntity(
                id = "prior-open-event",
                shiftId = shift.id,
                operationId = "prior-open-operation",
                type = CashShiftEventType.OPENED,
                amountMinor = 0L,
                occurredAt = shift.createdAt,
                actorUserId = actorUserId
            ),
            CashShiftEventEntity(
                id = "prior-count-event",
                shiftId = shift.id,
                operationId = "prior-count-operation",
                type = CashShiftEventType.COUNTED,
                amountMinor = 1_000L,
                occurredAt = shift.createdAt + 1_000L,
                actorUserId = actorUserId
            )
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(null, storedOperation?.cashShiftId)
        coVerify(exactly = 1) {
            cashShiftDao.insertShiftEvent(match {
                it.shiftId == shift.id && it.type == CashShiftEventType.CLOSED
            })
        }
    }

    @Test
    fun rejectsCashShiftEventWhoseShiftIdDiffersFromEnvelopeSubject() = runTest {
        val envelope = cashShiftEnvelope(
            operationType = "OPEN_CASH_SHIFT",
            eventShiftId = "different-shift-id"
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { cashShiftDao.insertShift(any()) }
        coVerify(exactly = 0) { cashShiftDao.insertShiftEvent(any()) }
    }

    @Test
    fun appliesExpenseCorrectionOnlyForExactExpenseEventAndSettlement() = runTest {
        val expenseId = "lifecycle-expense-id"
        val expense = mockk<ExpenseEntity>()
        every { expense.businessId } returns businessId
        every { expense.branchId } returns branchId
        every { expense.amountMinor } returns 1_000L
        every { expense.paymentCategory } returns "CASH"
        every { expense.category } returns "OTHER"
        every { expense.payeeId } returns null
        coEvery { expenseDao.getExpenseById(expenseId) } returns expense
        coEvery { expenseDao.getEventsForExpense(expenseId) } returns emptyList()
        coEvery { expenseDao.insertExpenseEvent(any()) } returns Unit
        val envelope = expenseLifecycleEnvelope(
            operationType = "RECORD_EXPENSE_CORRECTION",
            eventType = "CORRECTED",
            signedSettlementMinor = 200L,
            previousAmount = 1_000L,
            newAmount = 800L
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(InboundProcessResult.Applied, result)
        coVerify(exactly = 1) {
            expenseDao.insertExpenseEvent(match {
                it.expenseId == expenseId &&
                    it.operationId == "expense-correction-operation" &&
                    it.eventType == "CORRECTED" &&
                    it.actorUserId == actorUserId
            })
        }
        coVerify(exactly = 1) { transactionDao.insertSettlements(any()) }
    }

    @Test
    fun appliesExpenseVoidWithExactVoidEventAndPositiveReversal() = runTest {
        val expenseId = "lifecycle-expense-id"
        val expense = mockk<ExpenseEntity>()
        every { expense.businessId } returns businessId
        every { expense.branchId } returns branchId
        every { expense.amountMinor } returns 1_000L
        every { expense.paymentCategory } returns "CASH"
        every { expense.category } returns "OTHER"
        every { expense.payeeId } returns null
        coEvery { expenseDao.getExpenseById(expenseId) } returns expense
        coEvery { expenseDao.getEventsForExpense(expenseId) } returns emptyList()
        coEvery { expenseDao.insertExpenseEvent(any()) } returns Unit
        val envelope = expenseLifecycleEnvelope(
            operationType = "RECORD_EXPENSE_VOID",
            eventType = "VOIDED",
            signedSettlementMinor = 1_000L,
            previousAmount = 1_000L,
            newAmount = null
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(InboundProcessResult.Applied, result)
        coVerify(exactly = 1) {
            expenseDao.insertExpenseEvent(match {
                it.expenseId == expenseId &&
                    it.operationId == "expense-void-operation" &&
                    it.eventType == "VOIDED"
            })
        }
        coVerify(exactly = 1) { transactionDao.insertSettlements(any()) }
    }

    @Test
    fun rejectsExpenseLifecycleEventWithWrongEventTypeBeforeWrites() = runTest {
        val base = expenseLifecycleEnvelope(
            operationType = "RECORD_EXPENSE_CORRECTION",
            eventType = "CORRECTED",
            signedSettlementMinor = 200L,
            previousAmount = 1_000L,
            newAmount = 800L
        )
        val payload = JSONObject(base.canonicalText)
        payload.getJSONObject("financial_evidence")
            .getJSONArray("expense_events").getJSONObject(0)
            .put("event_type", "VOIDED")
        val envelope = envelopeFromPayload(
            base, "RECORD_EXPENSE_CORRECTION", payload, "expense-correction-operation"
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { expenseDao.insertExpenseEvent(any()) }
    }

    @Test
    fun rejectsExpenseLifecycleEventForDifferentExpenseBeforeWrites() = runTest {
        val base = expenseLifecycleEnvelope(
            operationType = "RECORD_EXPENSE_CORRECTION",
            eventType = "CORRECTED",
            signedSettlementMinor = 200L,
            previousAmount = 1_000L,
            newAmount = 800L
        )
        val payload = JSONObject(base.canonicalText)
        payload.getJSONObject("financial_evidence")
            .getJSONArray("expense_events").getJSONObject(0)
            .put("expense_id", "another-expense-id")
        val envelope = envelopeFromPayload(
            base, "RECORD_EXPENSE_CORRECTION", payload, "expense-correction-operation"
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { expenseDao.insertExpenseEvent(any()) }
        coVerify(exactly = 0) { transactionDao.insertSettlements(any()) }
    }
}
