package com.dailymoneyregister.feature.notifications.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.notifications.data.BusinessNotification
import com.dailymoneyregister.feature.notifications.data.NotificationsRepository
import com.dailymoneyregister.feature.notifications.data.PermissionRequestRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

data class NotificationsUiState(
    val isAdmin: Boolean = false,
    val isLoading: Boolean = false,
    val notifications: List<BusinessNotification> = emptyList(),
    val permissionRequests: List<PermissionRequestRecord> = emptyList(),
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        NotificationsUiState(
            isAdmin = SessionManager.currentSession.value?.user?.role == UserRole.ADMIN
        )
    )
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private var notificationWatchJob: Job? = null

    fun refresh() {
        if (_uiState.value.isLoading) return
        startNotificationUpdates()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, statusMessage = null)
            runCatching {
                val notifications = repository.listNotifications()
                val requests = repository.listPermissionRequests()
                notifications to requests
            }.onSuccess { (notifications, requests) ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notifications = notifications,
                    permissionRequests = requests,
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Unable to load notifications."
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startNotificationUpdates() {
        if (notificationWatchJob != null) return
        notificationWatchJob = viewModelScope.launch {
            if (_uiState.value.isAdmin) {
                runCatching {
                    repository.observeAdminNotificationInserts().collect {
                        while (_uiState.value.isLoading) delay(150)
                        refresh()
                    }
                }
            } else {
                // Custom PIN cashiers do not have a Supabase Auth JWT for Realtime.
                // Poll the durable inbox only while this screen's ViewModel is alive.
                while (true) {
                    delay(20_000)
                    if (!_uiState.value.isLoading) refresh()
                }
            }
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            runCatching { repository.markRead(id) }
                .onSuccess { refresh() }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = error.message ?: "Unable to mark notification as read."
                    )
                }
        }
    }

    fun requestPermission(permissionKey: String, note: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, statusMessage = null)
            runCatching { repository.requestPermission(permissionKey, note) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        statusMessage = "Request sent to your business Admin."
                    )
                    refresh()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to send permission request."
                    )
                }
        }
    }

    fun resolvePermissionRequest(requestId: String, approve: Boolean, additionalPermissions: Set<String>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, statusMessage = null)
            runCatching { repository.resolvePermissionRequest(requestId, approve, additionalPermissions) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        statusMessage = if (approve) "Permission request approved." else "Permission request rejected."
                    )
                    refresh()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to resolve permission request."
                    )
                }
        }
    }
}
