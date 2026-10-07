package com.dailymoneyregister.feature.invite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.PasswordField

@Composable
fun InviteActivateScreen(
    viewModel: InviteActivateViewModel,
    onActivated: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (state.phase) {
            InvitePhase.LOADING -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.invite_loading))
            }

            InvitePhase.INVALID -> {
                Text(
                    text = stringResource(R.string.invite_invalid_message),
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.invite_back))
                }
            }

            InvitePhase.READY -> {
                val preview = state.preview
                Text(
                    text = stringResource(R.string.invite_activate_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(stringResource(R.string.invite_activate_subtitle))

                if (preview != null) {
                    InfoRow(R.string.invite_label_business, preview.businessName)
                    InfoRow(R.string.invite_label_branch, preview.branchName)
                    InfoRow(
                        R.string.invite_label_role,
                        stringResource(R.string.invite_role_cashier)
                    )
                    InfoRow(R.string.invite_label_phone, preview.invite.canonicalPhone)
                    InfoRow(R.string.invite_label_name, preview.invite.displayName)
                }

                val pinKeyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

                PasswordField(
                    value = state.pin,
                    onValueChange = viewModel::onPinChanged,
                    label = stringResource(R.string.invite_pin_label),
                    enabled = !state.isActivating,
                    keyboardOptions = pinKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                PasswordField(
                    value = state.confirmPin,
                    onValueChange = viewModel::onConfirmPinChanged,
                    label = stringResource(R.string.invite_confirm_pin_label),
                    enabled = !state.isActivating,
                    keyboardOptions = pinKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )

                state.errorRes?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = { viewModel.activate(onActivated) },
                    enabled = !state.isActivating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.invite_activate_button))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(labelRes: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
