package com.dailymoneyregister.feature.register.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.core.ui.theme.RegisterColors
import com.dailymoneyregister.feature.register.domain.KeypadKey

@Composable
fun RegisterKeypad(
    onKeyPress: (KeypadKey) -> Unit,
    modifier: Modifier = Modifier,
    buttonHeight: androidx.compose.ui.unit.Dp = 56.dp,
    rowSpacing: androidx.compose.ui.unit.Dp = 8.dp
) {
    Column(
        modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        // 4x4 Grid
        val rows = listOf(
            listOf(KeypadKey.Digit('1'), KeypadKey.Digit('2'), KeypadKey.Digit('3'), KeypadKey.Divide),
            listOf(KeypadKey.Digit('4'), KeypadKey.Digit('5'), KeypadKey.Digit('6'), KeypadKey.Multiply),
            listOf(KeypadKey.Digit('7'), KeypadKey.Digit('8'), KeypadKey.Digit('9'), KeypadKey.Minus),
            listOf(KeypadKey.Digit('0'), KeypadKey.Decimal, KeypadKey.Equals, KeypadKey.Plus)
        )

        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (key in row) {
                    KeypadButton(
                        key = key,
                        onClick = { onKeyPress(key) },
                        modifier = Modifier.weight(1f).height(buttonHeight)
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    key: KeypadKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, containerColor, contentColor) = when (key) {
        is KeypadKey.Digit -> Triple(key.char.toString(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        KeypadKey.Decimal -> Triple(".", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        KeypadKey.Plus -> Triple("+", RegisterColors.OnlineContainer, RegisterColors.OnOnlineContainer)
        KeypadKey.Minus -> Triple("\u2212", RegisterColors.OnlineContainer, RegisterColors.OnOnlineContainer)
        KeypadKey.Multiply -> Triple("\u00D7", RegisterColors.OnlineContainer, RegisterColors.OnOnlineContainer)
        KeypadKey.Divide -> Triple("\u00F7", RegisterColors.CouponContainer, RegisterColors.OnCouponContainer)
        KeypadKey.Percent -> Triple("%", RegisterColors.CouponContainer, RegisterColors.OnCouponContainer)
        KeypadKey.Equals -> Triple("=", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else -> Triple("", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
    }

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}


