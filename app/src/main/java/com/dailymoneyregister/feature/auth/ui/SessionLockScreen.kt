package com.dailymoneyregister.feature.auth.ui

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.SessionBiometricKey
import java.security.KeyPermanentlyInvalidatedException

@Composable
fun SessionLockScreen(viewModel: SessionLockViewModel = hiltViewModel()) {
    val session = SessionManager.currentSession.collectAsState().value
    val context = LocalContext.current
    val activity = remember(context) { findFragmentActivity(context) }
    val biometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
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
        if (biometricAvailable && activity != null) {
            Button(
                onClick = {
                    try {
                        val cipher = SessionBiometricKey.createCipher()
                        val prompt = BiometricPrompt(
                            activity,
                            ContextCompat.getMainExecutor(context),
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(
                                    result: BiometricPrompt.AuthenticationResult
                                ) {
                                    viewModel.unlockWithBiometric()
                                }

                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                                        errorCode != BiometricPrompt.ERROR_CANCELED
                                    ) {
                                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Unlock Paid&Due")
                            .setSubtitle("Confirm your identity with strong biometrics")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                            .setNegativeButtonText("Use password or PIN")
                            .build()
                        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                    } catch (_: KeyPermanentlyInvalidatedException) {
                        Toast.makeText(
                            context,
                            "Biometrics changed. Unlock with your password or PIN to restore biometric access.",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {
                        Toast.makeText(context, "Biometric unlock is unavailable. Use your password or PIN.", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Unlock with biometrics")
            }
        }
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


private fun findFragmentActivity(context: Context): FragmentActivity? {
    var current: Context = context
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return current as? FragmentActivity
}
