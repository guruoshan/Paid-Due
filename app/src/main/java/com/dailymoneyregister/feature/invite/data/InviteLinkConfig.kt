package com.dailymoneyregister.feature.invite.data

import com.dailymoneyregister.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public Paid&Due invitation-link configuration.
 *
 * The public URL is NOT invitation authority.
 * Invite validity continues to come from InviteStore.
 *
 * Until Paid&Due owns and deploys a real HTTPS invite endpoint,
 * invitation creation fails closed rather than sending users to an
 * unrelated product/domain.
 */
@Singleton
open class InviteLinkConfig @Inject constructor() {

    open fun configuredBaseUrl(): String? {
        val value =
            BuildConfig.PAID_DUE_PUBLIC_INVITE_BASE_URL
                .trim()
                .trimEnd('/')

        if (value.isBlank()) {
            return null
        }

        if (!value.startsWith("https://")) {
            return null
        }

        return value
    }

    fun requireConfiguredBaseUrl(): String =
        configuredBaseUrl()
            ?: throw IllegalStateException(
                "Paid&Due employee invitations are not yet configured. " +
                    "Configure PAID_DUE_PUBLIC_INVITE_BASE_URL with a " +
                    "Paid&Due-owned HTTPS invite endpoint."
            )
}
