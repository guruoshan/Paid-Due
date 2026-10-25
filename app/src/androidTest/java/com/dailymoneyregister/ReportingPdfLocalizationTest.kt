package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.feature.reporting.pdf.ReportPdfMetric
import com.dailymoneyregister.feature.reporting.pdf.ReportPdfTransaction
import com.dailymoneyregister.feature.reporting.pdf.ReportingPdfRenderer
import com.dailymoneyregister.feature.reporting.pdf.ReportingPdfSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ReportingPdfLocalizationTest {
    @Test
    fun reportingPdfRendersAllSupportedLocalesWithoutCrashing() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val generated = mutableListOf<File>()
        try {
            for (code in listOf("en", "ar", "hi", "bn", "ur")) {
                val locale = Locale.forLanguageTag(code)
                val config = Configuration(base.resources.configuration).apply {
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                val context = base.createConfigurationContext(config)
                val localizedTitle = context.getString(R.string.reporting_title)
                if (code != "en") {
                    val expectedScript = when (code) {
                        "ar", "ur" -> Regex("[\\u0600-\\u06FF]")
                        "hi" -> Regex("[\\u0900-\\u097F]")
                        "bn" -> Regex("[\\u0980-\\u09FF]")
                        else -> error("Unsupported test locale: $code")
                    }
                    assertTrue("Report title must use the $code script, not English fallback", expectedScript.containsMatchIn(localizedTitle))
                }
                val snapshot = ReportingPdfSnapshot(
                    businessName = "Paid&Due",
                    branchName = "Main",
                    reportTitle = localizedTitle,
                    reportDate = "2026-10-25",
                    periodLabel = context.getString(R.string.audit_date_format_hint),
                    metrics = listOf(
                        ReportPdfMetric(context.getString(R.string.audit_sale), "OMR 12.345"),
                        ReportPdfMetric(context.getString(R.string.audit_credit_balance), "OMR 5.000")
                    ),
                    transactions = listOf(
                        ReportPdfTransaction(
                            date = "2026-10-25 12:30",
                            type = context.getString(R.string.audit_sale),
                            reference = "TEST-REF",
                            customer = "Customer Sample",
                            payment = context.getString(R.string.audit_payment_method),
                            amount = "OMR 12.345"
                        )
                    )
                )
                val pdf = ReportingPdfRenderer.render(context, snapshot)
                generated += pdf
                assertTrue("PDF must be non-empty for locale $code", pdf.isFile && pdf.length() > 500L)
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        assertTrue("PDF must contain a page for locale $code", renderer.pageCount > 0)
                        renderer.openPage(0).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                var inkPixels = 0
                                for (y in 0 until bitmap.height step 4) {
                                    for (x in 0 until bitmap.width step 4) {
                                        val pixel = bitmap.getPixel(x, y)
                                        if (Color.red(pixel) < 245 || Color.green(pixel) < 245 || Color.blue(pixel) < 245) {
                                            inkPixels++
                                        }
                                    }
                                }
                                assertTrue("PDF page must render visible content for locale $code", inkPixels > 100)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            generated.forEach { it.delete() }
        }
    }
}
