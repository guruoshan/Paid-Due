package com.dailymoneyregister.feature.customers.ui

import android.content.Context
import android.content.Intent

/**
 * CUSTOMER_WHATSAPP_V1_R2
 *
 * Communication-only WhatsApp launcher.
 *
 * BOARDLOCK:
 * - No financial writes.
 * - No database access.
 * - No repository access.
 * - No country code invention.
 * - Customer phone is supplied by the authoritative loaded customer.
 */
object CustomerWhatsAppLauncher {

    private const val WHATSAPP =
        "com.whatsapp"

    private const val WHATSAPP_BUSINESS =
        "com.whatsapp.w4b"

    fun share(
        context: Context,
        message: String,
        customerPhone: String
    ) {
        /*
         * Preserve the authoritative phone identity.
         *
         * We only remove formatting characters for WhatsApp's
         * recipient JID. We do NOT prepend or infer a country code.
         */
        val phoneDigits =
            customerPhone.filter(Char::isDigit)

        val baseIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"

                putExtra(
                    Intent.EXTRA_TEXT,
                    message
                )

                if (phoneDigits.isNotBlank()) {
                    putExtra(
                        "jid",
                        "$phoneDigits@s.whatsapp.net"
                    )
                }
            }

        if (
            launchPackage(
                context = context,
                baseIntent = baseIntent,
                packageName = WHATSAPP
            )
        ) {
            return
        }

        if (
            launchPackage(
                context = context,
                baseIntent = baseIntent,
                packageName = WHATSAPP_BUSINESS
            )
        ) {
            return
        }

        /*
         * Fail open to Android's chooser if neither WhatsApp
         * package accepts the targeted intent.
         *
         * The message is preserved exactly.
         */
        context.startActivity(
            Intent.createChooser(
                baseIntent,
                "Share via WhatsApp"
            )
        )
    }

    private fun launchPackage(
        context: Context,
        baseIntent: Intent,
        packageName: String
    ): Boolean {
        val intent =
            Intent(baseIntent).apply {
                setPackage(packageName)
            }

        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
