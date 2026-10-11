package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.UserRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedMembershipPolicyTest {
    private fun membership(
        userId: String = "user-1",
        branchId: String = "branch-1",
        role: UserRole = UserRole.ADMIN
    ) = UserBranchMembershipEntity(
        id = "membership-1",
        userId = userId,
        branchId = branchId,
        role = role,
        createdAt = 0L
    )

    @Test
    fun acceptsOnlyExactUserBranchAndRoleMembership() {
        assertTrue(trustedMembershipMatches("user-1", "branch-1", UserRole.ADMIN, membership()))
        assertFalse(trustedMembershipMatches("other-user", "branch-1", UserRole.ADMIN, membership()))
        assertFalse(trustedMembershipMatches("user-1", "other-branch", UserRole.ADMIN, membership()))
        assertFalse(trustedMembershipMatches("user-1", "branch-1", UserRole.CASHIER, membership()))
    }

    @Test
    fun missingMembershipFailsClosed() {
        assertFalse(trustedMembershipMatches("user-1", "branch-1", UserRole.ADMIN, null))
    }
}
