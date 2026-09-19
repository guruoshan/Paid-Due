package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.ui.theme.RegisterColors
import kotlinx.coroutines.delay

@Composable
fun CashSaleSuccessOverlay(
    transaction: TransactionEntity,
    onStartNewSale: () -> Unit,
    onDisabledAction: (String) -> Unit
) {
    LaunchedEffect(transaction.id) {
        delay(500L)
        onStartNewSale()
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = RegisterColors.CashContainer,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 22.dp,
                vertical = 14.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = RegisterColors.CashPrimary,
                modifier = Modifier.size(30.dp)
            )

            Text(
                text = stringResource(R.string.sale_saved),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = RegisterColors.CashPrimary
            )
        }
    }
}
