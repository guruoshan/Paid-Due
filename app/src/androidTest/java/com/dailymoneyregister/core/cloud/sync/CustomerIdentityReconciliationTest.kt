package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import kotlinx.coroutines.runBlocking
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
            updatedAt = 200
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

    @Test(expected = IllegalArgumentException::class)
    fun canonicalIdCannotBeReboundToAnotherPhone() = runBlocking {
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
}
