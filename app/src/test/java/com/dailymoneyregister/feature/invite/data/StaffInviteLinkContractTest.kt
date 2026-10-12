package com.dailymoneyregister.feature.invite.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffInviteLinkContractTest {
    private val validToken = "A".repeat(43)

    @Test
    fun acceptsCanonicalGitHubPagesInvite() {
        assertTrue(StaffInviteLinkContract.isValid(StaffInviteLinkContract.LANDING_PREFIX + validToken))
    }

    @Test
    fun rejectsSupabaseStaffOpenUrl() {
        assertFalse(
            StaffInviteLinkContract.isValid(
                "https://ttvdpgxqvvtjnxxfkoui.supabase.co/functions/v1/staff-open#$validToken"
            )
        )
    }

    @Test
    fun rejectsUnexpectedHostOrPath() {
        assertFalse(
            StaffInviteLinkContract.isValid(
                "https://example.com/Paid-Due/invite/#$validToken"
            )
        )
        assertFalse(
            StaffInviteLinkContract.isValid(
                "https://guruoshan.github.io/Paid-Due/other/#$validToken"
            )
        )
    }

    @Test
    fun rejectsInvalidOrExtendedToken() {
        assertFalse(StaffInviteLinkContract.isValid(StaffInviteLinkContract.LANDING_PREFIX + "not-a-token"))
        assertFalse(StaffInviteLinkContract.isValid(StaffInviteLinkContract.LANDING_PREFIX + validToken + "?x=1"))
    }
}
