package com.dailymoneyregister

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.cloud.sync.InboundProcessResult
import com.dailymoneyregister.core.cloud.sync.RemoteEnvelopeMaterializer
import com.dailymoneyregister.core.cloud.sync.RemoteEnvelopeV2
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
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
class ExpenseRollbackTest {

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
                        role = "CASHIER",
                        permissionsJson = "{}",
                        isActive = true,
                        isRemoved = false,
                        updatedAt = 1L
                    )
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
    fun settlementFailure_rollsBackExpenseAndOperation() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_settlement BEFORE INSERT ON settlement_entries " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END"
        )

        val result = materialize(expenseEnvelope())

        assertEquals(1, count("branches"))
        assertEquals(1, count("cloud_staff_identities"))
        assertTrue(
            "expected rejection from forced fault, got $result",
            result is InboundProcessResult.Rejected && result.reason.contains("forced")
        )
        assertEquals(0, count("expenses"))
        assertEquals(0, count("operations"))
        assertEquals(0, count("settlement_entries"))
    }
}
