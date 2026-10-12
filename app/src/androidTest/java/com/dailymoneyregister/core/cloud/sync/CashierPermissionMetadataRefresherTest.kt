package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CashierPermissionMetadataRefresherTest {
    private lateinit var database: DmrDatabase
    private lateinit var refresher: CashierPermissionMetadataRefresher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = DmrDatabase.createInMemory(context)
        refresher = CashierPermissionMetadataRefresher(database)
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun allFourteenFlagsUpdateWithoutClearingActiveSession() {
        val business = BusinessEntity("business-a", "Business A", 1, 1)
        val branch = BranchEntity(
            id = "branch-a",
            businessId = "business-a",
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 500,
            createdAt = 1,
            updatedAt = 1
        )
        val user = UserAccountEntity(
            id = "cashier-a",
            businessId = "business-a",
            displayName = "Cashier",
            username = "+96890000101",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "test-hash",
            credentialSalt = "test-salt",
            createdAt = 1,
            updatedAt = 1
        )
        val membership = UserBranchMembershipEntity(
            id = "membership-a",
            userId = "cashier-a",
            branchId = "branch-a",
            role = UserRole.CASHIER,
            canCreateSale = false,
            canAcceptCash = false,
            canAcceptOnline = false,
            canCreateCreditSale = false,
            canUseCoupon = false,
            canIssueInvoice = false,
            canIssueReceipt = false,
            canRefund = false,
            canVoid = false,
            canCorrectAmount = false,
            canChangePaymentMethod = false,
            canViewBroadHistory = false,
            canManageProducts = false,
            canViewReports = false,
            createdAt = 1
        )
        database.businessBranchDao().insertBusiness(business)
        database.businessBranchDao().insertBranch(branch)
        database.userDao().insertUser(user)
        database.userDao().insertMembership(membership)
        val active = ActiveSession(business, branch, user, membership)
        SessionManager.setSession(active)

        val metadataPermissions = JSONObject()
            .put("can_create_sale", true)
            .put("can_accept_cash", true)
            .put("can_accept_online", true)
            .put("can_create_credit_sale", true)
            .put("can_use_coupon", true)
            .put("can_issue_invoice", true)
            .put("can_issue_receipt", true)
            .put("can_refund", true)
            .put("can_void", true)
            .put("can_correct_amount", true)
            .put("can_change_payment_method", true)
            .put("can_view_broad_history", true)
            .put("can_manage_products", true)
            .put("can_view_reports", true)

        val metadata = JSONObject()
            .put("membership_id", "membership-a")
            .put("business_id", "business-a")
            .put("branch_id", "branch-a")
            .put("role", "CASHIER")
            .put("permissions", metadataPermissions)
        assertTrue(refresher.applyMetadataForActiveCashier(active, metadata))

        val current = SessionManager.currentSession.value
        val persisted = database.userDao().getMembership("cashier-a", "branch-a")
        assertNotNull(persisted)
        assertNotNull(current)
        assertEquals("cashier-a", current?.user?.id)
        assertEquals("business-a", current?.business?.id)
        assertEquals("branch-a", current?.branch?.id)
        assertEquals("membership-a", current?.membership?.id)
        assertTrue(current?.membership?.canCreateSale == true)
        assertTrue(persisted?.canCreateSale == true)
        assertTrue(current?.membership?.canAcceptCash == true)
        assertTrue(persisted?.canAcceptCash == true)
        assertTrue(current?.membership?.canAcceptOnline == true)
        assertTrue(persisted?.canAcceptOnline == true)
        assertTrue(current?.membership?.canCreateCreditSale == true)
        assertTrue(persisted?.canCreateCreditSale == true)
        assertTrue(current?.membership?.canUseCoupon == true)
        assertTrue(persisted?.canUseCoupon == true)
        assertTrue(current?.membership?.canIssueInvoice == true)
        assertTrue(persisted?.canIssueInvoice == true)
        assertTrue(current?.membership?.canIssueReceipt == true)
        assertTrue(persisted?.canIssueReceipt == true)
        assertTrue(current?.membership?.canRefund == true)
        assertTrue(persisted?.canRefund == true)
        assertTrue(current?.membership?.canVoid == true)
        assertTrue(persisted?.canVoid == true)
        assertTrue(current?.membership?.canCorrectAmount == true)
        assertTrue(persisted?.canCorrectAmount == true)
        assertTrue(current?.membership?.canChangePaymentMethod == true)
        assertTrue(persisted?.canChangePaymentMethod == true)
        assertTrue(current?.membership?.canViewBroadHistory == true)
        assertTrue(persisted?.canViewBroadHistory == true)
        assertTrue(current?.membership?.canManageProducts == true)
        assertTrue(persisted?.canManageProducts == true)
        assertTrue(current?.membership?.canViewReports == true)
        assertTrue(persisted?.canViewReports == true)
        assertFalse(current?.requiresPinChange ?: true)
    }
}
