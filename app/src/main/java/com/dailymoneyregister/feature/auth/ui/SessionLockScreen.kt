package com.dailymoneyregister.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager

@Composable
fun SessionLockScreen(viewModel: SessionLockViewModel = hiltViewModel()) {
    val session = SessionManager.currentSession.collectAsState().value
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    var secret by remember { mutableStateOf("") }
    val isCashier = session?.user?.role == UserRole.CASHIER

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Paid&Due is locked", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (isCashier) "Enter your cashier PIN to continue."
            else "Enter your Admin password to continue.",
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = if (isCashier) it.filter(Char::isDigit).take(6) else it },
            label = { Text(if (isCashier) "6-digit PIN" else "Admin password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isCashier) KeyboardType.NumberPassword else KeyboardType.Password
            ),
            enabled = !busy
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Button(
            onClick = { viewModel.unlock(secret) },
            enabled = !busy && secret.isNotBlank(),
            modifier = Modifier.padding(top = 20.dp)
        ) {
            if (busy) CircularProgressIndicator()
            else Text("Unlock")
        }
    }
}
