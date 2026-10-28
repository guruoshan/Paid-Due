package com.dailymoneyregister.feature.register.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import java.io.File
import java.io.FileOutputStream

/**
 * BOARDLOCK_UNIVERSAL_RECEIPT_IMAGE_V4
 *
 * The single PNG receipt renderer. Every WhatsApp message (cash, credit,
 * online, coupon payment, coupon purchase/top-up, refund-to-coupon) uses the
 * same header, the same footer and the same drawing code, so every receipt
 * image looks the same. It does not read or write financial state.
 */
object ReceiptRenderer {
    private const val WIDTH = 1080
    private const val MARGIN = 72f
    private const val LINE = 54f
    private const val THANK_YOU = "Thank you for your business."

    /** Already-resolved values for a receipt that has no merchandise items. */
    data class EventReceipt(
        val businessName: String,
        val title: String,
        val reference: String,
        val dateTime: String?,
        val customerName: String?,
        val rows: List<Row>,
        val heading: String = "RECEIPT",
        val partyLabel: String = "Customer"
    ) {
        data class Row(val label: String, val value: String)
    }

    fun render(context: Context, receipt: ReceiptReadModel): File =
        draw(context, receipt.reference, buildLines(receipt, context))

    fun renderEvent(context: Context, event: EventReceipt): File =
        draw(context, event.reference, buildEventLines(event, context))

    /** Business-level day-close card; values come from the existing day-close projection. */
    fun renderDaySummary(context: Context, summary: EventReceipt): File =
        draw(context, summary.reference, buildDaySummaryLines(summary, context))

    internal fun buildDaySummaryLines(summary: EventReceipt, context: Context): List<Line> = buildList {
        addHeader(summary.businessName, summary.heading, context)
        add(Line(summary.title, bold = true))
        summary.reference.takeIf(String::isNotBlank)?.let {
            add(Line(context.getString(R.string.audit_receipt_reference, it)))
        }
        summary.dateTime?.takeIf(String::isNotBlank)?.let {
            add(Line(context.getString(R.string.audit_receipt_date_time, it)))
        }
        add(Line(""))
        summary.rows.forEach { add(Line(it.label + ": " + it.value)) }
        add(Line(""))
        add(Line("Powered by Paid&Due", bold = true))
    }

    internal fun buildLines(receipt: ReceiptReadModel): List<Line> = buildList {
        addHeader(receipt.businessName, "RECEIPT")
        add(Line("Reference: ${receipt.reference}"))
        add(Line("Date & Time: ${receipt.formattedTransactionDateTime()}"))
        receipt.customerName?.let { add(Line("Customer: $it")) }
        add(Line(""))
        receipt.items.forEach {
            val quantity = if (it.quantity == 1) "" else " x ${it.quantity}"
            add(Line("${it.name}$quantity    ${receipt.formattedMoney(it.lineTotalMinor)}"))
        }
        add(Line(""))
        add(Line("Subtotal: ${receipt.formattedMoney(receipt.subtotalMinor)}"))
        if (receipt.taxMinor != 0L) add(Line("Tax: ${receipt.formattedMoney(receipt.taxMinor)}"))
        if (receipt.shippingMinor != 0L) add(Line("Shipping: ${receipt.formattedMoney(receipt.shippingMinor)}"))
        if (receipt.packingMinor != 0L) add(Line("Packing: ${receipt.formattedMoney(receipt.packingMinor)}"))
        add(Line("Total: ${receipt.formattedMoney(receipt.totalMinor)}", bold = true))
        add(Line("Payment: ${receipt.paymentSummary()}"))
        receipt.note?.takeIf(String::isNotBlank)?.let { add(Line("Note: $it")) }
        addFooter()
    }


    internal fun buildLines(receipt: ReceiptReadModel, context: Context): List<Line> = buildList {
        addHeader(receipt.businessName, context.getString(R.string.audit_receipt_heading), context)
        add(Line(context.getString(R.string.audit_receipt_reference, receipt.reference)))
        add(Line(context.getString(R.string.audit_receipt_date_time, receipt.formattedTransactionDateTime())))
        receipt.customerName?.let { add(Line(context.getString(R.string.audit_receipt_customer, it))) }
        add(Line(""))
        receipt.items.forEach {
            val quantity = if (it.quantity == 1) "" else " x " + it.quantity
            add(Line(it.name + quantity + "    " + receipt.formattedMoney(it.lineTotalMinor)))
        }
        add(Line(""))
        add(Line(context.getString(R.string.audit_receipt_subtotal, receipt.formattedMoney(receipt.subtotalMinor))))
        if (receipt.taxMinor != 0L) add(Line(context.getString(R.string.audit_receipt_tax, receipt.formattedMoney(receipt.taxMinor))))
        if (receipt.shippingMinor != 0L) add(Line(context.getString(R.string.audit_receipt_shipping, receipt.formattedMoney(receipt.shippingMinor))))
        if (receipt.packingMinor != 0L) add(Line(context.getString(R.string.audit_receipt_packing, receipt.formattedMoney(receipt.packingMinor))))
        add(Line(context.getString(R.string.audit_receipt_total, receipt.formattedMoney(receipt.totalMinor)), bold = true))
        add(Line(context.getString(R.string.audit_payment_prefix, localizedPaymentSummary(receipt, context))))
        receipt.note?.takeIf(String::isNotBlank)?.let { add(Line(context.getString(R.string.audit_receipt_note, it))) }
        addFooter(context)
    }

