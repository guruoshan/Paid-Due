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
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
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

class RemoteEnvelopeMaterializerTest {
    private val database = mockk<DmrDatabase>()
    private val branchDao = mockk<BusinessBranchDao>()
    private val cashShiftDao = mockk<CashShiftDao>()
    private val identityDao = mockk<CloudStaffIdentityDao>()
    private val customerDao = mockk<CustomerDao>()
    private val ledgerDao = mockk<LedgerDao>()
    private val customer = mockk<CustomerEntity>()
    private val operationDao = mockk<OperationDao>()
    private val expenseDao = mockk<ExpenseDao>()
    private val outboxDao = mockk<FinancialSyncOutboxDao>()
    private val transactionDao = mockk<TransactionDao>()
    private val userDao = mockk<UserDao>()

    private val businessId = "business-a"
    private val branchId = "branch-a"
    private val actorUserId = "cashier-user-a"
    private val transactionId = "0199c102-1234-7abc-8def-0123456789ab"
    private val operationId = "0199c101-1234-7abc-8def-0123456789ab"
    private val fingerprint = "cash-sale-request-fingerprint"
    private var storedOperation: OperationEntity? = null
    private lateinit var cloudIdentity: CloudStaffIdentityEntity

    @Before
    fun setUp() {
        every { database.businessBranchDao() } returns branchDao
        every { database.cashShiftDao() } returns cashShiftDao
        every { database.cloudStaffIdentityDao() } returns identityDao
        every { database.customerDao() } returns customerDao
        every { database.ledgerDao() } returns ledgerDao
        every { customer.businessId } returns businessId
        every { database.operationDao() } returns operationDao
        every { database.expenseDao() } returns expenseDao
        every { database.financialSyncOutboxDao() } returns outboxDao
        every { database.transactionDao() } returns transactionDao
        every { database.userDao() } returns userDao
        val branch = BranchEntity(
            id = branchId,
            businessId = businessId,
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { branchDao.getBranchById(branchId) } returns branch
        coEvery { cashShiftDao.getEventByOperationId(any()) } returns null
        coEvery { cashShiftDao.insertShift(any()) } returns Unit
        coEvery { cashShiftDao.insertShiftEvent(any()) } returns Unit
        cloudIdentity = CloudStaffIdentityEntity(
                membershipId = "membership-a",
                localUserId = actorUserId,
                businessId = businessId,
                branchId = branchId,
                role = "CASHIER",
                permissionsJson = "{}",
                isActive = true,
                isRemoved = false,
                updatedAt = 1L
            )
        coEvery { identityDao.findIdentity(actorUserId, businessId, branchId) } coAnswers { cloudIdentity }
        coEvery { userDao.getUserByIdInBusiness(actorUserId, businessId) } returns null
        coEvery { customerDao.getCustomerById(any()) } returns customer
        coEvery { ledgerDao.getCreditRepaymentById(any()) } returns null
        coEvery { ledgerDao.getCreditRepaymentsForOperation(any()) } returns emptyList()
        coEvery { ledgerDao.getCreditRepaymentAllocations(any()) } returns emptyList()
        coEvery { ledgerDao.getCreditEventsForOperations(any()) } returns emptyList()
        coEvery { ledgerDao.insertCreditRepayment(any()) } returns Unit
        coEvery { ledgerDao.insertCreditRepaymentAllocations(any()) } returns Unit
        coEvery { ledgerDao.insertCreditEvent(any()) } returns Unit
        coEvery { ledgerDao.getCouponWalletById(any()) } returns null
        coEvery { ledgerDao.getCouponEventsForOperation(any()) } returns emptyList()
        coEvery { ledgerDao.insertCouponWallet(any()) } returns Unit
        coEvery { ledgerDao.insertCouponEvent(any()) } returns Unit
        coEvery { operationDao.getOperationById(any()) } coAnswers { storedOperation }
        coEvery { operationDao.insertOperationIfAbsent(any()) } coAnswers {
            if (storedOperation == null) {
                storedOperation = firstArg<OperationEntity>()
                1L
            } else {
                0L
            }
        }
        coEvery { operationDao.markSucceededIfPending(any(), any(), any(), any()) } coAnswers {
            val current = storedOperation
            val operationIdArg = firstArg<String>()
            val fingerprintArg = secondArg<String>()
            if (
                current != null &&
                current.id == operationIdArg &&
                current.status == OperationStatus.PENDING &&
                current.requestFingerprint == fingerprintArg
            ) {
                storedOperation = current.copy(
                    status = OperationStatus.SUCCEEDED,
                    resultReferenceType = thirdArg<String>(),
                    resultReferenceId = args[3] as String,
                    reason = null
                )
                1
            } else {
                0
            }
        }
        coEvery { operationDao.isOperationSucceeded(any()) } answers { storedOperation?.status == OperationStatus.SUCCEEDED }
        coEvery { transactionDao.getTransactionById(transactionId) } returns null
        coEvery { transactionDao.insertTransaction(any()) } returns Unit
        coEvery { transactionDao.getItemsForTransaction(transactionId) } returns emptyList()
        coEvery { transactionDao.getSettlementsForOperation(any()) } returns emptyList()
        coEvery { transactionDao.insertSettlements(any()) } returns Unit
        coEvery { transactionDao.getPaymentMethodChanges(transactionId) } returns emptyList()
        coEvery { transactionDao.insertPaymentMethodChange(any()) } returns Unit
        coEvery { transactionDao.getRefundEvents(any()) } returns emptyList()
        coEvery { transactionDao.getRefundEventByOperationId(any()) } returns null
        coEvery { transactionDao.insertRefund(any()) } returns Unit
        coEvery { transactionDao.getAmountCorrections(any()) } returns emptyList()
        coEvery { transactionDao.getAmountCorrectionsByOperationId(any()) } returns emptyList()
        coEvery { transactionDao.insertAmountCorrection(any()) } returns Unit
        coEvery { transactionDao.getVoidEvent(any()) } returns null
        coEvery { transactionDao.getVoidEventByOperationId(any()) } returns null
        coEvery { transactionDao.insertVoid(any()) } returns Unit
        coEvery { expenseDao.getExpenseById(any()) } returns null
        coEvery { expenseDao.insertExpense(any()) } returns Unit
        coEvery { outboxDao.findByOperationId(any()) } returns null
        coEvery { outboxDao.insertOutbox(any()) } returns 1L

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            // The generated static call receives the RoomDatabase receiver first
            // and the transaction lambda second.
            secondArg<suspend () -> Any>().invoke()
        }
    }

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
    fun appliesExpenseWithSyncedCashierIdentityWhenLocalUserRowIsAbsent() = runTest {
        val envelope = expenseEnvelope()
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
        val base = expenseEnvelope()
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
        val envelope = expenseEnvelope()
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
    fun rejectsExpenseWhenOutflowSettlementDoesNotMatchSnapshot() = runTest {
        val base = expenseEnvelope()
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

    private fun creditRepaymentEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val repaymentId = "repayment-evidence-id"
        val repaymentOperationId = "credit-repayment-operation"
        val customerId = "credit-customer-id"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_type", "RECORD_CREDIT_REPAYMENT")
            put("subject_type", SubjectType.REPAYMENT.name)
            put("subject_id", repaymentId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "credit-repayment-collection")
                put("operation_id", repaymentOperationId)
                put("subject_type", SubjectType.REPAYMENT.name)
                put("subject_id", repaymentId)
                put("entry_purpose", "CREDIT_REPAYMENT_COLLECTION")
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", 1_000L)
                put("currency_code", "OMR")
                put("confirmation_type", "CASHIER_CONFIRMED")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject().apply {
                put("credit_repayment", JSONObject().apply {
                    put("id", repaymentId)
                    put("operation_id", repaymentOperationId)
                    put("customer_id", customerId)
                    put("amount_minor", 1_000L)
                    put("currency_code", "OMR")
                    put("payment_category", "CASH")
                    put("occurred_at", occurredAt)
                    put("actor_user_id", actorUserId)
                })
                put("credit_ledger_events", JSONArray().put(JSONObject().apply {
                    put("id", "credit-repayment-ledger-event")
                    put("operation_id", repaymentOperationId)
                    put("customer_id", customerId)
                    put("type", "REPAYMENT")
                    put("amount_signed_minor", -1_000L)
                    put("currency_code", "OMR")
                    put("repayment_id", repaymentId)
                    put("occurred_at", occurredAt)
                }))
                put("credit_repayment_allocations", JSONArray().put(JSONObject().apply {
                    put("id", "credit-repayment-allocation")
                    put("repayment_id", repaymentId)
                    put("original_transaction_id", "original-credit-transaction")
                    put("allocated_amount_minor", 1_000L)
                    put("occurred_at", occurredAt)
                }))
            })
        }
        return envelopeFromPayload(base, "RECORD_CREDIT_REPAYMENT", payload, repaymentOperationId)
    }

    private fun couponCreditRepaymentEnvelope(): RemoteEnvelopeV2 {
        val base = creditRepaymentEnvelope()
        val payload = JSONObject(base.canonicalText)
        val evidence = payload.getJSONObject("financial_evidence")
        val repaymentId = payload.optString("subject_id")
        val customerId = evidence.getJSONObject("credit_repayment").optString("customer_id")
        val occurredAt = payload.getLong("occurred_at")
        val walletId = "coupon-credit-repayment-wallet"
        payload.getJSONArray("settlements").getJSONObject(0).apply {
            put("category", "COUPON")
            put("channel", "COUPON_WALLET")
        }
        evidence.getJSONObject("credit_repayment").put("payment_category", "COUPON")
        evidence.put("coupon_wallets", JSONArray().put(JSONObject().apply {
            put("id", walletId)
            put("customer_id", customerId)
            put("currency_code", "OMR")
            put("is_active", true)
            put("created_at", occurredAt)
        }))
        evidence.put("coupon_ledger_events", JSONArray().put(JSONObject().apply {
            put("id", "coupon-credit-redemption")
            put("operation_id", base.operationId)
            put("wallet_id", walletId)
            put("type", "REDEMPTION")
            put("amount_signed_minor", -1_000L)
            put("currency_code", "OMR")
            put("authorized_user_id", "coupon-authorized-user")
            put("authorized_user_name_snapshot", "Family Member")
            put("occurred_at", occurredAt)
        }))
        return envelopeFromPayload(
            base, "RECORD_CREDIT_REPAYMENT", payload, "credit-repayment-operation"
        )
    }

    private fun couponPurchaseEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val couponOperationId = "coupon-purchase-operation"
        val walletId = "coupon-purchase-wallet"
        val customerId = "coupon-purchase-customer"
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_type", "RECORD_COUPON_PURCHASE")
            getJSONObject("transaction").apply {
                put("type", "COUPON_PURCHASE")
                put("customer_id", customerId)
            }
            getJSONArray("settlements").getJSONObject(0).apply {
                put("operation_id", couponOperationId)
                put("entry_purpose", "COUPON_PURCHASE_FUNDING")
            }
            put("financial_evidence", JSONObject().apply {
                put("coupon_wallets", JSONArray().put(JSONObject().apply {
                    put("id", walletId)
                    put("customer_id", customerId)
                    put("currency_code", "OMR")
                    put("is_active", true)
                    put("created_at", occurredAt)
                }))
                put("coupon_ledger_events", JSONArray().put(JSONObject().apply {
                    put("id", "coupon-purchase-ledger-event")
                    put("operation_id", couponOperationId)
                    put("wallet_id", walletId)
                    put("type", "PURCHASE")
                    put("amount_signed_minor", 1_000L)
                    put("currency_code", "OMR")
                    put("transaction_id", transactionId)
                    put("occurred_at", occurredAt)
                }))
            })
        }
        return envelopeFromPayload(base, "RECORD_COUPON_PURCHASE", payload, couponOperationId)
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
    fun appliesCashShiftCountWithSubjectAsAuthoritativeIdentity() = runTest {
        val envelope = cashShiftEnvelope("COUNT_CASH_SHIFT")
        val shift = CashShiftEntity(
            id = "inbound-shift-id",
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        coEvery { cashShiftDao.getShiftById(shift.id) } returns shift

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(null, storedOperation?.cashShiftId)
        coVerify(exactly = 1) {
            cashShiftDao.insertShiftEvent(match {
                it.shiftId == shift.id && it.type.name == "COUNTED"
            })
        }
    }

    @Test
    fun appliesCashShiftCloseWithSubjectAsAuthoritativeIdentity() = runTest {
        val envelope = cashShiftEnvelope("CLOSE_CASH_SHIFT")
        val shift = CashShiftEntity(
            id = "inbound-shift-id",
            branchId = branchId,
            userId = actorUserId,
            createdAt = envelope.payloadJson.getLong("occurred_at")
        )
        coEvery { cashShiftDao.getShiftById(shift.id) } returns shift

        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database, envelope, businessId, branchId
        )

        assertEquals(InboundProcessResult.Applied, result)
        assertEquals(null, storedOperation?.cashShiftId)
        coVerify(exactly = 1) {
            cashShiftDao.insertShiftEvent(match {
                it.shiftId == shift.id && it.type.name == "CLOSED"
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

    private fun cashShiftEnvelope(
        operationType: String,
        eventShiftId: String = "inbound-shift-id"
    ): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val shiftId = "inbound-shift-id"
        val operation = when (operationType) { "OPEN_CASH_SHIFT" -> "open-shift-operation"; "COUNT_CASH_SHIFT" -> "count-shift-operation"; else -> "close-shift-operation" }
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_id", operation)
            put("operation_type", operationType)
            put("subject_type", SubjectType.CASH_SHIFT.name)
            put("subject_id", shiftId)
            put("settlements", JSONArray())
            put("financial_evidence", JSONObject().apply {
                put("cash_shift", JSONObject().apply {
                    put("id", shiftId)
                    put("branch_id", branchId)
                    put("user_id", actorUserId)
                    put("created_at", occurredAt)
                })
                put("cash_shift_event", JSONObject().apply {
                    put("id", "$operation-event")
                    put("shift_id", eventShiftId)
                    put("operation_id", operation)
                    put("type", when (operationType) { "OPEN_CASH_SHIFT" -> "OPENED"; "COUNT_CASH_SHIFT" -> "COUNTED"; else -> "CLOSED" })
                    put("amount_minor", 1_000L)
                    put("occurred_at", occurredAt)
                    put("actor_user_id", actorUserId)
                })
            })
        }
        return envelopeFromPayload(base, operationType, payload, operation)
    }

    private fun expenseLifecycleEnvelope(
        operationType: String,
        eventType: String,
        signedSettlementMinor: Long,
        previousAmount: Long,
        newAmount: Long?
    ): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val expenseId = "lifecycle-expense-id"
        val lifecycleOperationId = if (operationType == "RECORD_EXPENSE_VOID") {
            "expense-void-operation"
        } else {
            "expense-correction-operation"
        }
        val purpose = if (operationType == "RECORD_EXPENSE_VOID") "VOID_REVERSAL" else "EXPENSE_CORRECTION"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_id", lifecycleOperationId)
            put("operation_type", operationType)
            put("subject_type", SubjectType.EXPENSE.name)
            put("subject_id", expenseId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "$lifecycleOperationId-settlement")
                put("operation_id", lifecycleOperationId)
                put("subject_type", SubjectType.EXPENSE.name)
                put("subject_id", expenseId)
                put("entry_purpose", purpose)
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", signedSettlementMinor)
                put("currency_code", "OMR")
                put("confirmation_type", "CASHIER_CONFIRMED")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject().apply {
                put("expense_snapshot", JSONObject().apply {
                    put("id", expenseId)
                    put("business_id", businessId)
                    put("branch_id", branchId)
                    put("currency_code", "OMR")
                    put("payment_category", "CASH")
                })
                put("expense_events", JSONArray().put(JSONObject().apply {
                    put("id", "$lifecycleOperationId-event")
                    put("expense_id", expenseId)
                    put("operation_id", lifecycleOperationId)
                    put("event_type", eventType)
                    put("actor_user_id", actorUserId)
                    put("previous_amount_minor", previousAmount)
                    put("new_amount_minor", newAmount ?: JSONObject.NULL)
                    put("previous_payment_category", "CASH")
                    put("new_payment_category", if (eventType == "CORRECTED") "CASH" else "")
                    put("previous_category", "OTHER")
                    put("new_category", if (eventType == "CORRECTED") "OTHER" else "")
                    put("previous_payee_id", "")
                    put("new_payee_id", "")
                    put("reason", "Inbound lifecycle test")
                    put("occurred_at", occurredAt)
                }))
            })
        }
        return envelopeFromPayload(base, operationType, payload, lifecycleOperationId)
    }

    private fun transactionLifecycleEnvelope(
        operationType: String,
        lifecycleOperationId: String,
        signedSettlementMinor: Long,
        entryPurpose: String,
        financialEvidence: JSONObject
    ): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val settlement = JSONObject().apply {
            put("id", "$lifecycleOperationId-settlement")
            put("operation_id", lifecycleOperationId)
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("entry_purpose", entryPurpose)
            put("category", "CASH")
            put("channel", "CASH_TENDER")
            put("amount_signed_minor", signedSettlementMinor)
            put("currency_code", "OMR")
            put("confirmation_type", ConfirmationType.CASHIER_CONFIRMED.name)
            put("actor_user_id", actorUserId)
            put("occurred_at", occurredAt)
        }
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_id", lifecycleOperationId)
            put("operation_type", operationType)
            put("occurred_at", occurredAt)
            put("settlements", JSONArray().put(settlement))
            put("financial_evidence", financialEvidence)
        }
        return envelopeFromPayload(base, operationType, payload, lifecycleOperationId)
    }

    private fun expenseEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val expenseId = "0199c104-1234-7abc-8def-0123456789ab"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_type", "RECORD_EXPENSE")
            put("subject_type", SubjectType.EXPENSE.name)
            put("subject_id", expenseId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "expense-outflow-settlement")
                put("operation_id", operationId)
                put("subject_type", SubjectType.EXPENSE.name)
                put("subject_id", expenseId)
                put("entry_purpose", "EXPENSE_OUTFLOW")
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", -1_000L)
                put("currency_code", "OMR")
                put("confirmation_type", "CASHIER_CONFIRMED")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject())
            put("expense", JSONObject().apply {
                put("id", expenseId)
                put("operation_id", operationId)
                put("business_id", businessId)
                put("branch_id", branchId)
                put("actor_user_id", actorUserId)
                put("payee_name_snapshot", "Test payee")
                put("category", "OTHER")
                put("amount_minor", 1_000L)
                put("payment_category", "CASH")
                put("currency_code", "OMR")
                put("occurred_at", occurredAt)
                put("created_at", occurredAt)
            })
        }
        return envelopeFromPayload(base, "RECORD_EXPENSE", payload)
    }

    private fun paymentMethodChangeEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val paymentChangeOperationId = "payment-change-operation"
        fun settlement(
            id: String,
            category: String,
            channel: String,
            amount: Long,
            confirmationType: String
        ) = JSONObject().apply {
            put("id", id)
            put("operation_id", paymentChangeOperationId)
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("entry_purpose", "PAYMENT_METHOD_RECLASSIFICATION")
            put("category", category)
            put("channel", channel)
            put("amount_signed_minor", amount)
            put("currency_code", "OMR")
            put("confirmation_type", confirmationType)
            put("actor_user_id", actorUserId)
            put("occurred_at", occurredAt)
        }
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_id", paymentChangeOperationId)
            put("operation_type", "RECORD_PAYMENT_METHOD_CHANGE")
            put("settlements", JSONArray()
                .put(settlement("payment-change-cash", "CASH", "CASH_TENDER", -1_000L, "CASHIER_CONFIRMED"))
                .put(settlement("payment-change-online", "ONLINE", "MANUAL_QR", 1_000L, "PROVIDER_APPROVED")))
            put("financial_evidence", JSONObject().put(
                "payment_method_change_events",
                JSONArray().put(JSONObject().apply {
                    put("id", "payment-change-event")
                    put("transaction_id", transactionId)
                    put("operation_id", paymentChangeOperationId)
                    put("previous_method", "CASH")
                    put("previous_channel", "CASH_TENDER")
                    put("new_method", "ONLINE")
                    put("new_channel", "MANUAL_QR")
                    put("reason", "Corrected tender")
                    put("actor_user_id", actorUserId)
                    put("occurred_at", occurredAt)
                })
            ))
        }
        return envelopeFromPayload(
            base,
            "RECORD_PAYMENT_METHOD_CHANGE",
            payload,
            operationId = paymentChangeOperationId
        )
    }

    private fun envelopeFromPayload(
        base: RemoteEnvelopeV2,
        operationType: String,
        payload: JSONObject,
        operationId: String = base.operationId
    ): RemoteEnvelopeV2 {
        payload.put("operation_id", operationId)
        payload.put("operation_type", operationType)
        val canonicalText = payload.toString()
        val occurredAtMillis = payload.getLong("occurred_at")
        val occurredAtIso = Instant.ofEpochMilli(occurredAtMillis).toString()
        val receivedAtIso = Instant.ofEpochMilli(Math.addExact(occurredAtMillis, 1L)).toString()
        return base.copy(
            operationId = operationId,
            operationType = operationType,
            occurredAtIso = occurredAtIso,
            receivedAtIso = receivedAtIso,
            canonicalText = canonicalText,
            payloadJson = payload,
            canonicalSha256 = CommandFingerprint.sha256(canonicalText)
        )
    }

    private fun cashSaleEnvelope(
        actorId: String = actorUserId,
        serverActorMembershipId: String? = null,
        serverActorRole: String? = null
    ): RemoteEnvelopeV2 {
        val occurredAtIso = "2026-10-09T10:00:00Z"
        val occurredAtMillis = Instant.parse(occurredAtIso).toEpochMilli()
        val settlement = JSONObject().apply {
            put("id", "0199c103-1234-7abc-8def-0123456789ab")
            put("operation_id", operationId)
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("entry_purpose", "SALE_FUNDING")
            put("category", "CASH")
            put("channel", "CASH_TENDER")
            put("amount_signed_minor", 1000L)
            put("currency_code", "OMR")
            put("confirmation_type", ConfirmationType.CASHIER_CONFIRMED.name)
            put("actor_user_id", actorId)
        }
        val transaction = JSONObject().apply {
            put("id", transactionId)
            put("branch_id", branchId)
            put("type", "SALE")
            put("subtotal_minor", 1000L)
            put("tax_rate_bps", 0)
            put("taxable_base_minor", 1000L)
            put("tax_amount_minor", 0L)
            put("grand_total_minor", 1000L)
            put("currency_code", "OMR")
            put("currency_decimal_places", 3)
            put("created_by_user_id", actorId)
            put("device_id", "cashier-device")
            put("created_at", occurredAtMillis)
            put("display_ref", "SALE-001")
        }
        val payload = JSONObject().apply {
            put("operation_id", operationId)
            put("business_id", businessId)
            put("branch_id", branchId)
            put("operation_type", "RECORD_SALE")
            put("actor_user_id", actorId)
            put("device_id", "cashier-device")
            put("occurred_at", occurredAtMillis)
            put("command_schema_version", 1)
            put("request_fingerprint", fingerprint)
            put("intent_payload", "{}")
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("transaction", transaction)
            put("settlements", JSONArray().put(settlement))
        }
        val canonicalText = payload.toString()
        return RemoteEnvelopeV2(
            operationId = operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = "RECORD_SALE",
            occurredAtIso = occurredAtIso,
            receivedAtIso = "2026-10-09T10:00:01Z",
            canonicalText = canonicalText,
            payloadJson = payload,
            canonicalSha256 = CommandFingerprint.sha256(canonicalText),
            actorMembershipId = serverActorMembershipId,
            actorLocalUserId = if (serverActorMembershipId == null) null else actorId,
            actorRole = if (serverActorMembershipId == null) null else serverActorRole
        )
    }
}
