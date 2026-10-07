package com.dailymoneyregister.feature.customers.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

data class PreparedWhatsAppMessage(
    val text: String,
    val recipientPhone: String,
    val attachment: WhatsAppAttachment? = null
)

data class WhatsAppAttachment(
    val contentUri: Uri,
    val mimeType: String,
    val subject: String? = null
)

/** Communication-only Android/WhatsApp launch boundary. */
object WhatsAppTransport {
    internal const val WHATSAPP_PACKAGE = "com.whatsapp"
    internal const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    fun share(
        context: Context,
        prepared: PreparedWhatsAppMessage,
        chooserTitle: String = "Share via WhatsApp"
    ) {
        val baseIntent = buildSendIntent(context, prepared)
        if (launchPackage(context, baseIntent, WHATSAPP_PACKAGE)) return
        if (launchPackage(context, baseIntent, WHATSAPP_BUSINESS_PACKAGE)) return

        val chooser = Intent.createChooser(baseIntent, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    internal fun buildSendIntent(
        context: Context,
        prepared: PreparedWhatsAppMessage
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        type = prepared.attachment?.mimeType ?: "text/plain"
        putExtra(Intent.EXTRA_TEXT, prepared.text)
        recipientJid(prepared.recipientPhone)?.let { putExtra("jid", it) }

        prepared.attachment?.let { attachment ->
            putExtra(Intent.EXTRA_STREAM, attachment.contentUri)
            attachment.subject?.takeIf(String::isNotBlank)?.let {
                putExtra(Intent.EXTRA_SUBJECT, it)
            }
            clipData = ClipData.newUri(
                context.contentResolver,
                attachment.subject ?: "WhatsApp attachment",
                attachment.contentUri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    internal fun recipientJid(canonicalPhone: String): String? =
        canonicalPhone.filter(Char::isDigit).takeIf(String::isNotBlank)
            ?.let { "$it@s.whatsapp.net" }

    private fun launchPackage(
        context: Context,
        baseIntent: Intent,
        packageName: String
    ): Boolean = runCatching {
        context.startActivity(Intent(baseIntent).setPackage(packageName))
        true
    }.getOrDefault(false)
}
