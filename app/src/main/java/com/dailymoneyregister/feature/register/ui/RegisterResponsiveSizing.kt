package com.dailymoneyregister.feature.register.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class RegisterResponsiveSizing(
    val actionHeight: Dp,
    val keypadButtonHeight: Dp,
    val keypadSpacing: Dp,
    val horizontalPadding: Dp,
    val totalTextSize: Int,
    val recentMaxHeight: Dp
) {
    companion object {
        fun forScreen(widthDp: Int, heightDp: Int): RegisterResponsiveSizing = when {
            widthDp >= 600 || heightDp >= 900 -> RegisterResponsiveSizing(56.dp, 72.dp, 12.dp, 24.dp, 36, 360.dp)
            widthDp >= 360 || heightDp >= 700 -> RegisterResponsiveSizing(48.dp, 64.dp, 10.dp, 16.dp, 32, 260.dp)
            else -> RegisterResponsiveSizing(44.dp, 56.dp, 8.dp, 12.dp, 29, 180.dp)
        }
    }
}
