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

class RemoteEnvelopeTransactionMaterializerTest : RemoteEnvelopeMaterializerTestSupport() {

    @Test
    fun appliesSaleWithSyncedCashierIdentityWhenLocalUserRowIsAbsentAndReplayIsSkipped() = runTest {
        val envelope = cashSaleEnvelope()

        val first = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )
        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(InboundProcessResult.Applied, first)
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(operationId, storedOperation?.id)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        assertEquals(fingerprint, storedOperation?.requestFingerprint)
        assertEquals("TRANSACTION", storedOperation?.resultReferenceType)
        assertEquals(transactionId, storedOperation?.resultReferenceId)
        coVerify(exactly = 1) { operationDao.insertOperationIfAbsent(any()) }
        coVerify(exactly = 1) { transactionDao.insertTransaction(any()) }
        coVerify(exactly = 1) { transactionDao.insertSettlements(any()) }
        // The remote identity projection, not a locally installed cashier account,
        // is sufficient to validate the actor and replay the immutable sale.
        coVerify(exactly = 0) { userDao.getUserByIdInBusiness(actorUserId, businessId) }
    }

    @Test
    fun appliesPaymentMethodChangeWithSyncedCashierIdentityAndReclassificationEvidence() = runTest {
        val occurredAt = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
        coEvery { transactionDao.getTransactionById(transactionId) } returns TransactionEntity(
            id = transactionId,
            branchId = branchId,
            type = TransactionType.SALE,
            customerId = null,
            subtotalMinor = 1_000L,
            taxRateBps = 0,
            taxableBaseMinor = 1_000L,
            taxAmountMinor = 0L,
            txShippingMinor = 0L,
            txPackingMinor = 0L,
            grandTotalMinor = 1_000L,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            createdByUserId = actorUserId,
            deviceId = "cashier-device",
            createdAt = occurredAt,
            description = null,
            displayRef = "SALE-001"
        )
        val envelope = paymentMethodChangeEnvelope()

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        assertEquals("PAYMENT_METHOD_CHANGE_EVENT", storedOperation?.resultReferenceType)
        assertEquals("payment-change-event", storedOperation?.resultReferenceId)
        coVerify(exactly = 0) { transactionDao.insertTransaction(any()) }
        coVerify(exactly = 1) { transactionDao.insertSettlements(match { it.size == 2 }) }
        coVerify(exactly = 1) { transactionDao.insertPaymentMethodChange(any()) }
        coVerify(exactly = 0) { userDao.getUserByIdInBusiness(actorUserId, businessId) }
    }

    @Test
    fun appliesRefundWithEventSpecificOperationResultReference() = runTest {
        val refundOperationId = "refund-operation"
        val refundEventId = "refund-event"
        val occurredAt = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
        val evidence = JSONObject().put("refund_event", JSONObject().apply {
            put("id", refundEventId)
            put("transaction_id", transactionId)
            put("operation_id", refundOperationId)
            put("refund_amount_minor", 1_000L)
            put("reason", "Customer return")
            put("actor_user_id", actorUserId)
            put("occurred_at", occurredAt)
        })
        val envelope = transactionLifecycleEnvelope(
            operationType = "RECORD_REFUND",
            lifecycleOperationId = refundOperationId,
            signedSettlementMinor = -1_000L,
            entryPurpose = "REFUND_OUTFLOW",
            financialEvidence = evidence
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals("REFUND_EVENT", storedOperation?.resultReferenceType)
        assertEquals(refundEventId, storedOperation?.resultReferenceId)
        coVerify(exactly = 1) { transactionDao.insertRefund(match { it.id == refundEventId }) }
    }

    @Test
    fun appliesAmountCorrectionWithEventSpecificOperationResultReference() = runTest {
        val correctionOperationId = "correction-operation"
        val correctionEventId = "correction-event"
        val occurredAt = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
        val evidence = JSONObject().put(
            "amount_correction_events",
            JSONArray().put(JSONObject().apply {
                put("id", correctionEventId)
                put("transaction_id", transactionId)
                put("operation_id", correctionOperationId)
                put("previous_amount_minor", 1_000L)
                put("corrected_amount_minor", 1_200L)
                put("reason", "Corrected amount")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            })
        )
        val envelope = transactionLifecycleEnvelope(
            operationType = "RECORD_AMOUNT_CORRECTION",
            lifecycleOperationId = correctionOperationId,
            signedSettlementMinor = 200L,
            entryPurpose = "FUNDING_CORRECTION",
            financialEvidence = evidence
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals("AMOUNT_CORRECTION_EVENT", storedOperation?.resultReferenceType)
        assertEquals(correctionEventId, storedOperation?.resultReferenceId)
        coVerify(exactly = 1) {
            transactionDao.insertAmountCorrection(match { it.id == correctionEventId })
        }
    }

    @Test
    fun appliesServerAuthenticatedAdminSaleWhenAdminRowIsAbsentAndSkipsReplay() = runTest {
        val adminActorId = "admin-user-from-authoritative-envelope"
        val envelope = cashSaleEnvelope(
            actorId = adminActorId,
            serverActorMembershipId = "admin-membership-a",
            serverActorRole = "ADMIN"
        )

        val first = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )
        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(InboundProcessResult.Applied, first)
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(operationId, storedOperation?.id)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        coVerify(exactly = 1) { operationDao.insertOperationIfAbsent(any()) }
        coVerify(exactly = 1) { transactionDao.insertTransaction(any()) }
        coVerify(exactly = 1) { transactionDao.insertSettlements(any()) }
        coVerify(exactly = 0) { userDao.getUserByIdInBusiness(adminActorId, businessId) }
    }

    @Test
    fun removedSynchronizedIdentityFailsClosedBeforeFinancialWrites() = runTest {
        cloudIdentity = cloudIdentity.copy(isActive = false, isRemoved = true)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = cashSaleEnvelope(),
            sessionBusinessId = businessId,
            sessionBranchId = branchId
        )

        assertEquals(
            InboundProcessResult.Rejected(
                "Referenced operation actor is missing from synchronized staff identities"
            ),
            result
        )
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
        coVerify(exactly = 0) { transactionDao.insertTransaction(any()) }
    }

    @Test
    fun partialRefundThenVoidAcceptsOnlyTheRemainingReversibleAmount() = runTest {
        val priorRefund = RefundEventEntity(
            id = "prior-refund-event",
            transactionId = transactionId,
            operationId = "prior-refund-operation",
            refundAmountMinor = 900L,
            reason = "Partial return",
            actorUserId = actorUserId,
            occurredAt = Instant.parse("2026-10-09T10:00:01Z").toEpochMilli()
        )
        coEvery { transactionDao.getRefundEvents(transactionId) } returns listOf(priorRefund)

        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at") + 2_000L
        val voidOperationId = "void-operation-after-partial-refund"
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_id", voidOperationId)
            put("operation_type", "RECORD_VOID")
            put("occurred_at", occurredAt)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "void-residual-settlement")
                put("operation_id", voidOperationId)
                put("subject_type", SubjectType.TRANSACTION.name)
                put("subject_id", transactionId)
                put("transaction_id", transactionId)
                put("entry_purpose", "VOID_REVERSAL")
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", -100L)
                put("currency_code", "OMR")
                put("confirmation_type", ConfirmationType.CASHIER_CONFIRMED.name)
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject().put("void_event", JSONObject().apply {
                put("id", "void-event-after-partial-refund")
                put("transaction_id", transactionId)
                put("operation_id", voidOperationId)
                put("reason", "Cancel remaining amount")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
        }
        val envelope = envelopeFromPayload(base, "RECORD_VOID", payload, voidOperationId)
        storedOperation = OperationEntity(
            id = voidOperationId,
            businessId = businessId,
            branchId = branchId,
            operationType = OperationType.RECORD_VOID,
            subjectType = SubjectType.TRANSACTION,
            subjectId = transactionId,
            transactionId = transactionId,
            actorUserId = actorUserId,
            deviceId = "cashier-device",
            occurredAt = occurredAt,
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
        assertEquals("VOID_EVENT", storedOperation?.resultReferenceType)
        assertEquals("void-event-after-partial-refund", storedOperation?.resultReferenceId)
        coVerifyOrder {
            outboxDao.insertOutbox(match {
                it.operationId == voidOperationId &&
                    it.transactionId == transactionId &&
                    it.status == "ACKNOWLEDGED"
            })
            operationDao.markSucceededIfPending(
                voidOperationId,
                fingerprint,
                "VOID_EVENT",
                "void-event-after-partial-refund"
            )
        }
        coVerify(exactly = 1) { transactionDao.insertVoid(match { it.transactionId == transactionId }) }
    }

    @Test
    fun rejectsAmountCorrectionWhenPositiveDeltaOverflowsLong() = runTest {
        val correctionOperationId = "correction-positive-overflow"
        val evidence = JSONObject().put(
            "amount_correction_events",
            JSONArray().put(JSONObject().apply {
                put("id", "correction-positive-overflow-event")
                put("transaction_id", transactionId)
                put("operation_id", correctionOperationId)
                put("previous_amount_minor", Long.MIN_VALUE)
                put("corrected_amount_minor", Long.MAX_VALUE)
                put("actor_user_id", actorUserId)
                put("occurred_at", Instant.parse("2026-10-09T10:00:00Z").toEpochMilli())
            })
        )
        val envelope = transactionLifecycleEnvelope(
            operationType = "RECORD_AMOUNT_CORRECTION",
            lifecycleOperationId = correctionOperationId,
            signedSettlementMinor = 1L,
            entryPurpose = "FUNDING_CORRECTION",
            financialEvidence = evidence
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
    }

    @Test
    fun rejectsAmountCorrectionWhenNegativeDeltaOverflowsLong() = runTest {
        val correctionOperationId = "correction-negative-overflow"
        val evidence = JSONObject().put(
            "amount_correction_events",
            JSONArray().put(JSONObject().apply {
                put("id", "correction-negative-overflow-event")
                put("transaction_id", transactionId)
                put("operation_id", correctionOperationId)
                put("previous_amount_minor", Long.MAX_VALUE)
                put("corrected_amount_minor", Long.MIN_VALUE)
                put("actor_user_id", actorUserId)
                put("occurred_at", Instant.parse("2026-10-09T10:00:00Z").toEpochMilli())
            })
        )
        val envelope = transactionLifecycleEnvelope(
            operationType = "RECORD_AMOUNT_CORRECTION",
            lifecycleOperationId = correctionOperationId,
            signedSettlementMinor = -1L,
            entryPurpose = "FUNDING_CORRECTION",
            financialEvidence = evidence
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
    }

    @Test
    fun rejectsZeroSumPaymentMethodChangeWithWrongOldAndNewChannelEvidence() = runTest {
        val base = paymentMethodChangeEnvelope()
        val payload = JSONObject(base.canonicalText)
        val settlements = payload.getJSONArray("settlements")
        settlements.getJSONObject(0)
            .put("category", "ONLINE")
            .put("channel", "MANUAL_QR")
        settlements.getJSONObject(1)
            .put("category", "CASH")
            .put("channel", "CASH_TENDER")
        val envelope = envelopeFromPayload(base, "RECORD_PAYMENT_METHOD_CHANGE", payload)

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue("A zero-sum but incorrectly classified pair must be rejected", result is InboundProcessResult.Rejected)
        coVerify(exactly = 0) { operationDao.insertOperationIfAbsent(any()) }
        coVerify(exactly = 0) { transactionDao.insertSettlements(any()) }
    }

    @Test
    fun rejectsCouponPurchaseWithoutPurchaseLedgerEvidenceBeforeWrites() = runTest {
        val base = couponPurchaseEnvelope()
        val payload = JSONObject(base.canonicalText)
        payload.getJSONObject("financial_evidence").remove("coupon_ledger_events")
        val envelope = envelopeFromPayload(
            base, "RECORD_COUPON_PURCHASE", payload, "coupon-purchase-operation"
        )

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertTrue(result is InboundProcessResult.Rejected)
        assertEquals(null, storedOperation)
        coVerify(exactly = 0) { ledgerDao.insertCouponWallet(any()) }
        coVerify(exactly = 0) { ledgerDao.insertCouponEvent(any()) }
        coVerify(exactly = 0) { transactionDao.insertTransaction(any()) }
    }

    @Test
    fun appliesCouponPurchaseInboundOnceAndSkipsReplay() = runTest {
        val envelope = couponPurchaseEnvelope()

        val first = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )
        val replay = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, first)
        assertEquals(InboundProcessResult.Skipped, replay)
        assertEquals(OperationStatus.SUCCEEDED, storedOperation?.status)
        coVerify(exactly = 1) { ledgerDao.insertCouponWallet(any()) }
        coVerify(exactly = 1) { ledgerDao.insertCouponEvent(any()) }
        coVerify(exactly = 1) { transactionDao.insertTransaction(any()) }
        coVerify(exactly = 1) { transactionDao.insertSettlements(any()) }
    }
}
