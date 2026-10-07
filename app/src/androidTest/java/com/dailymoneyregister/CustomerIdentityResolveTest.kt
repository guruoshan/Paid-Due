package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.customers.data.CustomerIdentity
import com.dailymoneyregister.feature.customers.data.CustomerIdentityException
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 1A proof: one phone = one customer id, and nobody is renamed. Throw-away database. */
@RunWith(AndroidJUnit4::class)
class CustomerIdentityResolveTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var trustedSessionStore: TrustedSessionStore
    private lateinit var businessId: String

    @Before
    fun setUp() = runBlocking {
        context = com.dailymoneyregister.isolatedAndroidTestContext(ApplicationProvider.getApplicationContext())
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()
        database = DmrDatabase.createInMemory(context)

        val setup = (SetupRepository(
            database,
            CommandExecutor(database),
            DeviceIdentityProvider(context),
            trustedSessionStore
        ).executeSetup(
            SetupParams(
                businessName = "Identity Shop",
                branchName = "Main",
                branchCode = "IDN",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 0,
                adminDisplayName = "Identity Owner",
                adminUsername = "identity_owner",
                adminPasswordPlain = "IdentityOwnerPass123",
                languageCode = "en"
            )
        ) as CommandResult.Success).data
        businessId = setup.businessId.value
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        database.close()
    }

    @Test
    fun newPhoneCreatesOneCustomer() = runBlocking {
        val dao = database.customerDao()
        val result = CustomerIdentity.resolveOrCreate(dao, businessId, "+968 9123 4567", " Sara ", 1_000L)
        assertTrue(result.wasCreated)
        assertEquals("+96891234567", result.customer.canonicalPhone)
        assertEquals("Sara", result.customer.name)
        assertEquals(1, dao.getCustomersForBusiness(businessId).size)
    }

    @Test
    fun samePhoneWrittenDifferentlyGivesTheSameId() = runBlocking {
        val dao = database.customerDao()
        val first = CustomerIdentity.resolveOrCreate(dao, businessId, "+96891234567", "Sara", 1_000L)
        val second = CustomerIdentity.resolveOrCreate(dao, businessId, "+968-9123-4567", "Sara", 2_000L)
        assertFalse(second.wasCreated)
        assertEquals(first.customer.id, second.customer.id)
        assertEquals(1, dao.getCustomersForBusiness(businessId).size)
    }

    @Test
    fun existingCustomerKeepsSavedNameAndIsNeverRenamed() = runBlocking {
        val dao = database.customerDao()
        val first = CustomerIdentity.resolveOrCreate(dao, businessId, "+96891234567", "Sara", 1_000L)
        val second = CustomerIdentity.resolveOrCreate(dao, businessId, "+96891234567", "Someone Else", 2_000L)
        assertEquals("Sara", second.customer.name)
        assertTrue(second.enteredNameDiffers)
        assertEquals("Sara", dao.getCustomerById(first.customer.id)!!.name)
        assertEquals(1_000L, dao.getCustomerById(first.customer.id)!!.updatedAt)
    }

    @Test
    fun existingCustomerCanBeFoundWithoutTypingTheName() = runBlocking {
        val dao = database.customerDao()
        val first = CustomerIdentity.resolveOrCreate(dao, businessId, "+96891234567", "Sara", 1_000L)
        val again = CustomerIdentity.resolveOrCreate(dao, businessId, "+96891234567", "", 2_000L)
        assertEquals(first.customer.id, again.customer.id)
        assertFalse(again.enteredNameDiffers)
    }

    @Test
    fun newPhoneWithoutNameIsRejectedAndNothingIsSaved() = runBlocking {
        val dao = database.customerDao()
        try {
            CustomerIdentity.resolveOrCreate(dao, businessId, "+96891234567", "   ", 1_000L)
            fail("Expected rejection")
        } catch (expected: CustomerIdentityException) {
            // ok
        }
        assertEquals(0, dao.getCustomersForBusiness(businessId).size)
    }

    @Test
    fun invalidPhoneIsRejectedAndNothingIsSaved() = runBlocking {
        val dao = database.customerDao()
        try {
            CustomerIdentity.resolveOrCreate(dao, businessId, "91234567", "Sara", 1_000L)
            fail("Expected rejection")
        } catch (expected: CustomerIdentityException) {
            // ok
        }
        assertEquals(0, dao.getCustomersForBusiness(businessId).size)
    }
}

