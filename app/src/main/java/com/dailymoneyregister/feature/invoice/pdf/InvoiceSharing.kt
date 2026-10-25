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
import com.dailymoneyregister.feature.customers.ui.PreparedWhatsAppMessage
import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import com.dailymoneyregister.feature.customers.ui.WhatsAppAttachment
import com.dailymoneyregister.feature.customers.ui.WhatsAppTransport
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

        val message = UniversalWhatsAppMessageComposer.compose(
            UniversalWhatsAppMessageComposer.Invoice(
                party = UniversalWhatsAppMessageComposer.Party(
                    businessName = s.business.name ?: context.getString(R.string.app_name),
                    customerName = s.customer.name,
                    customerPhone = s.customer.phone
                    labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
                ),
                formattedAmount = formatInvoiceMoney(
                    amountMinor = s.grandTotalMinor,
                    currencyCode = s.currencyCode,
                    decimalPlaces = s.currencyDecimalPlaces
                ),
                reference = invoice.displayNumber,
                documentDateTime = formatInvoiceDateTime(s.createdAt)
            )
        )

        // WhatsApp does not always honour EXTRA_TEXT together with a PDF,
        // so the exact message is also copied for a one-paste fallback.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.invoice), message))
        Toast.makeText(context, R.string.inv_pdf_message_copied, Toast.LENGTH_LONG).show()

        WhatsAppTransport.share(
            context = context,
            prepared = PreparedWhatsAppMessage(
                text = message,
                recipientPhone = s.customer.phone,
                attachment = WhatsAppAttachment(
                    contentUri = uri,
                    mimeType = "application/pdf",
                    subject = context.getString(R.string.invoice)
                )
            ),
            chooserTitle = context.getString(R.string.inv_pdf_share_chooser)
        )
    }

    private fun formatInvoiceMoney(
        amountMinor: Long,
        currencyCode: String,
        decimalPlaces: Int
    ): String = "$currencyCode " + BigDecimal.valueOf(amountMinor)
        .movePointLeft(decimalPlaces)
        .setScale(decimalPlaces, RoundingMode.UNNECESSARY)
        .toPlainString()

    private fun formatInvoiceDateTime(createdAt: Long): String =
        DateTimeFormatter
            .ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(createdAt))

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
