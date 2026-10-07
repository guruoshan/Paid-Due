package com.dailymoneyregister.feature.invite.ui

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.R
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.feature.invite.data.ActivationResult
import com.dailymoneyregister.feature.invite.data.InviteActivationRepository
import com.dailymoneyregister.feature.invite.data.InvitePreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InvitePhase { LOADING, INVALID, READY }

data class InviteActivateUiState(
    val phase: InvitePhase = InvitePhase.LOADING,
    val preview: InvitePreview? = null,
    val pin: String = "",
    val confirmPin: String = "",
    @StringRes val errorRes: Int? = null,
    val isActivating: Boolean = false
)

@HiltViewModel
class InviteActivateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: InviteActivationRepository
) : ViewModel() {

    // Held only in memory; never logged or persisted.
    private val token: String = savedStateHandle.get<String>("token").orEmpty()

    private val _uiState = MutableStateFlow(InviteActivateUiState())
    val uiState: StateFlow<InviteActivateUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val preview = if (token.isBlank()) null else repository.preview(token)
            _uiState.value = if (preview == null) {
                InviteActivateUiState(phase = InvitePhase.INVALID)
            } else {
                InviteActivateUiState(phase = InvitePhase.READY, preview = preview)
            }
        }
    }

    fun onPinChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            pin = value.filter(Char::isDigit).take(6),
            errorRes = null
        )
    }

    fun onConfirmPinChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            confirmPin = value.filter(Char::isDigit).take(6),
            errorRes = null
        )
    }

    fun activate(onActivated: () -> Unit) {
        val s = _uiState.value
        if (s.isActivating || s.phase != InvitePhase.READY) return

        if (!PinHasher.isValidPin(s.pin)) {
            _uiState.value = s.copy(errorRes = R.string.invite_pin_invalid)
            return
        }
        if (s.pin != s.confirmPin) {
            _uiState.value = s.copy(errorRes = R.string.invite_pin_mismatch)
            return
        }

        _uiState.value = s.copy(isActivating = true, errorRes = null)

        viewModelScope.launch {
            when (repository.activate(token, s.pin)) {
                is ActivationResult.Success -> onActivated()
                ActivationResult.InvalidInvite -> {
                    _uiState.value = InviteActivateUiState(phase = InvitePhase.INVALID)
                }
                ActivationResult.PhoneAlreadyRegistered -> {
                    _uiState.value = _uiState.value.copy(
                        isActivating = false,
                        errorRes = R.string.invite_phone_exists
                    )
                }
                ActivationResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isActivating = false,
                        errorRes = R.string.invite_activation_failed
                    )
                }
            }
        }
    }
}
