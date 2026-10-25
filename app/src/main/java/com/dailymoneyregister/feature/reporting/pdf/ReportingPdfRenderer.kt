package com.dailymoneyregister.feature.reporting.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.dailymoneyregister.R
import java.io.File
import java.io.FileOutputStream

enum class ReportValueTone { POSITIVE, NEGATIVE, OPERATIONAL }

data class ReportPdfMetric(
    val label: String,
    val value: String,
    val tone: ReportValueTone = ReportValueTone.OPERATIONAL
)

data class ReportPdfTransaction(
    val date: String,
    val type: String,
    val reference: String,
    val customer: String,
    val payment: String,
    val amount: String,
    val tone: ReportValueTone = ReportValueTone.OPERATIONAL
)

data class ReportingPdfSnapshot(
    val businessName: String,
    val branchName: String,
    val reportTitle: String,
    val reportDate: String,
    val periodLabel: String,
    val metrics: List<ReportPdfMetric>,
    val transactions: List<ReportPdfTransaction>
)

/** Renders a reporting snapshot only; this class never reads or changes financial records. */
object ReportingPdfRenderer {
    fun render(context: Context, snapshot: ReportingPdfSnapshot): File {
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        val safeName = snapshot.reportTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50)
        val file = File(directory, "${safeName}_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val pageWidth = 842
        val pageHeight = 595
        val left = 36f
        val right = 806f
        val ink = Color.rgb(35, 42, 52)
        val muted = Color.rgb(105, 113, 124)
        val rule = Color.rgb(222, 226, 232)
        val blue = Color.rgb(21, 101, 192)
        val red = Color.rgb(198, 40, 40)
        val gray = Color.rgb(97, 105, 115)
        val paleBlue = Color.rgb(239, 246, 255)
        val white = Color.WHITE

        fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            // Android's sans-serif family uses platform fallback glyphs for Arabic,
            // Urdu, Devanagari, and Bengali; StaticLayout then applies script shaping.
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val titlePaint = paint(ink, 20f, true)
        val businessPaint = paint(ink, 14f, true)
        val bodyPaint = paint(ink, 9f)
        val smallPaint = paint(muted, 8f)
        val labelPaint = paint(muted, 8f, true)
        val metricValuePaint = paint(ink, 12f, true)
        val tableHeaderPaint = paint(white, 8f, true)
        val linePaint = paint(rule, 1f).apply { strokeWidth = 1f }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

        // StaticLayout shapes Arabic/Urdu joining and performs bidirectional layout for PDF text.
        fun drawLocalizedText(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint, width: Float) {
            val safeWidth = width.toInt().coerceAtLeast(1)
            val textPaint = TextPaint(paint)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, safeWidth)
                .setAlignment(if (isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(x, baseline - layout.getLineBaseline(0))
            layout.draw(canvas)
            canvas.restore()
        }

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun toneColor(tone: ReportValueTone): Int = when (tone) {
            ReportValueTone.POSITIVE -> blue
            ReportValueTone.NEGATIVE -> red
            ReportValueTone.OPERATIONAL -> gray
        }

        fun finishPage() {
            val active = page ?: return
            active.canvas.drawLine(left, 568f, right, 568f, linePaint)
            val footer = context.getString(R.string.pdf_generated_by, pageNumber)
            drawLocalizedText(active.canvas, footer, left, 583f, smallPaint, right - left)
            pdf.finishPage(active)
            page = null
        }

        fun startPage(continuation: Boolean) {
            finishPage()
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val canvas = checkNotNull(page).canvas
            y = 28f
            if (continuation) {
                drawLocalizedText(canvas, snapshot.reportTitle + " — " + context.getString(R.string.pdf_continued), left, y + 8f, titlePaint, right - left)
                y += 28f
                canvas.drawLine(left, y, right, y, linePaint)
                y += 18f
            } else {
                drawLocalizedText(canvas, snapshot.businessName.ifBlank { context.getString(R.string.pdf_registered_business) }, left, y + 12f, businessPaint, right - left)
                y += 34f
                drawLocalizedText(canvas, snapshot.reportTitle, left, y + 4f, titlePaint, right - left)
                y += 22f
                drawLocalizedText(canvas, context.getString(R.string.pdf_branch, snapshot.branchName.ifBlank { "—" }), left, y, bodyPaint, 460f)
                drawLocalizedText(canvas, context.getString(R.string.pdf_report_date, snapshot.reportDate), 510f, y, bodyPaint, right - 510f)
                y += 15f
                drawLocalizedText(canvas, context.getString(R.string.pdf_period, snapshot.periodLabel), left, y, smallPaint, right - left)
                y += 16f
                canvas.drawLine(left, y, right, y, linePaint)
                y += 14f
            }
        }

        fun clipped(text: String, p: Paint, width: Float): String {
            val value = text.replace('\n', ' ').ifBlank { "—" }
            if (p.measureText(value) <= width) return value
            var result = value
            while (result.isNotEmpty() && p.measureText("$result…") > width) {
                result = result.dropLast(1)
            }
            return if (result.isEmpty()) "…" else "$result…"
        }

        fun drawMetrics() {
            val canvas = checkNotNull(page).canvas
            val gap = 10f
            val cardWidth = (right - left - gap * 3) / 4f
            snapshot.metrics.forEachIndexed { index, metric ->
                val row = index / 4
                val column = index % 4
                val top = y + row * 49f
                val x = if (isRtl) right - (column + 1) * cardWidth - column * gap else left + column * (cardWidth + gap)
                fillPaint.color = paleBlue
                canvas.drawRoundRect(RectF(x, top, x + cardWidth, top + 40f), 5f, 5f, fillPaint)
                drawLocalizedText(canvas, metric.label, x + 8f, top + 13f, labelPaint, cardWidth - 16f)
                val valuePaint = paint(toneColor(metric.tone), 11f, true)
                drawLocalizedText(canvas, metric.value, x + 8f, top + 30f, valuePaint, cardWidth - 16f)
            }
            y += ((snapshot.metrics.size + 3) / 4) * 49f + 5f
        }

        val columnWidths = floatArrayOf(98f, 92f, 130f, 145f, 130f, 139f)
        val headers = listOf(
            context.getString(R.string.pdf_header_date_time),
            context.getString(R.string.pdf_header_type),
            context.getString(R.string.pdf_header_reference),
            context.getString(R.string.pdf_header_customer),
            context.getString(R.string.pdf_header_payment),
            context.getString(R.string.pdf_header_amount)
        )
        val starts = FloatArray(columnWidths.size)
        var runningX = if (isRtl) right else left
        columnWidths.indices.forEach { i ->
            if (isRtl) {
                runningX -= columnWidths[i]
                starts[i] = runningX
            } else {
                starts[i] = runningX
                runningX += columnWidths[i]
            }
        }

        fun drawTableHeader() {
            val canvas = checkNotNull(page).canvas
            fillPaint.color = ink
            canvas.drawRoundRect(RectF(left, y, right, y + 22f), 4f, 4f, fillPaint)
            headers.forEachIndexed { i, label ->
                drawLocalizedText(canvas, label, starts[i] + 6f, y + 14f, tableHeaderPaint, columnWidths[i] - 12f)
            }
            y += 22f
        }

        try {
            startPage(false)
            drawMetrics()
            drawTableHeader()
            if (snapshot.transactions.isEmpty()) {
                drawLocalizedText(checkNotNull(page).canvas, context.getString(R.string.pdf_empty_transactions), left + 6f, y + 16f, bodyPaint, right - left - 12f)
                y += 26f
            } else {
                snapshot.transactions.forEachIndexed { index, row ->
                    if (y + 23f > 556f) {
                        startPage(true)
                        drawTableHeader()
                    }
                    val canvas = checkNotNull(page).canvas
                    if (index % 2 == 0) {
                        fillPaint.color = Color.rgb(248, 249, 251)
                        canvas.drawRect(left, y, right, y + 22f, fillPaint)
                    }
                    val values = listOf(row.date, row.type, row.reference, row.customer, row.payment, row.amount)
                    values.forEachIndexed { i, value ->
                        val p = if (i == 5) paint(toneColor(row.tone), 8.5f, true) else bodyPaint
                        drawLocalizedText(canvas, value, starts[i] + 6f, y + 14f, p, columnWidths[i] - 12f)
                    }
                    canvas.drawLine(left, y + 22f, right, y + 22f, linePaint)
                    y += 22f
                }
            }
            finishPage()
            FileOutputStream(file).use { pdf.writeTo(it) }
            return file
        } finally {
            pdf.close()
        }
    }
}
