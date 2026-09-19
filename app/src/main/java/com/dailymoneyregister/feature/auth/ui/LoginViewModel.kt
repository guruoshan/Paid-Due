package com.dailymoneyregister.feature.auth.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.UiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginMode {
    ADMIN,
    CASHIER
}

data class LoginUiState(
    val mode: LoginMode = LoginMode.CASHIER,
    val username: String = "",
    val password: String = "",
    val pin: String = "", // 6 digits
    val isExecuting: Boolean = false,
    val lockoutSecondsRemaining: Long = 0L,
    val errorMessage: UiText? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    fun toggleMode() {
        val nextMode = if (_uiState.value.mode == LoginMode.CASHIER) LoginMode.ADMIN else LoginMode.CASHIER
        _uiState.value = _uiState.value.copy(
            mode = nextMode,
            password = "",
            pin = "",
            errorMessage = null
        )
    }

    fun onUsernameChanged(username: String) {
        _uiState.value = _uiState.value.copy(username = username, errorMessage = null)
        checkLockout(username)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun onPinDigitEntered(digit: Char) {
        if (_uiState.value.lockoutSecondsRemaining > 0L) return
        val currentPin = _uiState.value.pin
        if (currentPin.length < 6) {
            val newPin = currentPin + digit
            _uiState.value = _uiState.value.copy(pin = newPin, errorMessage = null)
        }
    }

    fun onPinBackspace() {
        val currentPin = _uiState.value.pin
        if (currentPin.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(pin = currentPin.dropLast(1), errorMessage = null)
        }
    }

    fun onPinClear() {
        _uiState.value = _uiState.value.copy(pin = "", errorMessage = null)
    }

    private fun checkLockout(username: String) {
        val remaining = authRepository.getThrottler().getRemainingLockoutSeconds(username.trim())
        if (remaining > 0L) {
            startLockoutCountdown(remaining)
        }
    }

    fun executeLogin(onSuccess: (ActiveSession) -> Unit) {
        val s = _uiState.value
        if (s.username.isBlank()) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.username_required))
            return
        }

        _uiState.value = s.copy(isExecuting = true, errorMessage = null)

        viewModelScope.launch {
            val result = if (s.mode == LoginMode.ADMIN) {
                authRepository.loginAdmin(s.username, s.password)
            } else {
                authRepository.loginCashier(s.username, s.pin)
            }

            _uiState.value = _uiState.value.copy(isExecuting = false)

            when (result) {
                is AuthResult.Success -> {
                    onSuccess(result.session)
                }
                is AuthResult.InvalidCredentials -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = UiText.Dynamic(result.message),
                        pin = ""
                    )
                }
                is AuthResult.LockedOut -> {
                    startLockoutCountdown(result.remainingSeconds)
                }
                is AuthResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = UiText.Dynamic(result.message))
                }
            }
        }
    }

    private fun startLockoutCountdown(seconds: Long) {
        countdownJob?.cancel()
        _uiState.value = _uiState.value.copy(
            lockoutSecondsRemaining = seconds,
            pin = "",
            errorMessage = UiText.Resource(R.string.locked_out_msg, listOf(seconds))
        )

        countdownJob = viewModelScope.launch {
            var current = seconds
            while (current > 0L) {
                delay(1000L)
                current--
                _uiState.value = _uiState.value.copy(lockoutSecondsRemaining = current)
            }
            _uiState.value = _uiState.value.copy(errorMessage = null)
        }
    }
}
