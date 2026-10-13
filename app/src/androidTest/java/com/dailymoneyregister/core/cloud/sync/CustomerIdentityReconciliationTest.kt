package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CustomerIdentityReconciliationTest {
    private lateinit var database: DmrDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = DmrDatabase.createInMemory(context)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO businesses(id,name,created_at,updated_at) VALUES('business-a','Business A',1,1)"
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun remoteCanonicalIdAliasesToExistingLocalCustomerWithoutRewritingId() = runBlocking {
        database.customerDao().insertCustomer(
            CustomerEntity(
                id = "device-local-id",
                businessId = "business-a",
                canonicalPhone = "+96891234567",
                name = "Existing Customer",
                createdAt = 100,
                updatedAt = 100
            )
        )

        val resolved = CustomerIdentityReconciler.reconcileRemoteCustomer(
            database = database,
            businessId = "business-a",
            canonicalCustomerId = "server-canonical-id",
            canonicalPhone = "+96891234567",
            name = "Server Display Name",
            createdAt = 100,
            updatedAt = 200,
            isServerCanonical = true
        )

        assertEquals("device-local-id", resolved)
        assertNotNull(database.customerDao().getCustomerById("device-local-id"))
        assertNull(database.customerDao().getCustomerById("server-canonical-id"))
        assertEquals(
            "device-local-id",
            CustomerIdentityReconciler.resolveLocalCustomerId(
                database, "business-a", "server-canonical-id"
            )
        )
        CustomerIdentityReconciler.reconcileRemoteCustomer(
            database = database,
            businessId = "business-a",
            canonicalCustomerId = "other-device-local-id",
            canonicalPhone = "+96891234567",
            name = "Customer From Other Device",
            createdAt = 100,
            updatedAt = 300
        )
        assertEquals(
            "device-local-id",
            CustomerIdentityReconciler.resolveLocalCustomerId(
                database, "business-a", "other-device-local-id"
            )
        )
        assertEquals(
            "server-canonical-id",
            CustomerIdentityReconciler.resolveCanonicalCustomerId(
                database, "business-a", "device-local-id"
            )
        )
    }

    @Test
    fun inboundEvidenceSnapshotMapsRemoteIdToExistingLocalCustomer() = runBlocking {
        database.customerDao().insertCustomer(
            CustomerEntity(
                id = "device-local-id",
                businessId = "business-a",
                canonicalPhone = "+96891234567",
                name = "Existing Customer",
                createdAt = 100,
                updatedAt = 100
            )
        )
        val evidence = JSONObject().put(
            "customer",
            JSONObject()
                .put("id", "remote-customer-id")
                .put("business_id", "business-a")
                .put("canonical_phone", "+96891234567")
                .put("name", "Remote Name")
                .put("created_at", 100L)
                .put("updated_at", 200L)
        )

        CustomerIdentityReconciler.reconcileEvidenceCustomers(database, "business-a", evidence)

        assertEquals(
            "device-local-id",
            CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(
                database, "business-a", "remote-customer-id"
            )
        )
        assertNotNull(database.customerDao().getCustomerById("device-local-id"))
        assertNull(database.customerDao().getCustomerById("remote-customer-id"))
    }

    @Test
    fun unknownCustomerIdIsPreservedUntilDirectorySnapshotArrives() = runBlocking {
        assertEquals(
            "remote-id-not-yet-synced",
            CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(
                database, "business-a", "remote-id-not-yet-synced"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun canonicalIdCannotBeReboundToAnotherPhone(): Unit = runBlocking {
        database.customerDao().insertCustomer(
            CustomerEntity(
                id = "device-local-id",
                businessId = "business-a",
                canonicalPhone = "+96891234567",
                name = "Customer",
                createdAt = 100,
                updatedAt = 100
            )
        )
        CustomerIdentityReconciler.reconcileRemoteCustomer(
            database = database,
            businessId = "business-a",
            canonicalCustomerId = "server-canonical-id",
            canonicalPhone = "+96891234567",
            name = "Customer",
            createdAt = 100,
            updatedAt = 100
        )
        CustomerIdentityReconciler.reconcileRemoteCustomer(
            database = database,
            businessId = "business-a",
            canonicalCustomerId = "server-canonical-id",
            canonicalPhone = "+96890000000",
            name = "Different Customer",
            createdAt = 100,
            updatedAt = 100
        )
    }

    @Test
    fun authoritativeDirectoryIdReconcilesAnEarlierEnvelopeAlias() = runBlocking {
        database.customerDao().insertCustomer(
            CustomerEntity(
                id = "device-local-id",
                businessId = "business-a",
                canonicalPhone = "+96891234567",
                name = "Existing Customer",
                createdAt = 100,
                updatedAt = 100
            )
        )
        CustomerIdentityReconciler.reconcileRemoteCustomer(
            database = database,
            businessId = "business-a",
            canonicalCustomerId = "cashier-device-id",
            canonicalPhone = "+96891234567",
            name = "Existing Customer",
            createdAt = 100,
            updatedAt = 150,
            isServerCanonical = false
        )
        CustomerIdentityReconciler.reconcileRemoteCustomer(
            database = database,
            businessId = "business-a",
            canonicalCustomerId = "server-canonical-id",
            canonicalPhone = "+96891234567",
            name = "Existing Customer",
            createdAt = 100,
            updatedAt = 200,
            isServerCanonical = true
        )

        assertEquals(
            "device-local-id",
            CustomerIdentityReconciler.resolveLocalCustomerId(
                database, "business-a", "cashier-device-id"
            )
        )
        assertEquals(
            "server-canonical-id",
            CustomerIdentityReconciler.resolveCanonicalCustomerId(
                database, "business-a", "device-local-id"
            )
        )
    }
}
