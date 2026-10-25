package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    @Test
    fun localizedResourcesResolveInEveryPreparedLocale() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (code in listOf("en", "ar", "hi", "bn", "ur")) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(code))
            })
            val saleSaved = context.getString(R.string.sale_saved)
            val cashierCreated =
                UiText.Resource(R.string.cashier_created, listOf("Sam")).resolve(context)
            val currency = context.getString(R.string.profile_currency, "OMR", "3")
            val languageSummary =
                context.getString(R.string.cashier_language_summary, "sam", "English")

            if (code == "en") {
                assertEquals("Sale saved", saleSaved)
                assertEquals("Cashier 'Sam' created successfully", cashierCreated)
                assertEquals("Currency: OMR (3 decimals)", currency)
            } else {
                assertTrue("sale_saved must resolve for $code", saleSaved.isNotBlank())
                assertTrue("cashier_created must resolve for $code", cashierCreated.isNotBlank())
                assertTrue("cashier_created must preserve the name for $code", cashierCreated.contains("Sam"))
                assertTrue("profile_currency must preserve the currency for $code", currency.contains("OMR"))
                assertTrue("profile_currency must preserve the precision for $code", currency.contains("3"))
            }

            assertTrue("cashier_language_summary must resolve for $code", languageSummary.isNotBlank())
            assertTrue("cashier_language_summary must preserve the username for $code", languageSummary.contains("sam"))
            assertTrue("cashier_language_summary must preserve the language for $code", languageSummary.contains("English"))
            assertEquals("Repository detail", UiText.Dynamic("Repository detail").resolve(context))
            assertEquals(if (code == "ar" || code == "ur") 1 else 0,
                context.resources.configuration.layoutDirection)
        }
    }
    @Test
    fun everyStringResourceIsPresentAndFreeOfEncodingDamageInAllLocales() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val mojibake = Regex("(?:Ã.|Â.|â€|â€¦|â€¢|à¤|à¦|Ø[\\u0080-\\u00ff]|Ù[\\u0080-\\u00ff])")
        val stringIds = R.string::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .mapNotNull { runCatching { it.getInt(null) }.getOrNull() }
            .distinct()
        for (code in listOf("en", "ar", "hi", "bn", "ur")) {
            val locale = Locale.forLanguageTag(code)
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            })
            for (id in stringIds) {
                val value = runCatching { context.resources.getText(id).toString() }.getOrNull() ?: continue
                assertTrue("blank string resource id=$id locale=$code", value.isNotBlank())
                assertTrue("garbled encoding in string resource id=$id locale=$code: $value", !mojibake.containsMatchIn(value))
            }
            assertEquals(if (code == "ar" || code == "ur") 1 else 0,
                context.resources.configuration.layoutDirection)
        }
    }

}
