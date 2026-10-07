package com.dailymoneyregister.core.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** Resolve UI-owned messages at display time so a language change cannot leave stale text. */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any> = emptyList()) : UiText
    data class Dynamic(val value: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, quantity, *args.toTypedArray())
        is Dynamic -> value
    }
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *args.toTypedArray())
    is UiText.Dynamic -> value
}
