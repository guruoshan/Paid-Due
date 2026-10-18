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

data class LoginUiState(
    val username: String = "",
    val password: String = "",
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
    private var lockoutQueryJob: Job? = null

    fun onUsernameChanged(username: String) {
        clearLockoutState()
        _uiState.value = _uiState.value.copy(
            username = username,
            errorMessage = null,
            lockoutSecondsRemaining = 0L
        )
        checkLockout(username)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    private fun checkLockout(username: String) {
        lockoutQueryJob?.cancel()
        lockoutQueryJob = viewModelScope.launch {
            val remaining = authRepository.getLoginLockoutRemaining("ADMIN", username)
            val isLatest = _uiState.value.username == username
            if (isLatest && remaining > 0L) {
                startLockoutCountdown(remaining)
            } else if (isLatest) {
                clearLockoutState()
            }
        }
    }

    fun executeLogin(onSuccess: (ActiveSession) -> Unit) {
        val s = _uiState.value
        LoginInputValidator.error(s.username, s.password)?.let { error ->
            _uiState.value = s.copy(errorMessage = UiText.Dynamic(error))
            return
        }

        lockoutQueryJob?.cancel()
        lockoutQueryJob = null
        _uiState.value = s.copy(isExecuting = true, errorMessage = null)

        viewModelScope.launch {
            val result = authRepository.loginAdmin(s.username, s.password)
            _uiState.value = _uiState.value.copy(isExecuting = false)

            when (result) {
                is AuthResult.Success -> onSuccess(result.session)
                is AuthResult.InvalidCredentials -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = UiText.Dynamic(result.message)
                    )
                }
                is AuthResult.LockedOut -> {
                    if (result.remainingSeconds > 0L) {
                        startLockoutCountdown(result.remainingSeconds)
                    } else {
                        clearLockoutState()
                    }
                }
                is AuthResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = UiText.Dynamic(result.message)
                    )
                }
            }
        }
    }

    private fun clearLockoutState() {
        lockoutQueryJob?.cancel()
        countdownJob?.cancel()
        lockoutQueryJob = null
        countdownJob = null
        _uiState.value = _uiState.value.copy(
            lockoutSecondsRemaining = 0L,
            errorMessage = null
        )
    }

    private fun startLockoutCountdown(seconds: Long) {
        countdownJob?.cancel()
        _uiState.value = _uiState.value.copy(
            lockoutSecondsRemaining = seconds,
            errorMessage = UiText.Plural(
                R.plurals.locked_out_msg,
                seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                listOf(seconds)
            )
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
