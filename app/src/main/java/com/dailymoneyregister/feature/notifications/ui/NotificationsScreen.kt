package com.dailymoneyregister.feature.notifications.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dailymoneyregister.feature.notifications.data.PermissionRequestRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

private val permissionLabels = linkedMapOf(
    "CREATE_SALE" to "Create sales",
    "ACCEPT_CASH" to "Accept cash",
    "ACCEPT_ONLINE" to "Accept online payments",
    "CREATE_CREDIT_SALE" to "Create credit sales",
    "USE_COUPON" to "Use coupons",
    "ISSUE_INVOICE" to "Issue invoices",
    "REFUND" to "Refund transactions",
    "VOID" to "Void transactions",
    "CORRECT_AMOUNT" to "Correct amounts",
    "CHANGE_PAYMENT_METHOD" to "Change payment method",
    "VIEW_BROAD_HISTORY" to "View full history"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    requestPermission: String? = null,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedPermission by remember(requestPermission) {
        mutableStateOf(requestPermission?.takeIf { it in permissionLabels } ?: "REFUND")
    }
    var permissionsMenuOpen by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf("") }
    var reviewingRequest by remember { mutableStateOf<PermissionRequestRecord?>(null) }
    var additionalPermissions by remember(reviewingRequest?.id) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh notifications")
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

            if (!state.isAdmin) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Ask Admin for permission", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Choose the feature you need. Your request is sent to an Admin for this branch; access is not granted automatically.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Column {
                            Button(onClick = { permissionsMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Request: ${permissionLabels[selectedPermission] ?: selectedPermission}")
                            }
                            DropdownMenu(
                                expanded = permissionsMenuOpen,
                                onDismissRequest = { permissionsMenuOpen = false }
                            ) {
                                permissionLabels.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            selectedPermission = key
                                            permissionsMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = permissionNote,
                            onValueChange = { permissionNote = it.take(500) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Reason (optional)") },
                            placeholder = { Text("Why do you need this access?") },
                            minLines = 2,
                            maxLines = 4
                        )
                        Button(
                            onClick = {
                                viewModel.requestPermission(selectedPermission, permissionNote.trim())
                                permissionNote = ""
                            },
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Send permission request") }
                    }
                }
            }

            if (state.isAdmin && state.permissionRequests.isNotEmpty()) {
                Text("Pending permission requests", style = MaterialTheme.typography.titleLarge)
                state.permissionRequests.forEach { request ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(request.requesterName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Branch: ${request.branchName}")
                            Text("Requested: ${permissionLabels[request.permissionKey] ?: request.permissionKey}")
                            request.note?.takeIf { it.isNotBlank() }?.let { Text("Reason: $it") }
                            Text("Pending review", color = MaterialTheme.colorScheme.tertiary)
                            Button(
                                onClick = { reviewingRequest = request },
                                enabled = !state.isLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Review request") }
                        }
                    }
                }
            }

            Text("Inbox", style = MaterialTheme.typography.titleLarge)
            if (state.notifications.isEmpty() && !state.isLoading) {
                Text("No notifications yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    reviewingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { reviewingRequest = null },
            title = { Text("Review permission request") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("${request.requesterName} requested ${permissionLabels[request.permissionKey] ?: request.permissionKey}.")
                    Text("You may grant the requested permission only, or also select extra permissions.")
                    Divider()
                    permissionLabels.forEach { (key, label) ->
                        if (key != request.permissionKey) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = key in additionalPermissions,
                                    onCheckedChange = { checked ->
                                        additionalPermissions =
                                            if (checked) additionalPermissions + key
                                            else additionalPermissions - key
                                    }
                                )
                                Text(label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isLoading,
                    onClick = {
                        viewModel.resolvePermissionRequest(request.id, true, additionalPermissions)
                        reviewingRequest = null
                    }
                ) { Text("Approve selected") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !state.isLoading,
                        onClick = {
                            viewModel.resolvePermissionRequest(request.id, false, emptySet())
                            reviewingRequest = null
                        }
                    ) { Text("Reject") }
                    TextButton(onClick = { reviewingRequest = null }) { Text("Cancel") }
                }
            }
        )
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
                        Text("Detailed closing values are unavailable for this older close event.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } ?: Text(
                    "Detailed closing values are unavailable for this older close event.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(notification.createdAt.replace("T", " ").substringBefore("."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (notification.readAt == null) {
                TextButton(onClick = onMarkRead) { Text("Mark as read") }
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
        Text("Daily closing summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        ReportAmount(report, "Total sales", "gross_sales_minor", currencySymbol, decimals)
        ReportAmount(report, "Cash sales", "cash_sales_minor", currencySymbol, decimals)
        ReportAmount(report, "Online sales", "online_sales_minor", currencySymbol, decimals)
        ReportAmount(report, "Credit sales", "credit_given_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon payments", "coupon_used_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupon top-ups", "coupon_top_up_minor", currencySymbol, decimals)
        ReportAmount(report, "Coupons issued", "coupon_issued_minor", currencySymbol, decimals)
        ReportAmount(report, "Cash coupon funding", "cash_coupon_funding_minor", currencySymbol, decimals)
        ReportAmount(report, "Online coupon funding", "online_coupon_funding_minor", currencySymbol, decimals)
        ReportAmount(report, "Expected cash", "expected_cash_minor", currencySymbol, decimals)
        ReportAmount(report, "Counted cash", "counted_cash_minor", currencySymbol, decimals)
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

