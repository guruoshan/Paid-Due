package com.dailymoneyregister.feature.notifications.ui

import androidx.compose.ui.res.stringResource

import com.dailymoneyregister.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Divider
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.feature.notifications.data.BusinessNotification
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    requestPermission: String? = null,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.audit_refresh_notifications))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }

            Text(stringResource(R.string.audit_inbox), style = MaterialTheme.typography.titleLarge)
            if (state.notifications.isEmpty() && !state.isLoading) {
                Text(stringResource(R.string.audit_no_notifications), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.notifications.forEach { notification ->
                NotificationCard(
                    notification = notification,
                    onMarkRead = { viewModel.markRead(notification.id) }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }


}

@Composable
private fun NotificationCard(
    notification: BusinessNotification,
    onMarkRead: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (notification.readAt == null) {
                    Text("NEW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            notification.branchName?.let { Text("Branch: $it", style = MaterialTheme.typography.bodySmall) }
            Text(notification.body, style = MaterialTheme.typography.bodyMedium)
            if (notification.notificationType == "DAY_CLOSED" ||
                notification.notificationType == "CASH_DISCREPANCY") {
                notification.payload["report"]?.let { reportValue ->
                    val report = runCatching { reportValue.jsonObject }.getOrNull()
                    if (report != null && report.isNotEmpty()) {
                        ClosingReport(report)
                    } else {
                        Text(stringResource(R.string.audit_old_close_values_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } ?: Text(
                    stringResource(R.string.audit_old_close_values_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(notification.createdAt.replace("T", " ").substringBefore("."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (notification.readAt == null) {
                TextButton(onClick = onMarkRead) { Text(stringResource(R.string.audit_mark_read)) }
            }
        }
    }
}

@Composable
private fun ClosingReport(report: JsonObject) {
    val currencyCode = report["currency_code"]?.jsonPrimitive?.contentOrNull ?: "OMR"
    val currencySymbol = report["currency_symbol"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: currencyCode
    val decimals = report["currency_decimal_places"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 3) ?: 3
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Divider()
        Text(stringResource(R.string.audit_daily_shift_summary), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        ReportAmount(report, "Gross Sales", "gross_sales_minor", currencySymbol, decimals)
        ReportAmount(report, "Cash Sales Collected", "cash_sales_minor", currencySymbol, decimals)
        ReportAmount(report, "Online Sales Collected", "online_sales_minor", currencySymbol, decimals)
        ReportAmount(report, "Credit Sales Created", "credit_given_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon Value Redeemed (Not New Cash)", "coupon_used_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon top-ups", "coupon_top_up_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon Value Issued (Prepaid Liability)", "coupon_issued_minor", currencySymbol, decimals)
        ReportAmount(report, "Cash Received for Coupon Top-Ups", "cash_coupon_funding_minor", currencySymbol, decimals)
        ReportAmount(report, "Online Received for Coupon Top-Ups", "online_coupon_funding_minor", currencySymbol, decimals)
        ReportAmount(report, "Expected Cash Balance (Calculated)", "expected_cash_minor", currencySymbol, decimals)
        ReportAmount(report, "Actual Cash Counted", "counted_cash_minor", currencySymbol, decimals)
        val difference = report["difference_minor"]?.jsonPrimitive?.longOrNull ?: 0L
        val differenceText = formatMinor(difference, decimals)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (difference < 0) "Cash shortage" else if (difference > 0) "Cash overage" else "Cash difference")
            Text(
                "$currencySymbol $differenceText",
                color = if (difference == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
        ReportAmount(report, "Cash refunds", "cash_refunds_minor", currencySymbol, decimals)
        ReportAmount(report, "Online refunds", "online_refunds_minor", currencySymbol, decimals)
        ReportAmount(report, "Credit repayments (cash)", "cash_credit_repayments_minor", currencySymbol, decimals)
        ReportAmount(report, "Credit repayments (online)", "online_credit_repayments_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon refunds credited", "coupon_refund_credits_minor", currencySymbol, decimals)
        ReportAmount(report, "Cash expenses", "cash_expenses_minor", currencySymbol, decimals)
        ReportAmount(report, "Online expenses", "online_expenses_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon credit repayments", "coupon_credit_repayments_minor", currencySymbol, decimals)
        ReportAmount(report, "Cash lifecycle adjustments", "cash_lifecycle_adjustment_minor", currencySymbol, decimals)
        ReportAmount(report, "Online lifecycle adjustments", "online_lifecycle_adjustment_minor", currencySymbol, decimals)
        Text("Sales: ${report["sales_count"]?.jsonPrimitive?.contentOrNull ?: "0"} · Refunds: ${report["refund_count"]?.jsonPrimitive?.contentOrNull ?: "0"} · Voids: ${report["void_count"]?.jsonPrimitive?.contentOrNull ?: "0"} · Corrections: ${report["correction_count"]?.jsonPrimitive?.contentOrNull ?: "0"} · Payment changes: ${report["payment_method_change_count"]?.jsonPrimitive?.contentOrNull ?: "0"} · Operations: ${report["operation_count"]?.jsonPrimitive?.contentOrNull ?: "0"}")
        Divider()
    }
}

@Composable
private fun ReportAmount(
    report: JsonObject,
    label: String,
    key: String,
    symbol: String,
    decimals: Int
) {
    val minor = report[key]?.jsonPrimitive?.longOrNull ?: 0L
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("$symbol ${formatMinor(minor, decimals)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatMinor(minor: Long, decimals: Int): String =
    BigDecimal.valueOf(minor, decimals).setScale(decimals).toPlainString()

