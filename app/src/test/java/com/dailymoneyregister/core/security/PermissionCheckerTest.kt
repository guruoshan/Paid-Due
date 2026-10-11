package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCheckerTest {
    private val cashier = UserAccountEntity(
        id = "cashier", businessId = "business", displayName = "Cashier", username = "cashier",
        role = UserRole.CASHIER, authType = AuthType.PIN, credentialHash = "hash",
        credentialSalt = "salt", createdAt = 0L, updatedAt = 0L
    )
    private val admin = cashier.copy(role = UserRole.ADMIN, authType = AuthType.PASSWORD)
    private val emptyMembership = UserBranchMembershipEntity(
        id = "membership", userId = "cashier", branchId = "branch", role = UserRole.CASHIER,
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
        createdAt = 0L
    )

    @Test
    fun adminIsPermittedForEveryAction() {
        PermissionAction.entries.forEach { action ->
            assertTrue(PermissionChecker.isPermitted(admin, null, action))
        }
    }

    @Test
    fun nullMembershipDeniesNonAdmin() {
        PermissionAction.entries.forEach { action ->
            assertFalse(PermissionChecker.isPermitted(cashier, null, action))
        }
    }

    @Test
    fun documentPermissionsIgnoreLegacyFlagsForCashierWithMembership() {
        assertTrue(PermissionChecker.isPermitted(cashier, emptyMembership, PermissionAction.ISSUE_INVOICE))
        assertTrue(PermissionChecker.isPermitted(cashier, emptyMembership, PermissionAction.ISSUE_RECEIPT))
    }

    @Test
    fun reportsAreAdminOnlyRegardlessOfLegacyCashierFlag() {
        assertFalse(PermissionChecker.isPermitted(cashier, emptyMembership, PermissionAction.VIEW_REPORTS))
        assertFalse(
            PermissionChecker.isPermitted(
                cashier,
                emptyMembership.copy(canViewReports = true),
                PermissionAction.VIEW_REPORTS
            )
        )
        assertTrue(PermissionChecker.isPermitted(admin, null, PermissionAction.VIEW_REPORTS))
    }

    @Test
    fun sensitiveMembershipPermissionsRemainUnchanged() {
        val cases = listOf(
            PermissionAction.CREATE_CREDIT_SALE to emptyMembership.copy(canCreateCreditSale = true),
            PermissionAction.USE_COUPON to emptyMembership.copy(canUseCoupon = true),
            PermissionAction.REFUND to emptyMembership.copy(canRefund = true),
            PermissionAction.VOID to emptyMembership.copy(canVoid = true),
            PermissionAction.CORRECT_AMOUNT to emptyMembership.copy(canCorrectAmount = true),
            PermissionAction.CHANGE_PAYMENT_METHOD to emptyMembership.copy(canChangePaymentMethod = true)
        )

        cases.forEach { (action, enabledMembership) ->
            assertFalse(PermissionChecker.isPermitted(cashier, emptyMembership, action))
            assertTrue(PermissionChecker.isPermitted(cashier, enabledMembership, action))
        }
    }

    @Test
    fun sessionGateRejectsMissingStaleAndInactiveCashierMemberships() {
        val valid = activeSession(emptyMembership)
        assertTrue(PermissionChecker.isPermitted(valid, PermissionAction.ISSUE_INVOICE))
        assertTrue(PermissionChecker.isPermitted(valid, PermissionAction.ISSUE_RECEIPT))
        assertFalse(PermissionChecker.isPermitted(valid, PermissionAction.VIEW_REPORTS))

        assertFalse(PermissionChecker.isPermitted(valid.copy(membership = null), PermissionAction.ISSUE_INVOICE))
        assertFalse(PermissionChecker.isPermitted(valid.copy(membership = null), PermissionAction.ISSUE_RECEIPT))
        assertFalse(PermissionChecker.isPermitted(valid.copy(membership = null), PermissionAction.VIEW_REPORTS))
        assertFalse(
            PermissionChecker.isPermitted(
                valid.copy(membership = emptyMembership.copy(branchId = "other-branch")),
                PermissionAction.ISSUE_RECEIPT
            )
        )
        assertFalse(
            PermissionChecker.isPermitted(
                valid.copy(user = cashier.copy(isActive = false)),
                PermissionAction.ISSUE_INVOICE
            )
        )
    }


    @Test
    fun offlineLeaseRestrictsCashierToLocalCashOperations() {
        val offlineMembership = emptyMembership.copy(
            canCreateSale = true,
            canAcceptCash = true,
            canAcceptOnline = true,
            canCreateCreditSale = true,
            canUseCoupon = true,
            canRefund = true,
            canVoid = true
        )
        SessionManager.setSession(activeSession(offlineMembership).copy(isOfflineLease = true))
        try {
            assertTrue(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.CREATE_SALE))
            assertTrue(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.ACCEPT_CASH))
            assertFalse(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.ACCEPT_ONLINE))
            assertFalse(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.CREATE_CREDIT_SALE))
            assertFalse(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.USE_COUPON))
            assertFalse(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.REFUND))
            assertFalse(PermissionChecker.isPermitted(cashier, offlineMembership, PermissionAction.VOID))
        } finally {
            SessionManager.clearSession()
        }
    }

    @Test
    fun sessionLockDoesNotDestroySessionAndCanBeCleared() {
        SessionManager.setSession(activeSession(emptyMembership))
        try {
            SessionManager.lockSession()
            assertTrue(SessionManager.isLocked.value)
            assertTrue(SessionManager.currentSession.value != null)
            SessionManager.unlockSession()
            assertFalse(SessionManager.isLocked.value)
            SessionManager.lockSession()
            SessionManager.clearSession()
            assertFalse(SessionManager.isLocked.value)
            assertTrue(SessionManager.currentSession.value == null)
        } finally {
            SessionManager.clearSession()
        }
    }

    private fun activeSession(membership: UserBranchMembershipEntity?): ActiveSession = ActiveSession(
        business = BusinessEntity(
            id = "business",
            name = "Business",
            createdAt = 0L,
            updatedAt = 0L
        ),
        branch = BranchEntity(
            id = "branch",
            businessId = "business",
            name = "Branch",
            code = "BR",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 0L,
            updatedAt = 0L
        ),
        user = cashier,
        membership = membership
    )
}