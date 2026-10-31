package com.dailymoneyregister.feature.register.ui.coupon.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun CouponSelectedCustomerDetails(
    modifier: Modifier = Modifier,
    name: String,
    phone: String,
    walletBalanceText: String?
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            name,
            fontWeight = FontWeight.Bold
        )

        Text(
            phone,
            style = MaterialTheme.typography.bodySmall
        )

        walletBalanceText?.let { balance ->
            Text(
                balance,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
