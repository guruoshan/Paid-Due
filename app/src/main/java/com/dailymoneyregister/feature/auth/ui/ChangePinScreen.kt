package com.dailymoneyregister.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChangePinViewModel @Inject constructor(private val authRepository: AuthRepository) : ViewModel() {
    var newPin by mutableStateOf("")
    var confirmPin by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    fun save(onSuccess: () -> Unit) {
        if (newPin.length != 6 || !newPin.all(Char::isDigit) || newPin != confirmPin) {
            error = "PINs must match and contain exactly 6 digits"
            return
        }
        saving = true
        viewModelScope.launch {
            when (val result = authRepository.changeCashierPin(newPin)) {
                is AuthResult.Success -> { saving = false; onSuccess() }
                is AuthResult.InvalidCredentials -> { saving = false; error = result.message }
                is AuthResult.Error -> { saving = false; error = result.message }
                is AuthResult.LockedOut -> { saving = false; error = "Try again later" }
            }
        }
    }
}

@Composable
fun ChangePinScreen(viewModel: ChangePinViewModel, onSuccess: () -> Unit) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Create New PIN")
        viewModel.error?.let { Text(it) }
        OutlinedTextField(viewModel.newPin, { if (it.length <= 6 && it.all(Char::isDigit)) viewModel.newPin = it }, label = { Text("New 6-digit PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(viewModel.confirmPin, { if (it.length <= 6 && it.all(Char::isDigit)) viewModel.confirmPin = it }, label = { Text("Confirm New PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.save(onSuccess) }, enabled = !viewModel.saving, modifier = Modifier.fillMaxWidth()) { Text("Save PIN") }
    }
}
