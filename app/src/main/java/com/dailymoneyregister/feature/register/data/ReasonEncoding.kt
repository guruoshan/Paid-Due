package com.dailymoneyregister.feature.register.data

import android.util.Base64

internal fun encodeReason(reason: String?): String =
    reason?.toByteArray(Charsets.UTF_8)?.let {
        Base64.encodeToString(it, Base64.NO_WRAP)
    }.orEmpty()
