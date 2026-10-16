package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CloudStaffIdentityDao
import com.dailymoneyregister.core.database.dao.ExpenseDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RemoteEnvelopeMaterializerTest {
    private val database = mockk<DmrDatabase>()
    private val branchDao = mockk<BusinessBranchDao>()
    private val identityDao = mockk<CloudStaffIdentityDao>()
    private val operationDao = mockk<OperationDao>()
    private val expenseDao = mockk<ExpenseDao>()
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
        every { database.cloudStaffIdentityDao() } returns identityDao
        every { database.operationDao() } returns operationDao
        every { database.expenseDao() } returns expenseDao
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
        coEvery { operationDao.getOperationById(any()) } coAnswers { storedOperation }
        coEvery { operationDao.insertOperationIfAbsent(any()) } coAnswers {
            storedOperation = firstArg<OperationEntity>()
            1L
        }
        coEvery { operationDao.markSucceededIfPending(any(), any(), any(), any()) } returns 0
        coEvery { operationDao.isOperationSucceeded(any()) } answers { storedOperation?.status == OperationStatus.SUCCEEDED }
        coEvery { transactionDao.getTransactionById(transactionId) } returns null
        coEvery { transactionDao.insertTransaction(any()) } returns Unit
        coEvery { transactionDao.getItemsForTransaction(transactionId) } returns emptyList()
        coEvery { transactionDao.getSettlementsForOperation(any()) } returns emptyList()
        coEvery { transactionDao.insertSettlements(any()) } returns Unit
        coEvery { transactionDao.getPaymentMethodChanges(transactionId) } returns emptyList()
        coEvery { transactionDao.insertPaymentMethodChange(any()) } returns Unit
        coEvery { expenseDao.getExpenseById(any()) } returns null
        coEvery { expenseDao.insertExpense(any()) } returns Unit

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
                    settlements.single().amountSignedMinor == -1_000L
            })
        }
        coVerify(exactly = 0) { userDao.getUserByIdInBusiness(actorUserId, businessId) }
        coVerify(exactly = 0) { transactionDao.insertTransaction(any()) }
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
        coVerify(exactly = 0) { transactionDao.insertTransaction(any()) }
        coVerify(exactly = 1) { transactionDao.insertSettlements(match { it.size == 2 }) }
        coVerify(exactly = 1) { transactionDao.insertPaymentMethodChange(any()) }
        coVerify(exactly = 0) { userDao.getUserByIdInBusiness(actorUserId, businessId) }
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
        return base.copy(
            operationId = operationId,
            operationType = operationType,
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
