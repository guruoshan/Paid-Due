package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    val primaryLabels = listOf(R.string.history, R.string.customer)
    val secondaryLabels = listOf(R.string.clear, R.string.share)

    // Preserve the existing public/test contract.
    val labels = primaryLabels + secondaryLabels
}

@Composable
fun RegisterSecondaryActionBar(
    onHistory: () -> Unit,
    onCustomer: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    buttonHeight: androidx.compose.ui.unit.Dp = 44.dp
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RegisterSecondaryActionOrder.labels.zip(
            listOf(onHistory, onCustomer, onClear, onShare)
        ).chunked(4).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowActions.forEach { (label, action) ->
                    val displayLabel = when (label) {
                        R.string.clear -> "Day Closing"
                        R.string.share -> "Expenses"
                        else -> stringResource(label)
                    }

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
                        Text(
                            text = displayLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(4 - rowActions.size) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
