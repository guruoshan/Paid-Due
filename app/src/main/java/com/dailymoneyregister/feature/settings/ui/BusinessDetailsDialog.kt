package com.dailymoneyregister.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity

/**
 * Admin-only form for the business name and the current branch's address,
 * phone and tax registration number. These fields are display-only data:
 * they feed the invoice PDF header and are not part of any financial record.
 */
@Composable
fun BusinessDetailsDialog(
    business: BusinessEntity,
    branch: BranchEntity,
    isSaving: Boolean,
    error: String?,
    onSave: (name: String, address: String, phone: String, taxId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(business.id) { mutableStateOf(business.name) }
    var address by remember(branch.id) { mutableStateOf(branch.address.orEmpty()) }
    var phone by remember(branch.id) { mutableStateOf(branch.phone.orEmpty()) }
    var taxId by remember(branch.id) { mutableStateOf(branch.taxRegistrationNumber.orEmpty()) }

    val nameMissing = name.isBlank()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.biz_details_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.biz_details_intro),
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 120) name = it },
                    enabled = !isSaving,
                    isError = nameMissing,
                    label = { Text(stringResource(R.string.biz_details_name)) },
                    supportingText = if (nameMissing) {
                        { Text(stringResource(R.string.biz_details_name_required)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { if (it.length <= 300) address = it },
                    enabled = !isSaving,
                    label = { Text(stringResource(R.string.biz_details_address)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 40) phone = it },
                    enabled = !isSaving,
                    label = { Text(stringResource(R.string.biz_details_phone)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = taxId,
                    onValueChange = { if (it.length <= 60) taxId = it },
                    enabled = !isSaving,
                    label = { Text(stringResource(R.string.biz_details_tax_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, address, phone, taxId) },
                enabled = !isSaving && !nameMissing
            ) {
                Text(stringResource(if (isSaving) R.string.biz_details_saving else R.string.biz_details_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
