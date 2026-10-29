package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            val currency = context.getString(R.string.profile_currency, "OMR", "3")

            if (code == "en") {
                assertEquals("Sale saved", saleSaved)
                assertEquals("Currency: OMR (3 decimals)", currency)
            } else {
                assertTrue("sale_saved must resolve for $code", saleSaved.isNotBlank())
                assertTrue("profile_currency must preserve the currency for $code", currency.contains("OMR"))
                assertTrue("profile_currency must preserve the precision for $code", currency.contains("3"))
            }
            assertEquals(if (code == "ar" || code == "ur") 1 else 0,
                context.resources.configuration.layoutDirection)
        }
    }
    @Test
    fun everyStringResourceIsPresentAndFreeOfEncodingDamageInAllLocales() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val mojibake = Regex("(?:\\u00c3.|\\u00c2.|\\u00e2\\u20ac|\\u00e0\\u00a4|\\u00e0\\u00a6|\\u00d8[\\u0080-\\u00ff]|\\u00d9[\\u0080-\\u00ff])")
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
