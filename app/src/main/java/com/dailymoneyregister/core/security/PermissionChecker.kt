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
        // ADMIN is the only supported product role. Legacy CASHIER records may
        // still be decoded for historical attribution, but never authorize actions.
        return user.role == UserRole.ADMIN
    }

    /**
     * Session-aware gate for presentation/read actions. Legacy CASHIER sessions
     * are rejected even if stale local rows or memberships remain on disk.
     */
    fun isPermitted(session: ActiveSession?, action: PermissionAction): Boolean {
        session ?: return false
        if (session.requiresPinChange || !session.user.isActive) return false
        if (session.user.businessId != session.business.id) return false
        if (session.branch.businessId != session.business.id) return false
        if (session.user.role != UserRole.ADMIN) return false

        return isPermitted(session.user, session.membership, action)
    }
}

class PermissionDeniedException(message: String) : SecurityException(message)
