package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    @Test
    fun untranslatedMessagesFallBackToEnglishInEveryPreparedLocale() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (code in listOf("en", "ar", "hi", "bn", "ur")) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(code))
            })
            assertEquals("Sale saved", context.getString(R.string.sale_saved))
            assertEquals("Cashier 'Sam' created successfully",
                UiText.Resource(R.string.cashier_created, listOf("Sam")).resolve(context))
            assertEquals("Currency: OMR (3 decimals)",
                context.getString(R.string.profile_currency, "OMR", "3"))
            assertEquals("@sam • Language: English",
                context.getString(R.string.cashier_language_summary, "sam", "English"))
            assertEquals("Repository detail", UiText.Dynamic("Repository detail").resolve(context))
            assertEquals(if (code == "ar" || code == "ur") 1 else 0,
                context.resources.configuration.layoutDirection)
        }
    }
}
