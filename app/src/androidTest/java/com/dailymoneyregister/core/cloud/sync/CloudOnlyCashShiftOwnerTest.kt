package com.dailymoneyregister.core.cloud.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudOnlyCashShiftOwnerTest {
    private lateinit var database: DmrDatabase
    private val businessId = "cloud-business"
    private val branchId = "cloud-branch"
    private val cashierId = "cloud-cashier"

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = DmrDatabase.createInMemory(context)
        database.businessBranchDao().insertBusiness(
            BusinessEntity(businessId, "Cloud Shop", 1L, 1L)
        )
        database.businessBranchDao().insertBranch(
            BranchEntity(
                id = branchId, businessId = businessId, name = "Main", code = "MAIN",
                countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
                currencySymbol = "OMR", timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP, defaultTaxRateBps = 0,
                createdAt = 1L, updatedAt = 1L
            )
        )
        database.cloudStaffIdentityDao().upsertAll(
            listOf(CloudStaffIdentityEntity(
                membershipId = "membership-cloud-cashier", localUserId = cashierId,
                businessId = businessId, branchId = branchId, role = "CASHIER",
                permissionsJson = "{}", isActive = true, isRemoved = false, updatedAt = 1L
            ))
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun appliesOpenCashShiftForCloudOnlyCashier() = runTest {
        applyShift("open-shift", "OPENED")
        assertNotNull(database.cashShiftDao().getShiftById("open-shift"))
        assertNotNull(database.cashShiftDao().getEventByOperationId("operation-open-shift"))
        assertNull(database.userDao().getUserById(cashierId))
    }

    @Test
    fun appliesCountCashShiftForCloudOnlyCashier() = runTest {
        applyShift("count-shift", "COUNTED")
        assertNotNull(database.cashShiftDao().getShiftById("count-shift"))
        assertNotNull(database.cashShiftDao().getEventByOperationId("operation-count-shift"))
        assertNull(database.userDao().getUserById(cashierId))
    }

    @Test
    fun appliesCloseCashShiftForCloudOnlyCashier() = runTest {
        applyShift("close-shift", "CLOSED")
        assertNotNull(database.cashShiftDao().getShiftById("close-shift"))
        assertNotNull(database.cashShiftDao().getEventByOperationId("operation-close-shift"))
        assertNull(database.userDao().getUserById(cashierId))
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions").use {
            check(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM settlement_entries").use {
            check(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun rejectsCashShiftFromWrongBusinessOrBranch() = runTest {
        val wrongBranch = evidence("wrong-branch-shift", branchId = "other-branch")
        val branchRejected = runCatching {
            RemoteEnvelopeEvidenceApplier.apply(
                database, wrongBranch, "wrong-branch-op", businessId, branchId, cashierId
            )
        }.isFailure
        assertEquals(true, branchRejected)
        assertNull(database.cashShiftDao().getShiftById("wrong-branch-shift"))

        val wrongBusinessIdentity = CloudStaffIdentityEntity(
            membershipId = "other-business-membership", localUserId = "other-business-cashier",
            businessId = "other-business", branchId = branchId, role = "CASHIER",
            permissionsJson = "{}", isActive = true, isRemoved = false, updatedAt = 2L
        )
        database.cloudStaffIdentityDao().upsertAll(listOf(wrongBusinessIdentity))
        val wrongBusiness = evidence("wrong-business-shift", userId = "other-business-cashier")
        val businessRejected = runCatching {
            RemoteEnvelopeEvidenceApplier.apply(
                database, wrongBusiness, "wrong-business-op", businessId, branchId, "other-business-cashier"
            )
        }.isFailure
        assertEquals(true, businessRejected)
        assertNull(database.cashShiftDao().getShiftById("wrong-business-shift"))
    }

    @Test
    fun preservesHistoricalCashShiftForRevokedCloudCashierWithoutLocalUserRow() = runTest {
        database.cloudStaffIdentityDao().upsertAll(
            listOf(CloudStaffIdentityEntity(
                membershipId = "membership-cloud-cashier", localUserId = cashierId,
                businessId = businessId, branchId = branchId, role = "CASHIER",
                permissionsJson = "{}", isActive = false, isRemoved = true,
                updatedAt = 2L, displayName = "Cloud Cashier"
            ))
        )

        applyShift("revoked-shift", "OPENED")

        assertNotNull(database.cashShiftDao().getShiftById("revoked-shift"))
    }

    @Test
    fun replaysCashShiftEnvelopeExactlyOnce() = runTest {
        applyShift("replayed-shift", "OPENED")
        applyShift("replayed-shift", "OPENED")
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM cash_shifts WHERE id = 'replayed-shift'"
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun doesNotCreateLoginAccountDuringCloudStaffSync() = runTest {
        val source = mockk<com.dailymoneyregister.feature.admin.data.EmployeeDirectoryCloudSource>()
        val metadataSynchronizer = mockk<CashierMetadataSynchronizer>(relaxed = true)
        coEvery { source.listCashiers() } returns listOf(
            com.dailymoneyregister.feature.admin.data.CloudCashierDirectoryEntry(
                userId = cashierId, membershipId = "membership-cloud-cashier",
                branchId = branchId, displayName = "Cloud Cashier",
                canonicalPhone = "+96890000000", isActive = true,
                permissions = emptyMap(), isRemoved = false
            )
        )
        DefaultStaffIdentitySync(source, database, metadataSynchronizer)
            .synchronize(businessId, branchId)
        assertNull(database.userDao().getUserById(cashierId))
        assertNotNull(database.cloudStaffIdentityDao().findIdentity(cashierId, businessId, branchId))
        assertEquals("Cloud Cashier", database.cloudStaffIdentityDao()
            .findIdentity(cashierId, businessId, branchId)?.displayName)
    }

    private suspend fun applyShift(id: String, eventType: String) {
        val operationId = "operation-$id"
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT OR IGNORE INTO operations (
                id, business_id, branch_id, operation_type, subject_type, subject_id,
                actor_user_id, device_id, occurred_at, request_fingerprint,
                command_schema_version, intent_payload, status
            ) VALUES (
                '$operationId', '$businessId', '$branchId', '${eventTypeToOperation(eventType)}',
                'CASH_SHIFT', '$id', '$cashierId', 'cloud-test-device', 10,
                'fingerprint-$id', 1, '{}', 'SUCCEEDED'
            )
            """.trimIndent()
        )
        RemoteEnvelopeEvidenceApplier.apply(
            database = database,
            evidence = evidence(id, eventType),
            operationId = operationId,
            businessId = businessId,
            branchId = branchId,
            actorUserId = cashierId
        )
    }

    private fun eventTypeToOperation(eventType: String): String = when (eventType) {
        "OPENED" -> "OPEN_CASH_SHIFT"
        "COUNTED" -> "COUNT_CASH_SHIFT"
        "CLOSED" -> "CLOSE_CASH_SHIFT"
        else -> error("Unexpected cash-shift event type: $eventType")
    }

    private fun evidence(
        id: String,
        branchId: String = this.branchId,
        userId: String = cashierId,
        eventType: String = "OPENED"
    ) = JSONObject().put(
        "cash_shift",
        JSONObject().put("id", id).put("branch_id", branchId)
            .put("user_id", userId).put("created_at", 10L)
    ).put(
        "cash_shift_event",
        JSONObject().put("id", "event-$id").put("shift_id", id)
            .put("operation_id", "operation-$id").put("type", eventType)
            .put("amount_minor", 2500L).put("occurred_at", 10L)
            .put("actor_user_id", userId)
    )
}
