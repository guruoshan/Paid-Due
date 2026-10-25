package com.dailymoneyregister.feature.customers.ui

import com.dailymoneyregister.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

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
    private const val WHATSAPP_JID_SUFFIX = "@s.whatsapp.net"

    fun share(
        context: Context,
        prepared: PreparedWhatsAppMessage,
        chooserTitle: String = context.getString(R.string.audit_share_via_whatsapp)
    ) {
        // A media ACTION_SEND without a valid JID can open WhatsApp's generic
        // media/status flow. Never launch it without the intended recipient.
        if (recipientJid(prepared.recipientPhone) == null) {
            Toast.makeText(
                context,
                context.getString(R.string.audit_receipt_recipient_phone_required),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val baseIntent = buildSendIntent(context, prepared)
        if (launchPackage(context, baseIntent, WHATSAPP_PACKAGE)) return
        if (launchPackage(context, baseIntent, WHATSAPP_BUSINESS_PACKAGE)) return

        // Only offer the generic Android chooser after the target packages are
        // unavailable. The recipient JID remains attached to the send intent.
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
        // Keep the recipient on the same send intent as any receipt attachment.
        putExtra("jid", requireNotNull(recipientJid(prepared.recipientPhone)))

        prepared.attachment?.let { attachment ->
            putExtra(Intent.EXTRA_STREAM, attachment.contentUri)
            attachment.subject?.takeIf(String::isNotBlank)?.let {
                putExtra(Intent.EXTRA_SUBJECT, it)
            }
            // EXTRA_STREAM + URI grant is sufficient. Android copies the stream
            // URI into ClipData when starting the activity. Setting ClipData
            // separately can make some WhatsApp versions treat the image as a
            // generic media share (Status) instead of the target conversation.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * WhatsApp JIDs require an international phone number containing 7–15
     * ASCII digits (country code included), without '+' or formatting chars.
     * Reject missing/obviously invalid values rather than opening a generic
     * WhatsApp media composer that can land on Status.
     */
    internal fun recipientJid(phone: String): String? {
        val digits = phone.filter { it in '0'..'9' }
        if (digits.length !in 7..15) return null
        return "$digits$WHATSAPP_JID_SUFFIX"
    }

    private fun launchPackage(
        context: Context,
        baseIntent: Intent,
        packageName: String
    ): Boolean = runCatching {
        context.startActivity(Intent(baseIntent).setPackage(packageName))
        true
    }.getOrDefault(false)
}
