package com.dailymoneyregister.feature.register.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue

@Composable
internal fun CashCustomerDialog(
    customerName: String,
    customerPhone: PhoneNumberInputValue,
    normalizedPhone: String?,
    lookupCompleted: Boolean,
    existingCustomerName: String?,
    needsNewCustomerName: Boolean,
    isExecuting: Boolean,
    isLookupLoading: Boolean,
    onPhoneChange: (PhoneNumberInputValue) -> Unit,
    onNameChange: (String) -> Unit,
    onFindCustomer: (String) -> Unit,
    onSkipCustomer: () -> Unit,
    onUseExistingCustomer: (String) -> Unit,
    onSaveNewCustomer: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isExecuting) {
                onDismiss()
            }
        },
        title = {
            Text(
                stringResource(R.string.audit_customer_optional),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.audit_customer_phone_instruction))

                PhoneNumberInput(
                    value = customerPhone,
                    onValueChange = onPhoneChange,
                    enabled = !isExecuting && !isLookupLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (normalizedPhone != null && !lookupCompleted) {
                    Button(
                        onClick = { onFindCustomer(normalizedPhone) },
                        enabled = !isExecuting && !isLookupLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isLookupLoading) "Finding Customer..." else "Find Customer"
                        )
                    }
                }

                if (existingCustomerName != null) {
                    Text(
                        text = stringResource(
                            R.string.audit_existing_customer,
                            existingCustomerName
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (needsNewCustomerName) {
                    Text(stringResource(R.string.audit_no_existing_customer))
                    OutlinedTextField(
                        value = customerName,
                        onValueChange = onNameChange,
                        label = { Text(stringResource(R.string.audit_customer_name)) },
                        singleLine = true,
                        enabled = !isExecuting,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            when {
                normalizedPhone == null -> {
                    Button(
                        onClick = onSkipCustomer,
                        enabled = !isExecuting
                    ) {
                        Text(stringResource(R.string.audit_skip_customer))
                    }
                }

                existingCustomerName != null -> {
                    Button(
                        onClick = { onUseExistingCustomer(existingCustomerName) },
                        enabled = !isExecuting && !isLookupLoading
                    ) {
                        Text(stringResource(R.string.audit_save_sale))
                    }
                }

                needsNewCustomerName -> {
                    Button(
                        onClick = {
                            onSaveNewCustomer(normalizedPhone, customerName.trim())
                        },
                        enabled = !isExecuting && customerName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.audit_save_sale))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isExecuting
            ) {
                Text(stringResource(R.string.audit_cancel))
            }
        }
    )
}
