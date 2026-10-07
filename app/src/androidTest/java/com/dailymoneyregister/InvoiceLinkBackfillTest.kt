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
import com.dailymoneyregister.feature.customers.data.InvoiceLinkBackfillRepository
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
 * Linking older invoices: proves the one-time backfill links invoices that
 * were issued before customer linking existed, without renaming or changing
 * anything else. Uses a throw-away database.
 */
@RunWith(AndroidJUnit4::class)
class InvoiceLinkBackfillTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var trustedSessionStore: TrustedSessionStore
    private lateinit var invoiceRepository: InvoiceRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var backfill: InvoiceLinkBackfillRepository

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
                businessName = "Backfill Shop",
                branchName = "Main",
                branchCode = "BKF",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 0,
                adminDisplayName = "Backfill Owner",
                adminUsername = "backfill_owner",
                adminPasswordPlain = "BackfillOwnerPass123",
                languageCode = "en"
            )
        ) as CommandResult.Success).data

        businessId = setup.businessId.value
        branchId = setup.branchId.value
        adminId = setup.adminUserId.value

        SessionManager.setSession(adminSession())

        invoiceRepository = InvoiceRepository(database)
        customerRepository = CustomerRepository(database.customerDao(), database)
        backfill = InvoiceLinkBackfillRepository(database)
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun olderInvoicesGetLinkedAndNewestNameWins() = runBlocking {
        issueInvoice("Ali Old", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 1_000_000L)
        issueInvoice("Ali New", PHONE_ALI, unitPriceMinor = 2_000L, occurredAt = 2_000_000L)
        issueInvoice("Sara", PHONE_SARA, unitPriceMinor = 1_000L, occurredAt = 3_000_000L)
        makeAllInvoicesLegacyAndForgetCustomers()

        assertEquals(3, backfill.countUnlinkedInvoices())

        val result = backfill.linkOlderInvoices()
        assertEquals(3, result.linked)
        assertEquals(2, result.customersCreated)
        assertEquals(0, result.skipped)
        assertEquals(0, backfill.countUnlinkedInvoices())

        val list = customerRepository.listCustomers()
        assertEquals(listOf("Ali New", "Sara"), list.map { it.name })
        assertEquals(2, list[0].invoiceCount)
        assertEquals(1, list[1].invoiceCount)

        // Running it again finds nothing left to do.
        val again = backfill.linkOlderInvoices()
        assertEquals(0, again.linked)
        assertEquals(0, again.customersCreated)
        assertEquals(2, customerRepository.listCustomers().size)
    }

    @Test
    fun existingCustomerIsLinkedButNeverRenamed() = runBlocking {
        val issued = issueInvoice("Ali", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 1_000_000L)
        val customerId = issued.customerId!!

        // The owner has since corrected the customer's name by hand.
        val stored = database.customerDao().getCustomerById(customerId)!!
        database.customerDao().updateCustomer(stored.copy(name = "Ali Manual", updatedAt = 5_000_000L))

        // Only the invoice link is cleared; the customer stays.
        database.openHelper.writableDatabase.execSQL("UPDATE documents SET customer_id = NULL")

        val result = backfill.linkOlderInvoices()
        assertEquals(1, result.linked)
        assertEquals(0, result.customersCreated)

        val after = database.customerDao().getCustomerById(customerId)!!
        assertEquals("Ali Manual", after.name)
        assertEquals(5_000_000L, after.updatedAt)
        assertEquals(1, customerRepository.getHistory(customerId)!!.invoices.size)
    }

    @Test
    fun onlyEmptyLinksAreFilledExistingLinksStay() = runBlocking {
        val ali = issueInvoice("Ali", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 1_000_000L)
        val sara = issueInvoice("Sara", PHONE_SARA, unitPriceMinor = 1_000L, occurredAt = 2_000_000L)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE documents SET customer_id = NULL WHERE id = '${ali.id}'"
        )

        val result = backfill.linkOlderInvoices()
        assertEquals(1, result.linked)
        assertEquals(0, result.customersCreated)

        assertEquals(ali.customerId, database.documentMediaDao().getDocumentById(ali.id)!!.customerId)
        assertEquals(sara.customerId, database.documentMediaDao().getDocumentById(sara.id)!!.customerId)
    }

    @Test
    fun unreadableInvoiceIsSkippedAndReported() = runBlocking {
        val issued = issueInvoice("Ali", PHONE_ALI, unitPriceMinor = 1_500L, occurredAt = 1_000_000L)
        makeAllInvoicesLegacyAndForgetCustomers()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE documents SET snapshot_json = 'not json' WHERE id = '${issued.id}'"
        )

        val result = backfill.linkOlderInvoices()
        assertEquals(0, result.linked)
        assertEquals(0, result.customersCreated)
        assertEquals(1, result.skipped)
        assertEquals(0, customerRepository.listCustomers().size)
        assertEquals(1, backfill.countUnlinkedInvoices())
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

        assertFalse(InvoiceLinkBackfillRepository.canBackfill())
        try {
            backfill.linkOlderInvoices()
            fail("A cashier without a membership must not be able to link invoices")
        } catch (expected: PermissionDeniedException) {
            assertTrue(true)
        }
    }

    // ---------------------------------------------------------------

    /** Recreates the "before Phase 6" situation: no links, no customers. */
    private fun makeAllInvoicesLegacyAndForgetCustomers() {
        val db = database.openHelper.writableDatabase
        db.execSQL("UPDATE documents SET customer_id = NULL")
        db.execSQL("DELETE FROM customers")
    }

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
            business = InvoiceBusinessSnapshot(name = "Backfill Shop"),
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
            }
}

