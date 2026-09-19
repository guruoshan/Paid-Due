package com.dailymoneyregister.core.ui

import androidx.annotation.StringRes
import com.dailymoneyregister.R
import com.dailymoneyregister.core.i18n.SupportedLanguage

// Language names stay recognizable in the picker regardless of the current locale.
@get:StringRes
val SupportedLanguage.nativeLabel: Int
    get() = when (code) {
        "ar" -> R.string.language_ar_native
        "hi" -> R.string.language_hi_native
        "bn" -> R.string.language_bn_native
        "ur" -> R.string.language_ur_native
        else -> R.string.language_en_native
    }

@get:StringRes
val SupportedLanguage.englishLabel: Int
    get() = when (code) {
        "ar" -> R.string.language_ar_english
        "hi" -> R.string.language_hi_english
        "bn" -> R.string.language_bn_english
        "ur" -> R.string.language_ur_english
        else -> R.string.language_en_native
    }
