package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminSessionScopePolicyTest {
    @Test
    fun exactServerIdentityAndTenantScopeIsAccepted() {
        assertTrue(
            adminSessionScopeMatches(
                actualMembershipId = "membership-1",
                actualLocalUserId = "admin-1",
                actualBusinessId = "business-1",
                actualBranchId = "branch-1",
                expectedMembershipId = "membership-1",
                expectedLocalUserId = "admin-1",
                expectedBusinessId = "business-1",
                expectedBranchId = "branch-1"
            )
        )
    }

    @Test
    fun mismatchedLocalUserIsRejectedEvenWhenTenantAndMembershipMatch() {
        assertFalse(
            adminSessionScopeMatches(
                actualMembershipId = "membership-1",
                actualLocalUserId = "admin-2",
                actualBusinessId = "business-1",
                actualBranchId = "branch-1",
                expectedMembershipId = "membership-1",
                expectedLocalUserId = "admin-1",
                expectedBusinessId = "business-1",
                expectedBranchId = "branch-1"
            )
        )
    }

    @Test
    fun missingExpectedLocalUserFailsClosed() {
        assertFalse(
            adminSessionScopeMatches(
                actualMembershipId = "membership-1",
                actualLocalUserId = "admin-1",
                actualBusinessId = "business-1",
                actualBranchId = "branch-1",
                expectedMembershipId = "membership-1",
                expectedLocalUserId = "",
                expectedBusinessId = "business-1",
                expectedBranchId = "branch-1"
            )
        )
    }
}
