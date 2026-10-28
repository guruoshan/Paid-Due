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
    fun legacyCashierMembershipNeverAuthorizesActions() {
        PermissionAction.entries.forEach { action ->
            assertFalse(PermissionChecker.isPermitted(cashier, emptyMembership, action))
            assertFalse(
                PermissionChecker.isPermitted(
                    cashier,
                    emptyMembership.copy(canCreateSale = true, canRefund = true, canViewReports = true),
                    action
                )
            )
        }
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
    fun legacyPermissionFlagsDoNotGrantCashierAccess() {
        val enabled = emptyMembership.copy(
            canCreateCreditSale = true,
            canUseCoupon = true,
            canRefund = true,
            canVoid = true,
            canCorrectAmount = true,
            canChangePaymentMethod = true,
            canViewBroadHistory = true,
            canManageProducts = true
        )
        PermissionAction.entries.forEach { action ->
            assertFalse(PermissionChecker.isPermitted(cashier, enabled, action))
        }
    }

    @Test
    fun sessionGateRejectsLegacyCashierAndAllowsValidAdmin() {
        val legacyCashierSession = activeSession(emptyMembership)
        PermissionAction.entries.forEach { action ->
            assertFalse(PermissionChecker.isPermitted(legacyCashierSession, action))
        }

        val adminSession = legacyCashierSession.copy(
            user = admin,
            membership = emptyMembership.copy(userId = admin.id, role = UserRole.ADMIN)
        )
        PermissionAction.entries.forEach { action ->
            assertTrue(PermissionChecker.isPermitted(adminSession, action))
        }
        assertFalse(
            PermissionChecker.isPermitted(
                adminSession.copy(user = admin.copy(isActive = false)),
                PermissionAction.CREATE_SALE
            )
        )
        assertFalse(
            PermissionChecker.isPermitted(
                adminSession.copy(branch = adminSession.branch.copy(businessId = "other-business")),
                PermissionAction.CREATE_SALE
            )
        )
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