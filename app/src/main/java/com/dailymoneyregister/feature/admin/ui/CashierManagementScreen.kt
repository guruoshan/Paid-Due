package com.dailymoneyregister.feature.admin.ui

import com.dailymoneyregister.core.ui.nativeLabel

import com.dailymoneyregister.core.ui.asString

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.i18n.LanguageRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashierManagementScreen(
    viewModel: CashierManagementViewModel,
    onNavigateBack: () -> Unit
) {
    val cashiers by viewModel.cashiers.collectAsState()
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_cashiers)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openCreateDialog() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_cashier))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            state.successMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(msg.asString(), color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(12.dp))
                }
            }

            if (cashiers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_cashiers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(cashiers, key = { it.id }) { cashier ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp).padding(end = 16.dp)
                                )
                                Column {
                                    Text(text = cashier.displayName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = stringResource(R.string.cashier_language_summary, cashier.username, stringResource(LanguageRegistry.findByCode(cashier.languageCode).nativeLabel)),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.isCreateDialogOpen) {
            CreateCashierDialog(
                state = state,
                onDismiss = { viewModel.closeCreateDialog() },
                onNameChanged = { viewModel.onNameChanged(it) },
                onPhoneChanged = { viewModel.onPhoneChanged(it) },
                onPinChanged = { viewModel.onPinChanged(it) },
                onLanguageChanged = { viewModel.onLanguageChanged(it) },
                onToggleRefund = { viewModel.toggleRefundPermission(it) },
                onToggleVoid = { viewModel.toggleVoidPermission(it) },
                onToggleCredit = { viewModel.toggleCreditSalePermission(it) },
                onSave = { viewModel.executeCreateCashier() }
            )
        }
    }
}

@Composable
private fun CreateCashierDialog(
    state: CashierManagementUiState,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onPinChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onToggleRefund: (Boolean) -> Unit,
    onToggleVoid: (Boolean) -> Unit,
    onToggleCredit: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_cashier)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.errorMessage?.let { error ->
                    Text(error.asString(), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }

                OutlinedTextField(
                    value = state.newCashierName,
                    onValueChange = onNameChanged,
                    label = { Text(stringResource(R.string.cashier_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.newCashierPhone,
                    onValueChange = onPhoneChanged,
                    label = { Text(stringResource(R.string.whatsapp_mobile_number)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.newCashierPin,
                    onValueChange = onPinChanged,
                    label = { Text(stringResource(R.string.cashier_pin)) },
                    placeholder = { Text(stringResource(R.string.cashier_pin_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.preferred_language),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LanguageRegistry.allLanguages.forEach { lang ->
                        val selected = state.newCashierLanguage == lang.code
                        FilterChip(
                            selected = selected,
                            onClick = { onLanguageChanged(lang.code) },
                            label = { Text(stringResource(lang.nativeLabel), fontSize = 11.sp) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.permissions),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.canCreateCreditSale, onCheckedChange = onToggleCredit)
                    Text(stringResource(R.string.permission_credit_sale))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.canRefund, onCheckedChange = onToggleRefund)
                    Text(stringResource(R.string.permission_refund))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.canVoid, onCheckedChange = onToggleVoid)
                    Text(stringResource(R.string.permission_void))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !state.isExecuting
            ) {
                if (state.isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.save_cashier))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
