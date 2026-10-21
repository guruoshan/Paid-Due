package com.dailymoneyregister.feature.invoice.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.invoice.data.IssuedInvoice
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders an issued invoice to an A4 PDF from its FROZEN snapshot only.
 * Nothing is read from the live database, so an old invoice always prints
 * exactly as it was issued. Blocking work: call from Dispatchers.IO.
 */
object InvoicePdfRenderer {

    fun render(context: Context, invoice: IssuedInvoice): File {
        val s = invoice.snapshot
        val displayReference = invoice.displayNumber

        val directory = File(context.cacheDir, "invoices").apply { mkdirs() }
        val safeName = displayReference.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val file = File(directory, "$safeName.pdf")
        val pdf = PdfDocument()

        val accent = Color.rgb(103, 80, 164)
        val accentSoft = Color.rgb(246, 242, 252)
        val ink = Color.rgb(38, 38, 43)
        val muted = Color.rgb(99, 99, 108)
        val rule = Color.rgb(224, 224, 230)
        val white = Color.WHITE

        fun paint(c: Int, size: Float, bold: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c
                textSize = size
                if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

        val businessPaint = paint(ink, 16f, bold = true)
        val invoiceTitlePaint = paint(accent, 28f, bold = true)
        val headingPaint = paint(ink, 11f, bold = true)
        val bodyPaint = paint(ink, 9.5f)
        val smallPaint = paint(muted, 8f)
        val tinyPaint = paint(muted, 7.2f)
        val whiteHeadingPaint = paint(white, 8.5f, bold = true)
        val totalLabelPaint = paint(ink, 9.5f)
        val totalValuePaint = paint(ink, 9.5f, bold = true)
        val grandLabelPaint = paint(white, 11f, bold = true)
        val footerPaint = paint(muted, 7.6f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rule
            strokeWidth = 1f
        }

        val pageWidth = 595f
        val pageHeight = 842f
        val left = 42f
        val right = 553f
        val contentBottom = 782f
        val footerY = 820f

        val money = moneyFormatter(s.currencyCode, s.currencyDecimalPlaces)

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun drawFooter() {
            val active = page ?: return
            val canvas = active.canvas
            canvas.drawLine(left, 804f, right, 804f, linePaint)
            val text = context.getString(R.string.inv_pdf_footer, context.getString(R.string.app_name))
            val x = (pageWidth - footerPaint.measureText(text)) / 2f
            canvas.drawText(text, x, footerY, footerPaint)
        }

        fun finishPage() {
            val active = page ?: return
            drawFooter()
            pdf.finishPage(active)
            page = null
        }

        fun drawContinuationHeader() {
            val canvas = (page ?: return).canvas
            canvas.drawText(context.getString(R.string.invoice), left, 48f, headingPaint)
            val x = right - smallPaint.measureText(displayReference)
            canvas.drawText(displayReference, x, 48f, smallPaint)
            canvas.drawLine(left, 58f, right, 58f, linePaint)
        }

        fun startPage(continuation: Boolean = false) {
            finishPage()
            pageNumber++
            page = pdf.startPage(
                PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create()
            )
            y = if (continuation) 78f else 42f
            if (continuation) drawContinuationHeader()
        }

        fun ensureHeight(height: Float) {
            if (page == null) startPage(false)
            if (y + height > contentBottom) startPage(true)
        }

        fun wrapText(text: String, p: Paint, maxWidth: Float): List<String> {
            if (text.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            text.split('\n').forEach { paragraph ->
                if (paragraph.isBlank()) {
                    result += ""
                } else {
                    var line = ""
                    paragraph.trim().split(Regex("\\s+")).forEach { word ->
                        val candidate = if (line.isBlank()) word else "$line $word"
                        if (p.measureText(candidate) <= maxWidth) {
                            line = candidate
                        } else {
                            if (line.isNotBlank()) result += line
                            if (p.measureText(word) <= maxWidth) {
                                line = word
                            } else {
                                var chunk = ""
                                word.forEach { ch ->
                                    val next = chunk + ch
                                    if (p.measureText(next) > maxWidth && chunk.isNotEmpty()) {
                                        result += chunk
                                        chunk = ch.toString()
                                    } else {
                                        chunk = next
                                    }
                                }
                                line = chunk
                            }
                        }
                    }
                    if (line.isNotBlank()) result += line
                }
            }
            return result
        }

        fun drawWrapped(
            text: String,
            x: Float,
            maxWidth: Float,
            p: Paint = bodyPaint,
            lineHeight: Float = 12f
        ) {
            val lines = wrapText(text, p, maxWidth)
            if (lines.isEmpty()) return
            ensureHeight(lines.size * lineHeight + 2f)
            val canvas = checkNotNull(page).canvas
            lines.forEach { line ->
                canvas.drawText(line, x, y, p)
                y += lineHeight
            }
        }

        fun drawRight(text: String, rightX: Float, baseline: Float, p: Paint) {
            checkNotNull(page).canvas.drawText(text, rightX - p.measureText(text), baseline, p)
        }

        fun drawItemsHeader() {
            ensureHeight(28f)
            val canvas = checkNotNull(page).canvas
            fillPaint.color = accent
            canvas.drawRoundRect(RectF(left, y, right, y + 23f), 4f, 4f, fillPaint)
            canvas.drawText(context.getString(R.string.inv_pdf_items), left + 8f, y + 15f, whiteHeadingPaint)
            drawRight(context.getString(R.string.inv_pdf_qty), 390f, y + 15f, whiteHeadingPaint)
            drawRight(context.getString(R.string.inv_pdf_unit_price), 472f, y + 15f, whiteHeadingPaint)
            drawRight(context.getString(R.string.inv_pdf_line_total), right - 8f, y + 15f, whiteHeadingPaint)
            y += 30f
        }

        try {
            startPage(false)
            val canvas = checkNotNull(page).canvas

            // ---- Business block (left) ----
            var businessY = 50f
            s.business.name?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it.take(44), left, businessY, businessPaint)
                businessY += 16f
            }
            s.business.registrationNumber?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(
                    context.getString(R.string.inv_pdf_registration, it).take(64),
                    left, businessY, smallPaint
                )
                businessY += 12f
            }
            s.business.taxId?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(
                    context.getString(R.string.inv_pdf_tax_id, it).take(64),
                    left, businessY, smallPaint
                )
                businessY += 12f
            }
            s.business.address?.takeIf { it.isNotBlank() }?.let { address ->
                wrapText(address, smallPaint, 245f).take(2).forEach { line ->
                    canvas.drawText(line, left, businessY, smallPaint)
                    businessY += 11f
                }
            }
            s.business.phone?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it.take(40), left, businessY, smallPaint)
                businessY += 11f
            }

            // ---- Title + date (right) ----
            val dateLine = formatDateTime(s.createdAt)
            drawRight(context.getString(R.string.invoice).uppercase(), right, 60f, invoiceTitlePaint)
            drawRight(dateLine, right, 79f, smallPaint)

            fillPaint.color = accent
            canvas.drawRoundRect(RectF(left, 116f, right, 120f), 2f, 2f, fillPaint)

            // ---- Reference box ----
            y = 144f
            fillPaint.color = accentSoft
            canvas.drawRoundRect(RectF(left, y - 12f, right, y + 42f), 8f, 8f, fillPaint)
            canvas.drawText(
                context.getString(R.string.inv_pdf_reference, displayReference).take(82),
                left + 12f, y + 5f, smallPaint
            )
            canvas.drawText(
                context.getString(R.string.inv_pdf_date_time, dateLine).take(82),
                left + 12f, y + 22f, smallPaint
            )
            y += 66f

            // ---- Bill to ----
            canvas.drawText(context.getString(R.string.inv_pdf_bill_to).uppercase(), left, y, headingPaint)
            y += 18f
            val displayName = listOfNotNull(
                titleText(context, s.customer.title),
                s.customer.name.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            if (displayName.isNotBlank()) drawWrapped(displayName, left, 300f, headingPaint, 14f)
            drawWrapped(s.customer.phone, left, 300f, bodyPaint, 13f)
            s.customer.address?.takeIf { it.isNotBlank() }?.let {
                drawWrapped(it, left, 300f, bodyPaint, 13f)
            }
            y += 8f

            // ---- Items ----
            if (s.items.isNotEmpty()) {
                drawItemsHeader()
                s.items.forEachIndexed { index, item ->
                    val nameLines = wrapText("${index + 1}. ${item.name}", headingPaint, 255f)
                    val descLines = item.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { wrapText(it, smallPaint, 255f).take(2) }
                        .orEmpty()
                    val hasCharges = item.itemShippingMinor > 0L || item.itemPackingMinor > 0L
                    val rowHeight = maxOf(
                        34f,
                        12f + nameLines.size * 13f + descLines.size * 11f + if (hasCharges) 12f else 0f
                    )
                    if (y + rowHeight > contentBottom) {
                        startPage(true)
                        drawItemsHeader()
                    }

                    val rowTop = y
                    val c = checkNotNull(page).canvas
                    var textY = y + 12f
                    nameLines.forEach { line ->
                        c.drawText(line, left + 6f, textY, headingPaint)
                        textY += 13f
                    }
                    descLines.forEach { line ->
                        c.drawText(line, left + 6f, textY, smallPaint)
                        textY += 11f
                    }
                    if (hasCharges) {
                        val charges = context.getString(
                            R.string.inv_pdf_item_charges,
                            money(item.itemShippingMinor),
                            money(item.itemPackingMinor)
                        )
                        c.drawText(charges.take(62), left + 6f, textY, tinyPaint)
                    }

                    val baseline = rowTop + 15f
                    drawRight(item.quantity.toString(), 390f, baseline, bodyPaint)
                    drawRight(money(item.unitPriceMinor), 472f, baseline, bodyPaint)
                    drawRight(money(item.lineTotalMinor), right - 6f, baseline, totalValuePaint)

                    y = rowTop + rowHeight
                    c.drawLine(left, y, right, y, linePaint)
                    y += 7f
                }
            }

            // ---- Totals ----
            val totalsLeft = 330f
            val totalsRight = right
            val totals = mutableListOf<Pair<String, String>>()
            totals += context.getString(R.string.inv_pdf_subtotal) to money(s.subtotalMinor)
            s.taxableBaseMinor?.let { taxableBase ->
                totals += context.getString(R.string.inv_pdf_taxable_value) to money(taxableBase)
            }
            if (s.taxRateBps > 0 || s.taxAmountMinor > 0L) {
                totals += context.getString(R.string.inv_pdf_tax, formatRateBps(s.taxRateBps)) to
                    money(s.taxAmountMinor)
            }
            if (s.itemShippingTotalMinor > 0L) {
                totals += context.getString(R.string.inv_pdf_item_shipping) to money(s.itemShippingTotalMinor)
            }
            if (s.itemPackingTotalMinor > 0L) {
                totals += context.getString(R.string.inv_pdf_item_packing) to money(s.itemPackingTotalMinor)
            }
            if (s.txShippingMinor > 0L) {
                totals += context.getString(R.string.inv_pdf_shipping) to money(s.txShippingMinor)
            }
            if (s.txPackingMinor > 0L) {
                totals += context.getString(R.string.inv_pdf_packing) to money(s.txPackingMinor)
            }
            ensureHeight(totals.size * 18f + 42f)
            totals.forEach { (label, value) ->
                checkNotNull(page).canvas.drawText(label, totalsLeft, y, totalLabelPaint)
                drawRight(value, totalsRight, y, totalValuePaint)
                y += 18f
            }

            fillPaint.color = accent
            checkNotNull(page).canvas.drawRoundRect(
                RectF(totalsLeft - 8f, y - 13f, totalsRight, y + 11f), 5f, 5f, fillPaint
            )
            checkNotNull(page).canvas.drawText(
                context.getString(R.string.inv_pdf_grand_total), totalsLeft, y + 3f, grandLabelPaint
            )
            drawRight(money(s.grandTotalMinor), totalsRight - 8f, y + 3f, grandLabelPaint)
            y += 34f

            // ---- Note ----
            s.note?.takeIf { it.isNotBlank() }?.let { note ->
                ensureHeight(58f)
                checkNotNull(page).canvas.drawText(
                    context.getString(R.string.inv_pdf_note), left, y, headingPaint
                )
                y += 15f
                drawWrapped(note, left, right - left, bodyPaint, 12f)
                y += 8f
            }

            // ---- Non-financial notice ----
            ensureHeight(44f)
            fillPaint.color = Color.rgb(250, 250, 251)
            checkNotNull(page).canvas.drawRoundRect(
                RectF(left, y, right, y + 34f), 6f, 6f, fillPaint
            )
            var noticeY = y + 13f
            wrapText(context.getString(R.string.inv_pdf_notice), tinyPaint, right - left - 20f)
                .take(2)
                .forEach { line ->
                    checkNotNull(page).canvas.drawText(line, left + 10f, noticeY, tinyPaint)
                    noticeY += 10f
                }
            y += 42f

            finishPage()
            FileOutputStream(file).use { pdf.writeTo(it) }
            return file
        } finally {
            if (page != null) runCatching { finishPage() }
            pdf.close()
        }
    }

    // ------------------------------------------------------------------

    private fun moneyFormatter(currencyCode: String, decimals: Int): (Long) -> String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val pattern = buildString {
            append("#,##0")
            if (decimals > 0) {
                append('.')
                repeat(decimals) { append('0') }
            }
        }
        return { minor ->
            val text = DecimalFormat(pattern, symbols).format(
                java.math.BigDecimal.valueOf(minor, decimals)
            )
            "$currencyCode $text"
        }
    }

    private fun formatDateTime(epochMillis: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))

    /** 500 -> "5", 725 -> "7.25", 750 -> "7.5" */
    private fun formatRateBps(bps: Int): String {
        val whole = bps / 100
        val fraction = bps % 100
        return if (fraction == 0) whole.toString()
        else "$whole.${fraction.toString().padStart(2, '0').trimEnd('0')}"
    }

    private fun titleText(context: Context, code: String?): String? = when (code) {
        "MR" -> context.getString(R.string.inv_title_mr)
        "MRS" -> context.getString(R.string.inv_title_mrs)
        "MS" -> context.getString(R.string.inv_title_ms)
        "DR" -> context.getString(R.string.inv_title_dr)
        else -> null
    }
}
