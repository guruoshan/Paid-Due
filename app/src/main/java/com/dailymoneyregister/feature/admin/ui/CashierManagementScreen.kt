package com.dailymoneyregister.feature.admin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.material.icons.filled.MoreVert
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
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository.DirectoryEntry
import com.dailymoneyregister.core.i18n.LanguageRegistry
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.ui.PhoneNumberInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashierManagementScreen(
    viewModel: CashierManagementViewModel,
    onNavigateBack: () -> Unit
) {
    val cashiers by viewModel.cashiers.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val inviteState by viewModel.inviteState.collectAsState()
    val context = LocalContext.current

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
            ExtendedFloatingActionButton(
                onClick = viewModel::openInviteDialog
            ) {
                Text(stringResource(R.string.invite_by_whatsapp))
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

            // Errors raised while no dialog is open (for example "cashier could not be loaded").
            if (state.errorMessage != null && !state.isCreateDialogOpen && state.dialog is StaffDialogState.None) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        state.errorMessage!!.asString(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
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
                        CashierRow(
                            cashier = cashier,
                            onEditDetails = { viewModel.openEditDetails(cashier) },
                            onEditPermissions = { viewModel.openEditPermissions(cashier) },
                            onResetPin = { viewModel.openResetPin(cashier) },
                            onDisable = { viewModel.openDisableConfirm(cashier) },
                            onEnable = { viewModel.openEnableConfirm(cashier) },
                            onRemove = { viewModel.openRemoveConfirm(cashier) }
                        )
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

        if (inviteState.isDialogOpen) {
            val createdInvite = inviteState.createdInvite
            if (createdInvite == null) {
                CreateInviteDialog(
                    state = inviteState,
                    branchName = SessionManager.activeBranch?.name.orEmpty(),
                    onDismiss = viewModel::clearInvite,
                    onNameChanged = viewModel::onInviteNameChanged,
                    onPhoneChanged = viewModel::onInvitePhoneChanged,
                    onPermissionChanged = viewModel::onInvitePermissionChanged,
                    onCreate = viewModel::createInvite
                )
            } else {
                InviteReadyDialog(
                    createdInvite = createdInvite,
                    onClose = viewModel::clearInvite,
                    onSendWhatsApp = {
                        val message = "You have been invited to join Paid&Due as " +
                            createdInvite.displayName + ". Tap this link to activate your account and set your 6-digit PIN: " +
                            createdInvite.inviteUrl +
                            "\n\nThis invitation expires in 48 hours and can be used once."
                        val phoneDigits = createdInvite.canonicalPhone.filter(Char::isDigit)
                        val whatsappUri = Uri.parse("https://wa.me/" + phoneDigits + "?text=" + Uri.encode(message))
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, whatsappUri))
                        } catch (_: Exception) {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, message)
                            }
                            context.startActivity(Intent.createChooser(share, "Send cashier invitation"))
                        }
                    }
                )
            }
        }

        when (val dialog = state.dialog) {
            is StaffDialogState.EditDetails -> EditDetailsDialog(
                dialog = dialog,
                state = state,
                onDismiss = { viewModel.dismissStaffDialog() },
                onNameChanged = { viewModel.onEditNameChanged(it) },
                onPhoneChanged = { viewModel.onEditPhoneChanged(it) },
                onLanguageChanged = { viewModel.onEditLanguageChanged(it) },
                onSave = { viewModel.saveEditDetails() }
            )

            is StaffDialogState.EditPermissions -> EditPermissionsDialog(
                dialog = dialog,
                state = state,
                onDismiss = { viewModel.dismissStaffDialog() },
                onChanged = { key, value -> viewModel.onEditPermissionChanged(key, value) },
                onSave = { viewModel.saveEditPermissions() }
            )

            is StaffDialogState.ResetPin -> ResetPinDialog(
                dialog = dialog,
                state = state,
                onDismiss = { viewModel.dismissStaffDialog() },
                onPinChanged = { viewModel.onResetPinChanged(it) },
                onSave = { viewModel.saveResetPin() }
            )

            is StaffDialogState.ConfirmDisable -> ConfirmDialog(
                title = stringResource(R.string.staff_disable_title, dialog.name),
                message = stringResource(R.string.staff_disable_message),
                confirmLabel = stringResource(R.string.staff_disable),
                state = state,
                onDismiss = { viewModel.dismissStaffDialog() },
                onConfirm = { viewModel.confirmDisable() }
            )

            is StaffDialogState.ConfirmEnable -> ConfirmDialog(
                title = stringResource(R.string.staff_enable_title, dialog.name),
                message = stringResource(R.string.staff_enable_message),
                confirmLabel = stringResource(R.string.staff_enable),
                state = state,
                onDismiss = { viewModel.dismissStaffDialog() },
                onConfirm = { viewModel.confirmEnable() }
            )

            is StaffDialogState.ConfirmRemove -> ConfirmDialog(
                title = stringResource(R.string.staff_delete_title, dialog.name),
                message = stringResource(R.string.staff_delete_message),
                confirmLabel = stringResource(R.string.staff_delete),
                state = state,
                onDismiss = { viewModel.dismissStaffDialog() },
                onConfirm = { viewModel.confirmRemove() }
            )

            StaffDialogState.None -> Unit
        }
    }
}

