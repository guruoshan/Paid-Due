package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.UserRole

enum class PermissionAction {
    CREATE_SALE,
    ACCEPT_CASH,
    ACCEPT_ONLINE,
    CREATE_CREDIT_SALE,
    USE_COUPON,
    ISSUE_INVOICE,
    ISSUE_RECEIPT,
    REFUND,
    VOID,
    CORRECT_AMOUNT,
    CHANGE_PAYMENT_METHOD,
    VIEW_BROAD_HISTORY,
    MANAGE_PRODUCTS,
    VIEW_REPORTS
}

object PermissionChecker {

    /**
     * Asserts that a user has permission to perform an action on a branch.
     * Throws PermissionDeniedException if not permitted.
     */
    fun assertPermitted(
        user: UserAccountEntity,
        membership: UserBranchMembershipEntity?,
        action: PermissionAction
    ) {
        if (!isPermitted(user, membership, action)) {
            throw PermissionDeniedException(
                "Permission denied: User '${user.displayName}' (${user.role}) is not authorized to perform $action"
            )
        }
    }

    fun isPermitted(
        user: UserAccountEntity,
        membership: UserBranchMembershipEntity?,
        action: PermissionAction
    ): Boolean {
        // Admin has full permissions
        if (user.role == UserRole.ADMIN) return true

        // Cashier requires an active branch membership
        if (membership == null) return false

        return when (action) {
            PermissionAction.CREATE_SALE -> membership.canCreateSale
            PermissionAction.ACCEPT_CASH -> membership.canAcceptCash
            PermissionAction.ACCEPT_ONLINE -> membership.canAcceptOnline
            PermissionAction.CREATE_CREDIT_SALE -> membership.canCreateCreditSale
            PermissionAction.USE_COUPON -> membership.canUseCoupon
            PermissionAction.ISSUE_INVOICE -> membership.canIssueInvoice
            PermissionAction.ISSUE_RECEIPT -> membership.canIssueReceipt
            PermissionAction.REFUND -> membership.canRefund
            PermissionAction.VOID -> membership.canVoid
            PermissionAction.CORRECT_AMOUNT -> membership.canCorrectAmount
            PermissionAction.CHANGE_PAYMENT_METHOD -> membership.canChangePaymentMethod
            PermissionAction.VIEW_BROAD_HISTORY -> membership.canViewBroadHistory
            PermissionAction.MANAGE_PRODUCTS -> membership.canManageProducts
            PermissionAction.VIEW_REPORTS -> membership.canViewReports
        }
    }
}

class PermissionDeniedException(message: String) : SecurityException(message)
