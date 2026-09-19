package com.dailymoneyregister.feature.auth.ui

import com.dailymoneyregister.core.ui.asString

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.security.ActiveSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: (ActiveSession) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Toggle
            TabRow(
                selectedTabIndex = if (state.mode == LoginMode.CASHIER) 0 else 1,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                Tab(
                    selected = state.mode == LoginMode.CASHIER,
                    onClick = { if (state.mode != LoginMode.CASHIER) viewModel.toggleMode() },
                    text = { Text(stringResource(R.string.cashier_login), fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = state.mode == LoginMode.ADMIN,
                    onClick = { if (state.mode != LoginMode.ADMIN) viewModel.toggleMode() },
                    text = { Text(stringResource(R.string.admin_login), fontWeight = FontWeight.SemiBold) }
                )
            }

            state.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(
                        text = error.asString(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            OutlinedTextField(
                value = state.username,
                onValueChange = { viewModel.onUsernameChanged(it) },
                label = { Text(stringResource(R.string.whatsapp_mobile_number)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            if (state.mode == LoginMode.ADMIN) {
                // Admin Password Form
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { viewModel.onPasswordChanged(it) },
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                )

                Button(
                    onClick = { viewModel.executeLogin(onLoginSuccess) },
                    enabled = !state.isExecuting,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (state.isExecuting) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Text(stringResource(R.string.login), fontSize = 16.sp)
                    }
                }
            } else {
                // Cashier 6-Digit PIN Form
                Text(
                    text = stringResource(R.string.enter_6_digit_pin),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 6 Pin Dots
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    for (i in 0 until 6) {
                        val isFilled = i < state.pin.length
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isFilled) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }

                if (state.lockoutSecondsRemaining > 0L) {
                    Text(
                        text = stringResource(R.string.lockout_countdown, state.lockoutSecondsRemaining.toString()),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Numeric Keypad
                PinKeypad(
                    onDigit = { viewModel.onPinDigitEntered(it) },
                    onBackspace = { viewModel.onPinBackspace() },
                    onClear = { viewModel.onPinClear() },
                    isEnabled = state.lockoutSecondsRemaining == 0L && !state.isExecuting
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.executeLogin(onLoginSuccess) },
                    enabled = state.pin.length == 6 && state.lockoutSecondsRemaining == 0L && !state.isExecuting,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (state.isExecuting) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Text(stringResource(R.string.login), fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    isEnabled: Boolean
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('C', '0', 'B')
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                for (item in row) {
                    when (item) {
                        'C' -> {
                            OutlinedButton(
                                onClick = onClear,
                                enabled = isEnabled,
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape
                            ) {
                                Text(stringResource(R.string.clear_key), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        'B' -> {
                            OutlinedButton(
                                onClick = onBackspace,
                                enabled = isEnabled,
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.backspace))
                            }
                        }
                        else -> {
                            FilledTonalButton(
                                onClick = { onDigit(item) },
                                enabled = isEnabled,
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape
                            ) {
                                Text(item.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
