package com.dailymoneyregister.feature.customers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.dao.CustomerSummary
import com.dailymoneyregister.core.ui.asString

data class CustomersScreenActions(
    val onNavigateBack: () -> Unit,
    val onExportCustomers: () -> Unit,
    val onOpenCustomer: (String) -> Unit,
    val onReload: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSelectFilter: (CustomerListFilter) -> Unit,
    val onAskToLinkOlderInvoices: () -> Unit,
    val onConfirmLinkOlderInvoices: () -> Unit,
    val onDismissLinkConfirm: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(
    state: CustomersUiState,
    actions: CustomersScreenActions
) {
    val visible = state.visible
    val lifecycleOwner = LocalLifecycleOwner.current

    // BOARDLOCK_CUSTOMERS_REFRESH_ON_RESUME
    //
    // CustomersViewModel can survive navigation/back-stack restoration.
    // Its init{} load therefore cannot be treated as a guarantee that the
    // list reflects customers committed after that initial load.
    //
    // CustomerEntity remains the authoritative identity source. Whenever
    // this screen becomes active, reload the projection from the database.
    //
    // No customer or financial state is copied or mutated here.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    actions.onReload()
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customers_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = actions.onExportCustomers, modifier = Modifier.fillMaxWidth()) { Text("EXPORT CUSTOMERS") }
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.customers_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            val customerFilters =
                listOf(
                    CustomerListFilter.ALL to "All",
                    CustomerListFilter.PAYMENTS to "Payments",
                    CustomerListFilter.CREDIT to "Credit",
                    CustomerListFilter.COUPONS to "Coupons",
                    CustomerListFilter.INVOICES to "Invoices"
                )

            PrimaryScrollableTabRow(
                selectedTabIndex =
                    customerFilters.indexOfFirst {
                        it.first == state.selectedFilter
                    }
                        .coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 4.dp
            ) {
                customerFilters.forEach { (filter, label) ->
                    Tab(
                        selected = state.selectedFilter == filter,
                        onClick = {
                            actions.onSelectFilter(filter)
                        },
                        text = {
                            Text(
                                text = label,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            state.error?.let { error ->
                Text(error.asString(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = actions.onReload) {
                    Text(stringResource(R.string.retry))
                }
            }

            state.linkResult?.let { result ->
                Text(result.asString(), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
            state.linkError?.let { error ->
                Text(error.asString(), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }

            if (state.showLinkBanner) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            pluralStringResource(R.plurals.customers_unlinked_banner, state.unlinkedCount, state.unlinkedCount),
                            fontSize = 14.sp
                        )
                        OutlinedButton(
                            onClick = actions.onAskToLinkOlderInvoices,
                            enabled = !state.isLinking
                        ) {
                            Text(stringResource(R.string.customers_unlinked_action))
                        }
                    }
                }
            }

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                visible.isEmpty() -> {
                    if (state.error == null) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(
                                    if (state.customers.isEmpty()) R.string.customers_empty
                                    else R.string.customers_no_match
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visible, key = { it.id }) { customer ->
                            CustomerRow(customer = customer, onClick = { actions.onOpenCustomer(customer.id) })
                        }
                    }
                }
            }
        }
    }

    if (state.showLinkConfirm) {
        LinkConfirmDialog(
            onConfirm = actions.onConfirmLinkOlderInvoices,
            onDismiss = actions.onDismissLinkConfirm
        )
    }
}

@Composable
private fun LinkConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customers_link_confirm_title)) },
        text = { Text(stringResource(R.string.customers_link_confirm_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.customers_link_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerRow(customer: CustomerSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(customer.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                customer.phone,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val lastInvoiceAt = customer.lastInvoiceAt
            Text(
                text = if (customer.invoiceCount > 0 && lastInvoiceAt != null) {
                    stringResource(R.string.customers_invoices_count, customer.invoiceCount) +
                        "  \u2022  " +
                        stringResource(R.string.customers_last_invoice, formatCustomerDate(lastInvoiceAt))
                } else {
                    stringResource(R.string.customers_no_invoices_yet)
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

