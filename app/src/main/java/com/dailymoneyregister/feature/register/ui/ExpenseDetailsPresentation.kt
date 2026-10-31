package com.dailymoneyregister.feature.register.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R

@Composable
internal fun ExpenseDetailsHeader(
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onBack) {
            Text(stringResource(R.string.audit_back))
        }

        Text(
            text = stringResource(R.string.audit_expense_details),
            modifier = Modifier
                .weight(1f)
                .padding(end = 72.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
    }
}

@Composable
internal fun ExpenseDetailsSummaryCard(
    amount: String,
    category: String,
    paymentMethod: String,
    paymentChannel: String,
    payeeName: String,
    payeePhone: String,
    reference: String,
    note: String,
    dateTime: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = amount,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            ExpenseDetailLine(
                label = stringResource(R.string.audit_category),
                value = category
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_payment_method),
                value = paymentMethod
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_payment_channel),
                value = paymentChannel
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_name),
                value = payeeName
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_phone),
                value = payeePhone
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_reference),
                value = reference
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_note),
                value = note
            )
            ExpenseDetailLine(
                label = stringResource(R.string.audit_date_time),
                value = dateTime
            )
        }
    }
}

@Composable
@Composable
private fun ExpenseDetailLine(
    label: String,
    value: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
