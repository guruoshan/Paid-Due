package com.dailymoneyregister

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.register.ui.components.PaymentMethodBar
import com.dailymoneyregister.feature.register.ui.components.TaxDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UiLocalizationTest {
    @get:Rule val compose = createComposeRule()

    private fun contextFor(code: String): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(code))
        })
    }

    @Test
    fun taxDialogKeepsEnglishLabelsAndInputCallback() {
        var saved: String? = null
        var dismissed = false
        compose.setContent {
            CompositionLocalProvider(LocalContext provides contextFor("en")) {
                MaterialTheme {
                    TaxDialog(DraftState(), com.dailymoneyregister.core.model.currency.CurrencyRegistry.OMR, { dismissed = true }, { saved = it })
                }
            }
        }
        compose.onNodeWithText("e.g. 5, 18, 7.5").assertIsDisplayed()
        compose.onNodeWithText("Tax Rate (%)").performTextInput("7.5")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("7.5", saved)
            assertEquals(true, dismissed)
        }
    }

    @Test fun paymentControlsWorkInLtr() = checkPayments(LayoutDirection.Ltr)
    @Test fun paymentControlsWorkInRtl() = checkPayments(LayoutDirection.Rtl)

    private fun checkPayments(direction: LayoutDirection) {
        val clicks = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides contextFor("en"),
                LocalLayoutDirection provides direction
            ) {
                MaterialTheme {
                    PaymentMethodBar(
                        cashEnabled = true, isExecuting = false,
                        onCoupon = { clicks += "Coupon" }, onCredit = { clicks += "Credit" },
                        onOnline = { clicks += "Online" }, onCash = { clicks += "Cash" }
                    )
                }
            }
        }
        for (label in listOf("Coupon", "Credit", "Online", "Cash")) {
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("Coupon", "Credit", "Online", "Cash"), clicks) }
    }
}
