package com.dailymoneyregister

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.cloud.sync.InboundProcessResult
import com.dailymoneyregister.core.cloud.sync.RemoteEnvelopeMaterializer
import com.dailymoneyregister.core.cloud.sync.RemoteEnvelopeV2
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CustomerIdAliasEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.SubjectType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ExpenseAttributionAuditTest {

    private lateinit var db: DmrDatabase

    private val businessId = "business-a"
    private val branchId = "branch-a"
    private val actorUserId = "cashier-user-a"
    private val transactionId = "0199c102-1234-7abc-8def-0123456789ab"
    private val operationId = "0199c101-1234-7abc-8def-0123456789ab"
    private val fingerprint = "expense-request-fingerprint"

    @Before
    fun setUp() {
        db = DmrDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.businessBranchDao().insertBusiness(BusinessEntity(businessId, "Business A", 0L, 0L))
            db.businessBranchDao().insertBranch(
                BranchEntity(
                    id = branchId,
                    businessId = businessId,
                    name = "Main",
                    code = "MAIN",
                    countryCode = "OM",
                    currencyCode = "OMR",
                    currencyDecimalPlaces = 3,
                    currencySymbol = "OMR",
                    timezoneId = "Asia/Muscat",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            db.cloudStaffIdentityDao().upsertAll(
                listOf(
                    CloudStaffIdentityEntity(
                        membershipId = "membership-a",
                        localUserId = actorUserId,
                        businessId = businessId,
                        branchId = branchId,
                        role = "ADMIN",
                        permissionsJson = "{}",
                        isActive = true,
                        isRemoved = false,
                        updatedAt = 1L
                    )
                )
            )
            db.cashShiftDao().insertShift(
                CashShiftEntity(
                    id = "expense-cash-shift",
                    branchId = branchId,
                    userId = actorUserId,
                    createdAt = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun cashSaleEnvelope(): RemoteEnvelopeV2 {
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
            put("actor_user_id", actorUserId)
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
            put("created_by_user_id", actorUserId)
            put("device_id", "cashier-device")
            put("created_at", occurredAtMillis)
            put("display_ref", "SALE-001")
        }
        val payload = JSONObject().apply {
            put("operation_id", operationId)
            put("business_id", businessId)
            put("branch_id", branchId)
            put("operation_type", "RECORD_SALE")
            put("actor_user_id", actorUserId)
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
            actorMembershipId = null,
            actorLocalUserId = null,
            actorRole = null
        )
    }

    private fun expenseEnvelope(includeCustomerEvidence: Boolean = false): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val expenseId = "0199c104-1234-7abc-8def-0123456789ab"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_type", "RECORD_EXPENSE")
            put("cash_shift_id", "expense-cash-shift")
            put("subject_type", SubjectType.EXPENSE.name)
            put("subject_id", expenseId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "$operationId:expense-outflow")
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
            put("financial_evidence", JSONObject().apply {
                if (includeCustomerEvidence) {
                    put("customer", JSONObject().apply {
                        put("id", "rollback-evidence-customer")
                        put("business_id", businessId)
                        put("canonical_phone", "+96891234567")
                        put("name", "Rollback Evidence Customer")
                        put("created_at", occurredAt)
                        put("updated_at", occurredAt)
                    })
                }
            })
            put("expense", JSONObject().apply {
                put("id", expenseId)
                put("operation_id", operationId)
                put("business_id", businessId)
                put("branch_id", branchId)
                put("cash_shift_id", "expense-cash-shift")
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

    private fun envelopeFromPayload(
        base: RemoteEnvelopeV2,
        operationType: String,
        payload: JSONObject
    ): RemoteEnvelopeV2 {
        payload.put("operation_id", operationId)
        payload.put("operation_type", operationType)
        val canonicalText = payload.toString()
        val occurredAtMillis = payload.getLong("occurred_at")
        return base.copy(
            operationType = operationType,
            occurredAtIso = Instant.ofEpochMilli(occurredAtMillis).toString(),
            receivedAtIso = Instant.ofEpochMilli(occurredAtMillis + 1L).toString(),
            canonicalText = canonicalText,
            payloadJson = payload,
            canonicalSha256 = CommandFingerprint.sha256(canonicalText)
        )
    }

    @Test
    fun closedShiftCannotReceiveExpense() {
        // The fixture seeds shift "expense-cash-shift" for this branch and actor.
        // Add a real operation row and a CLOSED event on that shift via the DAOs.
        runBlocking {
            db.operationDao().insertOperationIfAbsent(
                com.dailymoneyregister.core.database.entity.OperationEntity(
                    id = "closed-op",
                    businessId = businessId,
                    branchId = branchId,
                    operationType = com.dailymoneyregister.core.model.enums.OperationType.CLOSE_CASH_SHIFT,
                    subjectType = com.dailymoneyregister.core.model.enums.SubjectType.CASH_SHIFT,
                    subjectId = "expense-cash-shift",
                    transactionId = null,
                    cashShiftId = "expense-cash-shift",
                    actorUserId = actorUserId,
                    deviceId = "cashier-device",
                    occurredAt = 0L,
                    requestFingerprint = "closed-fingerprint",
                    commandSchemaVersion = 1,
                    intentPayload = "{}",
                    status = com.dailymoneyregister.core.model.enums.OperationStatus.SUCCEEDED
                )
            )
            db.cashShiftDao().insertShiftEvent(
                com.dailymoneyregister.core.database.entity.CashShiftEventEntity(
                    id = "closed-event",
                    shiftId = "expense-cash-shift",
                    operationId = "closed-op",
                    type = com.dailymoneyregister.core.model.enums.CashShiftEventType.CLOSED,
                    amountMinor = 0L,
                    occurredAt = 0L,
                    actorUserId = actorUserId
                )
            )
        }

        val result = materialize(expenseEnvelope())

        assertTrue("closed shift accepted an expense: $result", result is InboundProcessResult.Rejected)
        assertEquals(0, count("expenses"))
        assertEquals(1, count("operations"))  // only the seeded closed-op row
        assertEquals(0, count("settlement_entries"))
    }


    private fun materialize(envelope: RemoteEnvelopeV2): InboundProcessResult =
        runBlocking {
            RemoteEnvelopeMaterializer.materializeInboundEnvelope(
                database = db,
                envelope = envelope,
                sessionBusinessId = businessId,
                sessionBranchId = branchId
            )
        }

    @Test
    fun withoutFault_expenseOperationAndSettlementArePersisted() {
        val result = materialize(expenseEnvelope())

        assertEquals("expected Applied, got $result", InboundProcessResult.Applied, result)
        assertEquals(1, count("expenses"))
        assertEquals(1, count("operations"))
        assertEquals(1, count("settlement_entries"))
    }

    @Test
    fun settlementFailure_rollsBackExpenseOperationCustomerAndAlias() {
        runBlocking {
            db.customerDao().insertCustomer(
                CustomerEntity(
                    id = "preexisting-customer",
                    businessId = businessId,
                    canonicalPhone = "+96890000000",
                    name = "Pre-existing Customer",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
            db.customerDao().insertCustomerIdAlias(
                CustomerIdAliasEntity(
                    businessId = businessId,
                    aliasCustomerId = "preexisting-customer-alias",
                    localCustomerId = "preexisting-customer",
                    canonicalCustomerId = "preexisting-customer-alias",
                    canonicalPhone = "+96890000000",
                    updatedAt = 1L
                )
            )
        }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_settlement BEFORE INSERT ON settlement_entries " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END"
        )

        val result = materialize(expenseEnvelope(includeCustomerEvidence = true))

        assertEquals(1, count("branches"))
        assertEquals(1, count("cloud_staff_identities"))
        assertTrue(
            "expected rejection from forced fault, got $result",
            result is InboundProcessResult.Rejected && result.reason.contains("forced")
        )
        assertEquals(0, count("expenses"))
        assertEquals(0, count("operations"))
        assertEquals(0, count("settlement_entries"))
        assertEquals(1, count("customers"))
        assertEquals(1, count("customer_id_aliases"))
        runBlocking {
            val existing = db.customerDao().getCustomerById("preexisting-customer")
            assertEquals("Pre-existing Customer", existing?.name)
            assertEquals(
                "preexisting-customer",
                db.customerDao().getCustomerIdAliasByAliasId(
                    businessId, "preexisting-customer-alias"
                )?.localCustomerId
            )
            assertEquals(null, db.customerDao().getCustomerById("rollback-evidence-customer"))
            assertEquals(
                null,
                db.customerDao().getCustomerIdAliasByAliasId(
                    businessId, "rollback-evidence-customer"
                )
            )
        }
    }
}
