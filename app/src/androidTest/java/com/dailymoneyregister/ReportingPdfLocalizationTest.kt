package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
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
                val snapshot = ReportingPdfSnapshot(
                    businessName = "Paid&Due",
                    branchName = "Main",
                    reportTitle = context.getString(R.string.reporting_title),
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
            }
        } finally {
            generated.forEach { it.delete() }
        }
    }
}
