package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

object RegisterSecondaryActionOrder {
    val labels = listOf(R.string.history, R.string.customer, R.string.clear, R.string.share)
}

@Composable
fun RegisterSecondaryActionBar(
    onHistory: () -> Unit,
    onCustomer: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    buttonHeight: androidx.compose.ui.unit.Dp = 44.dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RegisterSecondaryActionOrder.labels.zip(
            listOf(onHistory, onCustomer, onClear, onShare)
        ).map { (label, action) ->
            label to action
        }.forEach { (label, action) ->
            val containerColor = when (label) {
                R.string.history -> Color(0xFF6D28D9)
                R.string.customer -> Color(0xFF0F766E)
                R.string.clear -> Color(0xFFDC2626)
                else -> Color(0xFF1877F2)
            }
            Button(
                onClick = action,
                modifier = Modifier.weight(1f).height(buttonHeight),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = Color.White
                )
            ) {
                Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
