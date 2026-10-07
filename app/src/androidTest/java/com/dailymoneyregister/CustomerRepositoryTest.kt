package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.DocumentEntity
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.customers.data.CustomerRepository
import com.dailymoneyregister.feature.invoice.data.GenerateInvoiceCommand
import com.dailymoneyregister.feature.invoice.data.InvoiceRepository
import com.dailymoneyregister.feature.invoice.domain.InvoiceBusinessSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceCustomerSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceItemSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshot
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 proof: the Customers list and a customer's invoice history read
 * back exactly what Phase 6 saved. Read-only; uses a throw-away database.
 */
@RunWith(AndroidJUnit4::class)
class CustomerRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var trustedSessionStore: TrustedSessionStore
    private lateinit var invoiceRepository: InvoiceRepository
    private lateinit var customerRepository: CustomerRepository

    private lateinit var businessId: String
    private lateinit var branchId: String
    private lateinit var adminId: String

    @Before
    fun setUp() = runBlocking {
        context = com.dailymoneyregister.isolatedAndroidTestContext(ApplicationProvider.getApplicationContext())
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()
        database = DmrDatabase.createInMemory(context)

        val setupRepository = SetupRepository(
            database,
            CommandExecutor(database),
            DeviceIdentityProvider(context),
            trustedSessionStore
        )

        val setup = (setupRepository.executeSetup(
            SetupParams(
                businessName = "Customers Shop",
                branchName = "Main",
                branchCode = "CUS",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 0,
                adminDisplayName = "Customers Owner",
                adminUsername = "customers_owner",
                adminPasswordPlain = "CustomersOwnerPass123",
                languageCode = "en"
            )
        ) as CommandResult.Success).data

        businessId = setup.businessId.value
        branchId = setup.branchId.value
        adminId = setup.adminUserId.value

        SessionManager.setSession(adminSession())

        invoiceRepository = InvoiceRepository(database)
        customerRepository = CustomerRepository(database.customerDao(), database)
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun listShowsCountsLastInvoiceAndCustomersWithoutInvoices() = runBlocking {
        issueInvoice("Sara", PHONE_SARA, unitPriceMinor = 2_000L, occurredAt = 1_000_000L)
        issueInvoice("ali", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 2_000_000L)
        issueInvoice("ali", PHONE_ALI, unitPriceMinor = 1_000L, occurredAt = 3_000_000L)

        // A customer who never received an invoice (for example a coupon customer).
        database.customerDao().insertCustomer(
            CustomerEntity(
                id = IdGenerator.newId(),
                businessId = businessId,
                canonicalPhone = PHONE_NOBODY,
                name = "Zed",
                createdAt = 500_000L,
                updatedAt = 500_000L
            )
        )

        val list = customerRepository.listCustomers()

        assertEquals("Sorted by name, ignoring upper/lower case", listOf("ali", "Sara", "Zed"), list.map { it.name })

        val ali = list[0]
        assertEquals(2, ali.invoiceCount)
        assertEquals(3_000_000L, ali.lastInvoiceAt)
        assertEquals(PHONE_ALI, ali.phone)

        assertEquals(1, list[1].invoiceCount)
        assertEquals(0, list[2].invoiceCount)
        assertNull(list[2].lastInvoiceAt)
    }

    @Test
    fun historyIsNewestFirstWithTotalsAndUnpaidStatus() = runBlocking {
        val first = issueInvoice("Ali", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 1_000_000L)
        val second = issueInvoice("Ali", PHONE_ALI, unitPriceMinor = 2_500L, occurredAt = 2_000_000L)

        val history = customerRepository.getHistory(first.customerId!!)
        assertNotNull(history)
        history!!

        assertEquals("Ali", history.customer.name)
        assertEquals(listOf(second.displayNumber, first.displayNumber), history.invoices.map { it.displayNumber })

        // quantity 2 in the helper: 2 x 2.500 = 5.000 and 2 x 1.500 = 3.000
        assertEquals(5_000L, history.invoices[0].totalMinor)
        assertEquals(3_000L, history.invoices[1].totalMinor)
        assertEquals("OMR", history.invoices[0].currencyCode)
        assertEquals(3, history.invoices[0].decimalPlaces)
        assertFalse("A new invoice is not linked to a paid sale", history.invoices[0].isPaid)
    }

    @Test
    fun historyOnlyContainsThatCustomersInvoices() = runBlocking {
        val ali = issueInvoice("Ali", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 1_000_000L)
        issueInvoice("Sara", PHONE_SARA, unitPriceMinor = 2_000L, occurredAt = 2_000_000L)

        val history = customerRepository.getHistory(ali.customerId!!)!!
        assertEquals(1, history.invoices.size)
        assertEquals(ali.displayNumber, history.invoices[0].displayNumber)
    }

    @Test
    fun unknownCustomerReturnsNull() = runBlocking {
        assertNull(customerRepository.getHistory("does-not-exist"))
    }

    @Test
    fun userWithoutPermissionIsDenied() = runBlocking {
        val admin = database.userDao().getUserById(adminId)!!
        SessionManager.setSession(
            ActiveSession(
                business = database.businessBranchDao().getBusinessById(businessId)!!,
                branch = database.businessBranchDao().getBranchById(branchId)!!,
                user = admin.copy(role = UserRole.CASHIER),
                membership = null
            )
        )

        assertFalse(CustomerRepository.canViewCustomers())
        try {
            customerRepository.listCustomers()
            fail("A cashier without a membership must not be able to list customers")
        } catch (expected: PermissionDeniedException) {
            assertTrue(true)
        }
    }

    // ---------------------------------------------------------------

    private suspend fun adminSession() = ActiveSession(
        business = database.businessBranchDao().getBusinessById(businessId)!!,
        branch = database.businessBranchDao().getBranchById(branchId)!!,
        user = database.userDao().getUserById(adminId)!!,
        membership = null
    )

    private suspend fun issueInvoice(
        name: String,
        phone: String,
        unitPriceMinor: Long,
        occurredAt: Long
    ): DocumentEntity {
        val branch = database.businessBranchDao().getBranchById(branchId)!!
        val lineTotal = unitPriceMinor * 2

        val snapshot = InvoiceSnapshot(
            documentId = IdGenerator.newId(),
            currencyCode = branch.currencyCode,
            currencyDecimalPlaces = branch.currencyDecimalPlaces,
            customer = InvoiceCustomerSnapshot(name = name, phone = phone),
            business = InvoiceBusinessSnapshot(name = "Customers Shop"),
            items = listOf(
                InvoiceItemSnapshot(
                    id = "item-1",
                    name = "Test item",
                    quantity = 2,
                    unitPriceMinor = unitPriceMinor,
                    lineTotalMinor = lineTotal
                )
            ),
            subtotalMinor = lineTotal,
            taxRateBps = 0,
            taxAmountMinor = 0L,
            txShippingMinor = 0L,
            txPackingMinor = 0L,
            itemShippingTotalMinor = 0L,
            itemPackingTotalMinor = 0L,
            grandTotalMinor = lineTotal,
            createdAt = occurredAt
        )

        val result = invoiceRepository.executeGenerateInvoice(
            GenerateInvoiceCommand(
                operationId = OperationId.generate(),
                businessId = businessId,
                branchId = branchId,
                actorUserId = adminId,
                snapshot = snapshot,
                deviceId = "test-device",
                occurredAt = occurredAt
            )
        )

        assertTrue("Invoice should be issued, but got: $result", result is CommandResult.Success)
        return (result as CommandResult.Success).data
    }

    private companion object {
        const val PHONE_ALI = "+96891234567"
        const val PHONE_SARA = "+96897654321"
        const val PHONE_NOBODY = "+96895550000"
    }
}