@Composable
private fun CreateInviteDialog(
    state: CashierInviteUiState,
    branchName: String,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onPhoneChanged: (com.dailymoneyregister.core.ui.PhoneNumberInputValue) -> Unit,
    onPermissionChanged: (CashierPermissionKey, Boolean) -> Unit,
    onCreate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_by_whatsapp)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.errorMessage?.let {
                    Text(it.asString(), color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = onNameChanged,
                    label = { Text(stringResource(R.string.cashier_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PhoneNumberInput(
                    value = state.phone,
                    onValueChange = onPhoneChanged,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = branchName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.invite_branch)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.permissions),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                CashierPermissionKey.editableValues.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.permissions[key.name] == true,
                            onCheckedChange = { onPermissionChanged(key, it) }
                        )
                        Text(stringResource(key.labelRes))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCreate, enabled = !state.isExecuting) {
                if (state.isExecuting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.create_invite))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun InviteReadyDialog(
    createdInvite: com.dailymoneyregister.feature.invite.data.CreatedInvite,
    onClose: () -> Unit,
    onSendWhatsApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.invite_sent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.invite_sent_message, createdInvite.canonicalPhone))
                Text(stringResource(R.string.invite_expiry_note))
            }
        },
        confirmButton = {
            Button(onClick = onSendWhatsApp) {
                Text(stringResource(R.string.send_whatsapp_invitation))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun CashierRow(
    cashier: DirectoryEntry,
    onEditDetails: () -> Unit,
    onEditPermissions: () -> Unit,
    onResetPin: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = if (cashier.isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp).padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = cashier.displayName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.cashier_language_summary, cashier.username, stringResource(LanguageRegistry.findByCode(cashier.languageCode).nativeLabel)),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!cashier.isActive) {
                    Text(
                        text = stringResource(R.string.staff_status_disabled),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (cashier.membershipId != null || cashier.localAccount != null) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.staff_more_actions)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (cashier.membershipId != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.staff_edit_permissions)) },
                            onClick = { menuOpen = false; onEditPermissions() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.staff_delete)) },
                            onClick = { menuOpen = false; onRemove() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.staff_edit_details)) },
                            onClick = { menuOpen = false; onEditDetails() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.staff_edit_permissions)) },
                            onClick = { menuOpen = false; onEditPermissions() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.staff_reset_pin)) },
                            onClick = { menuOpen = false; onResetPin() }
                        )
                        if (cashier.isActive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.staff_disable)) },
                                onClick = { menuOpen = false; onDisable() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.staff_enable)) },
                                onClick = { menuOpen = false; onEnable() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffDialogError(state: CashierManagementUiState) {
    state.errorMessage?.let { error ->
        Text(error.asString(), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
    }
}

@Composable
private fun SaveButton(state: CashierManagementUiState, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !state.isExecuting) {
        if (state.isExecuting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(label)
        }
    }
}

@Composable
private fun EditDetailsDialog(
    dialog: StaffDialogState.EditDetails,
    state: CashierManagementUiState,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.staff_edit_details_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StaffDialogError(state)

                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = onNameChanged,
                    label = { Text(stringResource(R.string.cashier_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = dialog.phone,
                    onValueChange = onPhoneChanged,
                    label = { Text(stringResource(R.string.whatsapp_mobile_number)) },
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
                        FilterChip(
                            selected = dialog.language == lang.code,
                            onClick = { onLanguageChanged(lang.code) },
                            label = { Text(stringResource(lang.nativeLabel), fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = { SaveButton(state, stringResource(R.string.save), onSave) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EditPermissionsDialog(
    dialog: StaffDialogState.EditPermissions,
    state: CashierManagementUiState,
    onDismiss: () -> Unit,
    onChanged: (CashierPermissionKey, Boolean) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.staff_edit_permissions_title, dialog.name)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                StaffDialogError(state)

                CashierPermissionKey.editableValues.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dialog.permissions.isGranted(key),
                            onCheckedChange = { onChanged(key, it) }
                        )
                        Text(stringResource(key.labelRes))
                    }
                }
            }
        },
        confirmButton = { SaveButton(state, stringResource(R.string.save), onSave) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ResetPinDialog(
    dialog: StaffDialogState.ResetPin,
    state: CashierManagementUiState,
    onDismiss: () -> Unit,
    onPinChanged: (String) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.staff_reset_pin_title, dialog.name)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StaffDialogError(state)

                Text(
                    text = stringResource(R.string.staff_reset_pin_note),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = dialog.pin,
                    onValueChange = onPinChanged,
                    label = { Text(stringResource(R.string.staff_new_pin)) },
                    placeholder = { Text(stringResource(R.string.cashier_pin_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { SaveButton(state, stringResource(R.string.staff_reset_pin), onSave) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    state: CashierManagementUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StaffDialogError(state)
                Text(message)
            }
        },
        confirmButton = { SaveButton(state, confirmLabel, onConfirm) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
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
