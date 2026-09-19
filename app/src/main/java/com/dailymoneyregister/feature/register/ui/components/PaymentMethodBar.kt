package com.dailymoneyregister.feature.register.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.theme.RegisterColors
import androidx.compose.ui.graphics.Color

@Composable
fun PaymentMethodBar(cashEnabled: Boolean, isExecuting: Boolean, onCoupon: () -> Unit, onCredit: () -> Unit, onOnline: () -> Unit, onCash: () -> Unit, buttonHeight: androidx.compose.ui.unit.Dp = 44.dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = onCoupon, modifier = Modifier.weight(1f).height(buttonHeight), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE8B0), contentColor = Color(0xFF8A4B08))) { Text(stringResource(R.string.coupon), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        Button(onClick = onCredit, modifier = Modifier.weight(1f).height(buttonHeight), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEDE3FF), contentColor = Color(0xFF5B21B6))) { Text(stringResource(R.string.credit), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        Button(onClick = onOnline, modifier = Modifier.weight(1f).height(buttonHeight), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDCEEFF), contentColor = Color(0xFF075985))) { Text(stringResource(R.string.online), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        Button(onClick = onCash, enabled = cashEnabled, modifier = Modifier.weight(1f).height(buttonHeight), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.buttonColors(containerColor = RegisterColors.CashPrimary, contentColor = MaterialTheme.colorScheme.onPrimary)) { if (isExecuting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text(stringResource(R.string.cash), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}
