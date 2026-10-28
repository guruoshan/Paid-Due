package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.feature.register.receipt.ReceiptReadModel
import com.dailymoneyregister.feature.register.receipt.ReceiptRenderer
import com.dailymoneyregister.feature.register.receipt.ReceiptSharing
import com.dailymoneyregister.feature.customers.ui.PreparedWhatsAppMessage
import com.dailymoneyregister.feature.customers.ui.WhatsAppAttachment
import com.dailymoneyregister.feature.customers.ui.WhatsAppTransport
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ReceiptLocalizationTest {
    @Test
    fun saleAndEventReceiptImagesRenderInAllSupportedLocales() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val generated = mutableListOf<File>()
        try {
            for (code in listOf("en", "ar", "hi", "bn", "ur")) {
                val locale = Locale.forLanguageTag(code)
                val configuration = Configuration(base.resources.configuration).apply {
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                val context = base.createConfigurationContext(configuration)
                val heading = context.getString(R.string.audit_receipt_heading)
                if (code != "en") {
                    val script = when (code) {
                        "ar", "ur" -> Regex("[\\u0600-\\u06FF]")
                        "hi" -> Regex("[\\u0900-\\u097F]")
                        "bn" -> Regex("[\\u0980-\\u09FF]")
                        else -> error("Unsupported locale: $code")
                    }
                    assertTrue("Receipt heading must use $code script", script.containsMatchIn(heading))
                }

                val receipt = ReceiptReadModel(
                    businessName = "Paid&Due",
                    customerName = "Sample Customer",
                    customerPhone = "+96890000000",
                    reference = "SALE-$code",
                    transactionTimestamp = 1_761_365_400_000L,
                    currencyCode = "OMR",
                    currencyDecimalPlaces = 3,
                    subtotalMinor = 12_345L,
                    taxMinor = 0L,
                    shippingMinor = 0L,
                    packingMinor = 0L,
                    totalMinor = 12_345L,
                    note = "Localization test",
                    items = listOf(ReceiptReadModel.Item("Tea", 1, 12_345L)),
                    tenders = emptyList()
                )
                val saleLines = ReceiptRenderer.buildLines(receipt, context).map { it.text }
                assertTrue("Localized sale receipt heading missing for $code", saleLines.contains(heading))
                assertTrue("Localized receipt footer missing for $code",
                    saleLines.contains(context.getString(R.string.audit_message_footer)))

                val saleImage = ReceiptRenderer.render(context, receipt)
                generated += saleImage
                assertVisibleImage(saleImage, "sale", code)

                val event = ReceiptRenderer.EventReceipt(
                    businessName = "Paid&Due",
                    title = context.getString(R.string.audit_receipt_total, receipt.formattedMoney(receipt.totalMinor)),
                    reference = "EVENT-$code",
                    dateTime = "2026-10-25 12:30",
                    customerName = "Sample Customer",
                    rows = listOf(
                        ReceiptRenderer.EventReceipt.Row(
                            context.getString(R.string.audit_receipt_total, ""),
                            receipt.formattedMoney(receipt.totalMinor)
                        )
                    )
                )
                val eventLines = ReceiptRenderer.buildEventLines(event, context).map { it.text }
                assertTrue("Localized event receipt heading missing for $code", eventLines.contains(heading))
                assertTrue("Localized event footer missing for $code",
                    eventLines.contains(context.getString(R.string.audit_message_footer)))

                val eventImage = ReceiptRenderer.renderEvent(context, event)
                generated += eventImage
                assertVisibleImage(eventImage, "event", code)
            }
        } finally {
            generated.forEach { it.delete() }
        }
    }

    @Test
    fun dayCloseDashboardRendersPngAndUsesBusinessLevelShareIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val summary = ReceiptRenderer.EventReceipt(
            businessName = "Sample Business",
            title = "Day Close Summary",
            reference = "DAY-CLOSE-PREVIEW",
            dateTime = "2026-10-29 12:30",
            customerName = null,
            heading = "DAY CLOSE SUMMARY",
            rows = listOf(
                ReceiptRenderer.EventReceipt.Row("Branch", "MAIN"),
                ReceiptRenderer.EventReceipt.Row("Sales count", "12"),
                ReceiptRenderer.EventReceipt.Row("Gross sales", "OMR 125.000"),
                ReceiptRenderer.EventReceipt.Row("Expected cash", "OMR 82.500"),
                ReceiptRenderer.EventReceipt.Row("Counted cash", "OMR 82.500")
            )
        )
        val file = ReceiptRenderer.renderDaySummary(context, summary)
        try {
            assertTrue("Day-close dashboard must be a PNG", file.name.endsWith(".png"))
            assertVisibleImage(file, "day-close dashboard", "en")
            val lines = ReceiptRenderer.buildDaySummaryLines(summary, context).map { it.text }
            assertTrue(lines.last() == "Powered by Paid&Due")
            assertTrue(ReceiptSharing.DAY_SUMMARY_MESSAGE ==
                "Please check today's cash register dashboard.\n\nPowered by Paid&Due")

            val prepared = PreparedWhatsAppMessage(
                text = ReceiptSharing.DAY_SUMMARY_MESSAGE,
                recipientPhone = "",
                attachment = WhatsAppAttachment(
                    contentUri = Uri.parse("content://com.dailymoneyregister.fileprovider/receipts/day-close.png"),
                    mimeType = "image/png",
                    subject = "Day Close Summary"
                )
            )
            val intent = WhatsAppTransport.buildBusinessLevelSendIntent(context, prepared)
            assertTrue(intent.action == Intent.ACTION_SEND)
            assertTrue(intent.type == "image/png")
            assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT) == ReceiptSharing.DAY_SUMMARY_MESSAGE)
            assertTrue(intent.hasExtra(Intent.EXTRA_STREAM))
            assertTrue("Business-level share must not require a customer JID", !intent.hasExtra("jid"))

            val previewDirectory = context.getExternalFilesDir("day-close-preview")
                ?: error("External app files directory unavailable for dashboard preview artifact")
            check(previewDirectory.mkdirs() || previewDirectory.isDirectory)
            file.copyTo(File(previewDirectory, "day-close-dashboard-preview.png"), overwrite = true)
        } finally {
            file.delete()
        }
    }

    private fun assertVisibleImage(file: File, kind: String, locale: String) {
        assertTrue("$kind receipt image must exist for $locale", file.isFile && file.length() > 500L)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertTrue("$kind receipt image must decode for $locale", bitmap != null)
        val decoded = bitmap!!
        try {
            var inkPixels = 0
            for (y in 0 until decoded.height step 8) {
                for (x in 0 until decoded.width step 8) {
                    val pixel = decoded.getPixel(x, y)
                    if (Color.red(pixel) < 245 || Color.green(pixel) < 245 || Color.blue(pixel) < 245) {
                        inkPixels++
                    }
                }
            }
            assertTrue("$kind receipt image must contain visible content for $locale", inkPixels > 100)
        } finally {
            decoded.recycle()
        }
    }
}
