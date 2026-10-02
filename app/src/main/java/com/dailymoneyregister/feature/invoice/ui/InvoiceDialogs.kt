package com.dailymoneyregister.feature.invoice.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.dailymoneyregister.R
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.feature.register.domain.DraftState

/**
 * Single entry point used by RegisterScreen.
 * Renders the customer form and the "invoice saved" confirmation.
 */
@Composable
fun InvoiceFlow(
    viewModel: InvoiceViewModel,
    draftState: DraftState,
    currency: CurrencyConfig
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it.resolve(context), Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    if (state.isFormOpen) {
        InvoiceFormDialog(
            state = state,
            itemCount = draftState.items.size,
            totalText = draftState.grandTotal.formatDisplay(currency),
            onTitleChange = viewModel::onTitleChange,
            onNameChange = viewModel::onNameChange,
            onPhoneChange = viewModel::onPhoneChange,
            onAddressChange = viewModel::onAddressChange,
            onSave = { viewModel.issue(draftState) },
            onDismiss = viewModel::dismissForm
        )
    }

    state.issuedNumber?.let { number ->
        InvoiceSavedDialog(
            number = number,
            isPreparingShare = state.isPreparingShare,
            onSharePdf = { viewModel.sharePdf(context) },
            onShareWhatsApp = { viewModel.shareWhatsApp(context) },
            onDismiss = viewModel::dismissIssued
        )
    }
}

@Composable
fun InvoiceFormDialog(
    state: InvoiceUiState,
    itemCount: Int,
    totalText: String,
    onTitleChange: (String?) -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (com.dailymoneyregister.core.ui.PhoneNumberInputValue) -> Unit,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val phone = state.phone
    val nameMissing = state.showValidation && state.name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inv_form_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.inv_form_intro, itemCount, totalText),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    stringResource(R.string.inv_customer_title),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TitleChip("MR", R.string.inv_title_mr, state.title, onTitleChange)
                    TitleChip("MRS", R.string.inv_title_mrs, state.title, onTitleChange)
                    TitleChip("MS", R.string.inv_title_ms, state.title, onTitleChange)
                    TitleChip("DR", R.string.inv_title_dr, state.title, onTitleChange)
                }

                if (phone != null) {
                    PhoneNumberInput(
                        value = phone,
                        onValueChange = onPhoneChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isIssuing,
                        showValidation = state.showValidation
                    )
                }

                state.existingCustomerName?.let {
                    Text(
                        stringResource(R.string.inv_existing_customer, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    enabled = !state.isIssuing && state.existingCustomerName == null,
                    isError = nameMissing,
                    label = { Text(stringResource(R.string.inv_customer_name)) },
                    supportingText = if (nameMissing) {
                        { Text(stringResource(R.string.inv_error_customer)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    enabled = !state.isIssuing,
                    label = { Text(stringResource(R.string.inv_customer_address)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !state.isIssuing) {
                Text(
                    stringResource(
                        if (state.isIssuing) R.string.inv_saving else R.string.inv_save
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isIssuing) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun TitleChip(
    code: String,
    labelRes: Int,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    FilterChip(
        selected = selected == code,
        onClick = { onSelect(if (selected == code) null else code) },
        label = { Text(stringResource(labelRes)) }
    )
}

@Composable
fun InvoiceSavedDialog(
    number: String,
    isPreparingShare: Boolean,
    onSharePdf: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inv_saved_title)) },
        text = { Text(stringResource(R.string.inv_saved_message, number)) },
        confirmButton = {
            Row {
                TextButton(
                    onClick = onShareWhatsApp,
                    enabled = !isPreparingShare,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF25D366)
                    )
                ) {
                    Text(stringResource(R.string.inv_pdf_share_whatsapp))
                }
                TextButton(onClick = onSharePdf, enabled = !isPreparingShare) {
                    if (isPreparingShare) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text(stringResource(R.string.inv_pdf_share_pdf))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
