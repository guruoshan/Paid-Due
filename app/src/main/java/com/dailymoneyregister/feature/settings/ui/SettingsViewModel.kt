package com.dailymoneyregister.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.settings.data.SettingsLanguageResult
import com.dailymoneyregister.feature.settings.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val languageManager: LanguageManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private var languageUpdateJob: Job? = null
    private var languageUpdateGeneration = 0L

    fun updateLanguage(langCode: String) {
        val session = SessionManager.currentSession.value ?: return
        val generation = ++languageUpdateGeneration
        val previousJob = languageUpdateJob

        languageUpdateJob = viewModelScope.launch {
            previousJob?.join()
            if (generation != languageUpdateGeneration) return@launch

            when (
                val result = settingsRepository.updateLanguage(
                    userId = session.user.id,
                    businessId = session.business.id,
                    requestedLanguageCode = langCode
                )
            ) {
                is SettingsLanguageResult.Success -> {
                    if (generation != languageUpdateGeneration) return@launch
                    val currentSession = SessionManager.currentSession.value ?: return@launch
                    if (
                        currentSession.user.id != session.user.id ||
                        currentSession.business.id != session.business.id
                    ) {
                        return@launch
                    }
                    SessionManager.setSession(currentSession.copy(user = result.user))
                    languageManager.setLanguage(result.user.languageCode)
                }
                is SettingsLanguageResult.UnsupportedLanguage,
                is SettingsLanguageResult.PersistenceFailure -> Unit
            }
        }
    }
}
