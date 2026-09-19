package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
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
    fun allFalseMembershipDeniesEveryActionForCashier() {
        PermissionAction.entries.forEach { action ->
            assertFalse(PermissionChecker.isPermitted(cashier, emptyMembership, action))
        }
    }

    @Test
    fun eachPermissionActionMapsToItsMembershipFlag() {
        PermissionAction.entries.forEach { action ->
            val membership = when (action) {
                PermissionAction.CREATE_SALE -> emptyMembership.copy(canCreateSale = true)
                PermissionAction.ACCEPT_CASH -> emptyMembership.copy(canAcceptCash = true)
                PermissionAction.ACCEPT_ONLINE -> emptyMembership.copy(canAcceptOnline = true)
                PermissionAction.CREATE_CREDIT_SALE -> emptyMembership.copy(canCreateCreditSale = true)
                PermissionAction.USE_COUPON -> emptyMembership.copy(canUseCoupon = true)
                PermissionAction.ISSUE_INVOICE -> emptyMembership.copy(canIssueInvoice = true)
                PermissionAction.ISSUE_RECEIPT -> emptyMembership.copy(canIssueReceipt = true)
                PermissionAction.REFUND -> emptyMembership.copy(canRefund = true)
                PermissionAction.VOID -> emptyMembership.copy(canVoid = true)
                PermissionAction.CORRECT_AMOUNT -> emptyMembership.copy(canCorrectAmount = true)
                PermissionAction.CHANGE_PAYMENT_METHOD -> emptyMembership.copy(canChangePaymentMethod = true)
                PermissionAction.VIEW_BROAD_HISTORY -> emptyMembership.copy(canViewBroadHistory = true)
                PermissionAction.MANAGE_PRODUCTS -> emptyMembership.copy(canManageProducts = true)
                PermissionAction.VIEW_REPORTS -> emptyMembership.copy(canViewReports = true)
            }
            PermissionAction.entries.forEach { checkedAction ->
                if (checkedAction == action) {
                    assertTrue(PermissionChecker.isPermitted(cashier, membership, checkedAction))
                } else {
                    assertFalse(PermissionChecker.isPermitted(cashier, membership, checkedAction))
                }
            }
        }
    }
}
