package com.dailymoneyregister.core.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean = false
)

object LanguageRegistry {
    val ENGLISH = SupportedLanguage("en", "English", "English", isRtl = false)
    val ARABIC = SupportedLanguage("ar", "العربية", "Arabic", isRtl = true)
    val HINDI = SupportedLanguage("hi", "हिन्दी", "Hindi", isRtl = false)
    val BANGLA = SupportedLanguage("bn", "বাংলা", "Bangla", isRtl = false)
    val URDU = SupportedLanguage("ur", "اردو", "Urdu", isRtl = true)

    val allLanguages = listOf(ENGLISH, ARABIC, HINDI, BANGLA, URDU)

    fun findByCode(code: String): SupportedLanguage {
        return allLanguages.find { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}

class LanguageManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("dmr_language_prefs", Context.MODE_PRIVATE)

    private val _currentLanguage = MutableStateFlow(loadSavedLanguage())
    val currentLanguage: StateFlow<SupportedLanguage> = _currentLanguage.asStateFlow()

    private fun loadSavedLanguage(): SupportedLanguage {
        val code = prefs.getString("selected_language", "en") ?: "en"
        return LanguageRegistry.findByCode(code)
    }

    /**
     * Sets the application language and persists it locally.
     * Also applies the locale to AppCompatDelegate.
     */
    fun setLanguage(languageCode: String) {
        val language = LanguageRegistry.findByCode(languageCode)
        prefs.edit().putString("selected_language", language.code).apply()
        _currentLanguage.value = language

        val appLocale = LocaleListCompat.forLanguageTags(language.code)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    /**
     * Checks if the current language layout direction is Right-To-Left (RTL).
     */
    val isRtl: Boolean get() = _currentLanguage.value.isRtl
}
