package com.dailymoneyregister.feature.invite.data

/**
 * The public invitation landing page is hosted on GitHub Pages.
 *
 * Keep this contract aligned with supabase/functions/staff-invite/invite-link.ts.
 * The raw token must remain in the URL fragment, never in a query or server log.
 */
internal object StaffInviteLinkContract {
    const val LANDING_PREFIX = "https://guruoshan.github.io/Paid-Due/invite/#"

    private val tokenRegex = Regex("""^[A-Za-z0-9_-]{32,128}$""")

    fun isValid(url: String): Boolean {
        if (!url.startsWith(LANDING_PREFIX)) return false
        return tokenRegex.matches(url.removePrefix(LANDING_PREFIX))
    }
}
