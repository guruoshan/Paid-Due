package com.dailymoneyregister.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val languageManager: LanguageManager,
    private val userDao: UserDao
) : ViewModel() {

    fun updateLanguage(langCode: String) {
        languageManager.setLanguage(langCode)
        val session = SessionManager.currentSession.value ?: return
        val updatedUser = session.user.copy(languageCode = langCode)
        SessionManager.setSession(session.copy(user = updatedUser))

        viewModelScope.launch {
            userDao.updateUser(updatedUser)
        }
    }
}
