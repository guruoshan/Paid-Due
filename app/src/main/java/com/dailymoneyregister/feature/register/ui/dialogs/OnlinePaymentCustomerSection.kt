package com.dailymoneyregister.feature.register.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue

@Composable
internal fun OnlinePaymentCustomerSection(
    customerName: String,
    customerPhone: PhoneNumberInputValue,
    normalizedPhone: String?,
    lookupCompleted: Boolean,
    existingCustomerName: String?,
    needsNewCustomerName: Boolean,
    isExecuting: Boolean,
    isLookupLoading: Boolean,
    confirmEnabled: Boolean,
    onPhoneChange: (PhoneNumberInputValue) -> Unit,
    onFindCustomer: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Text(
        stringResource(R.string.audit_customer_optional),
        fontWeight = FontWeight.SemiBold
    )
    Text(stringResource(R.string.audit_customer_phone_optional_instruction))

    PhoneNumberInput(
        value = customerPhone,
        onValueChange = onPhoneChange,
        enabled = !isExecuting && !isLookupLoading,
        modifier = Modifier.fillMaxWidth()
    )

    if (normalizedPhone != null && !lookupCompleted) {
        OutlinedButton(
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
            text = stringResource(R.string.audit_existing_customer, existingCustomerName),
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

    Button(
        onClick = onConfirm,
        enabled = confirmEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(
            stringResource(R.string.payment_received),
            fontSize = 17.sp
        )
    }
    TextButton(
        onClick = onCancel,
        enabled = !isExecuting
    ) {
        Text(stringResource(R.string.cancel))
    }
}