    private fun localizedPaymentSummary(receipt: ReceiptReadModel, context: Context): String =
        if (receipt.tenders.isEmpty()) {
            context.getString(R.string.audit_receipt_recorded_payment)
        } else {
            receipt.tenders.joinToString(" + ") { tender ->
                val method = when (tender.category) {
                    com.dailymoneyregister.core.model.enums.PaymentCategory.CASH -> context.getString(R.string.cash)
                    com.dailymoneyregister.core.model.enums.PaymentCategory.ONLINE -> context.getString(R.string.online)
                    com.dailymoneyregister.core.model.enums.PaymentCategory.CREDIT -> context.getString(R.string.credit)
                    com.dailymoneyregister.core.model.enums.PaymentCategory.COUPON -> context.getString(R.string.coupon)
                }
                method + ": " + receipt.formattedMoney(tender.amountMinor)
            }
        }


    internal fun buildEventLines(event: EventReceipt, context: Context): List<Line> = buildList {
        val heading = if (event.heading == "RECEIPT") context.getString(R.string.audit_receipt_heading) else event.heading
        addHeader(event.businessName, heading, context)
        add(Line(event.title, bold = true))
        event.reference.takeIf(String::isNotBlank)?.let { add(Line(context.getString(R.string.audit_receipt_reference, it))) }
        event.dateTime?.takeIf(String::isNotBlank)?.let { add(Line(context.getString(R.string.audit_receipt_date_time, it))) }
        event.customerName?.takeIf(String::isNotBlank)?.let {
            val customerLabel = if (event.partyLabel == "Customer") context.getString(R.string.audit_receipt_customer_label) else event.partyLabel
            add(Line(customerLabel + ": " + it))
        }
        add(Line(""))
        event.rows.forEach { add(Line(it.label + ": " + it.value)) }
        addFooter(context)
    }

    private fun MutableList<Line>.addHeader(businessName: String, heading: String, context: Context) {
        add(Line(businessName.trim().ifBlank { context.getString(R.string.audit_receipt_business_name) }, bold = true))
        add(Line(heading, bold = true))
    }

    private fun MutableList<Line>.addFooter(context: Context) {
        add(Line(""))
        add(Line(context.getString(R.string.audit_thank_you_business)))
        add(Line(context.getString(R.string.audit_message_footer)))
    }

    internal fun buildEventLines(event: EventReceipt): List<Line> = buildList {
        addHeader(event.businessName, event.heading)
        add(Line(event.title, bold = true))
        event.reference.takeIf(String::isNotBlank)?.let { add(Line("Reference: $it")) }
        event.dateTime?.takeIf(String::isNotBlank)?.let { add(Line("Date & Time: $it")) }
        event.customerName?.takeIf(String::isNotBlank)?.let { add(Line("${event.partyLabel}: $it")) }
        add(Line(""))
        event.rows.forEach { add(Line("${it.label}: ${it.value}")) }
        addFooter()
    }

    private fun MutableList<Line>.addHeader(businessName: String, heading: String) {
        add(Line(businessName.trim().ifBlank { "Paid&Due" }, bold = true))
        add(Line(heading, bold = true))
    }

    private fun MutableList<Line>.addFooter() {
        add(Line(""))
        add(Line(THANK_YOU))
        add(Line(UniversalWhatsAppMessageComposer.FOOTER))
    }

    private fun draw(context: Context, reference: String, contentLines: List<Line>): File {
        val height = maxOf(900, (MARGIN * 2 + contentLines.size * LINE).toInt())
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 30, 30)
                textSize = 34f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val bold = Paint(paint).apply {
                textSize = 40f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val contentWidth = (WIDTH - MARGIN * 2).toInt()

            var y = MARGIN
            contentLines.forEach { line ->
                y += LINE
                val textPaint = TextPaint(if (line.bold) bold else paint)
                val layout = StaticLayout.Builder.obtain(line.text, 0, line.text.length, textPaint, contentWidth)
                    .setAlignment(if (isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                canvas.save()
                canvas.translate(MARGIN, y - layout.getLineBaseline(0))
                layout.draw(canvas)
                canvas.restore()
            }

            val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
            check(directory.isDirectory) { "Unable to create receipt cache directory." }
            val safeReference = reference
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
                .ifBlank { System.currentTimeMillis().toString() }
            val file = File(directory, "receipt-$safeReference.png")
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Unable to render receipt PNG."
                }
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    internal data class Line(val text: String, val bold: Boolean = false)
}
