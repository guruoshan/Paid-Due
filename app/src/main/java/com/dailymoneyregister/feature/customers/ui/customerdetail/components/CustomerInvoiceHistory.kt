package com.dailymoneyregister.feature.customers.ui.customerdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.customers.data.CustomerInvoiceRow
import com.dailymoneyregister.feature.customers.ui.formatCustomerDateTime
import com.dailymoneyregister.feature.customers.ui.formatCustomerMoney

@Composable
fun ColumnScope.CustomerInvoiceHistory(
    invoices: List<CustomerInvoiceRow>,
    onOpenInvoice: (CustomerInvoiceRow) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.audit_customer_documents),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Text(
            "${invoices.size} invoice" +
                if (invoices.size == 1) {
                    ""
                } else {
                    "s"
                },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (invoices.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                stringResource(R.string.audit_customer_no_documents),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                invoices,
                key = { it.documentId }
            ) { row ->
                InvoiceHistoryRow(
                    row = row,
                    onClick = { onOpenInvoice(row) }
                )
            }
        }
    }
}

@Composable
private fun InvoiceHistoryRow(
    row: CustomerInvoiceRow,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.displayNumber, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    formatCustomerDateTime(row.createdAt),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val total = row.totalMinor
                val code = row.currencyCode
                val places = row.decimalPlaces
                Text(
                    text = if (total != null && code != null && places != null) {
                        formatCustomerMoney(total, places, code)
                    } else {
                        stringResource(R.string.customers_amount_unavailable)
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
