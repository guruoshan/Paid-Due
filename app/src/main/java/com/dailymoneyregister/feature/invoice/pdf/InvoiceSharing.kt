package com.dailymoneyregister.feature.invoice.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.invoice.data.IssuedInvoice
import java.io.File

/**
 * Launches the Android share sheet / WhatsApp for a rendered invoice PDF.
 * Needs a FileProvider with authority "<applicationId>.fileprovider"
 * exposing cache/invoices (see res/xml/invoice_file_paths.xml).
 */
object InvoiceSharing {

    fun sharePdf(context: Context, file: File) {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.inv_pdf_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun shareWhatsApp(context: Context, invoice: IssuedInvoice, file: File) {
        val s = invoice.snapshot
        val uri = uriFor(context, file)

        val message = context.getString(
            R.string.inv_pdf_whatsapp_message,
            s.customer.name,
            invoice.displayNumber,
            s.business.name ?: context.getString(R.string.app_name)
        )

        // WhatsApp does not always honour EXTRA_TEXT together with a PDF,
        // so the exact message is also copied for a one-paste fallback.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.invoice), message))
        Toast.makeText(context, R.string.inv_pdf_message_copied, Toast.LENGTH_LONG).show()

        val phoneDigits = s.customer.phone.filter(Char::isDigit)

        fun send(packageName: String?): Boolean = runCatching {
            val intent = buildSendIntent(context, uri, message).apply {
                if (phoneDigits.isNotEmpty()) {
                    // Best-effort recipient hint. WhatsApp may ignore it.
                    putExtra("jid", "$phoneDigits@s.whatsapp.net")
                }
                if (packageName != null) setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (send("com.whatsapp")) return
        if (send("com.whatsapp.w4b")) return

        val fallback = buildSendIntent(context, uri, message)
        context.startActivity(
            Intent.createChooser(fallback, context.getString(R.string.inv_pdf_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun buildSendIntent(context: Context, uri: Uri, message: String) =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.invoice))
            clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.invoice), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
