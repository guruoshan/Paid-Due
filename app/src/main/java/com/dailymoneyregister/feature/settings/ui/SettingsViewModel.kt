package com.dailymoneyregister.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.settings.data.SettingsLanguageResult
import com.dailymoneyregister.feature.settings.data.SettingsRepository
import com.dailymoneyregister.feature.settings.data.BusinessDetailsInput
import com.dailymoneyregister.feature.settings.data.SettingsBusinessDetailsResult
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.feature.settings.data.MerchantQrRepository
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val languageManager: LanguageManager,
    private val settingsRepository: SettingsRepository,
    private val merchantQrRepository: MerchantQrRepository
) : ViewModel() {

    private val _merchantQr = kotlinx.coroutines.flow.MutableStateFlow<MerchantQrSelection?>(null)
    val merchantQr: kotlinx.coroutines.flow.StateFlow<MerchantQrSelection?> = _merchantQr
    private val _qrError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val qrError: kotlinx.coroutines.flow.StateFlow<String?> = _qrError
    private val _qrSaving = kotlinx.coroutines.flow.MutableStateFlow(false)
    val qrSaving: kotlinx.coroutines.flow.StateFlow<Boolean> = _qrSaving

    private var initialMerchantQrLoadJob: Job? = null

    init {
        initialMerchantQrLoadJob = viewModelScope.launch { loadMerchantQr() }
    }

    /** Test lifecycle hook: cancel and await the startup QR read before its database closes. */
    internal suspend fun cancelInitialMerchantQrLoadAndJoin() {
        initialMerchantQrLoadJob?.cancelAndJoin()
    }

    suspend fun loadMerchantQr() {
        val branch = SessionManager.currentSession.value?.branch ?: return
        _merchantQr.value = merchantQrRepository.getActive(branch.id)
    }

    fun saveMerchantQr(sourceUri: String, displayLabel: String, bankAppName: String, accountLabel: String) {
        val session = SessionManager.currentSession.value ?: return
        if (session.user.role != com.dailymoneyregister.core.model.enums.UserRole.ADMIN) {
            _qrError.value = "Only an administrator can manage merchant QR settings."
            return
        }
        viewModelScope.launch {
            _qrSaving.value = true
            _qrError.value = null
            try {
                _merchantQr.value = merchantQrRepository.save(
                    session,
                    sourceUri,
                    displayLabel,
                    bankAppName,
                    accountLabel
                )
            } catch (error: Exception) {
                _qrError.value = error.message ?: "Could not save merchant QR."
            } finally {
                _qrSaving.value = false
            }
        }
    }

    fun clearQrError() { _qrError.value = null }

    // ------------------------------------------------------------------
    // Business details
    // ------------------------------------------------------------------

    private val _isBusinessDetailsFormOpen = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isBusinessDetailsFormOpen: kotlinx.coroutines.flow.StateFlow<Boolean> = _isBusinessDetailsFormOpen

    private val _businessDetailsSaving = kotlinx.coroutines.flow.MutableStateFlow(false)
    val businessDetailsSaving: kotlinx.coroutines.flow.StateFlow<Boolean> = _businessDetailsSaving

    private val _businessDetailsError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val businessDetailsError: kotlinx.coroutines.flow.StateFlow<String?> = _businessDetailsError

    fun canEditBusinessDetails(): Boolean =
        SessionManager.currentSession.value?.user?.role == UserRole.ADMIN

    fun openBusinessDetailsForm() {
        if (!canEditBusinessDetails()) return
        _businessDetailsError.value = null
        _isBusinessDetailsFormOpen.value = true
    }

    fun dismissBusinessDetailsForm() {
        if (_businessDetailsSaving.value) return
        _isBusinessDetailsFormOpen.value = false
        _businessDetailsError.value = null
    }

    fun saveBusinessDetails(
        businessName: String,
        branchAddress: String,
        branchPhone: String,
        branchTaxRegistrationNumber: String
    ) {
        if (_businessDetailsSaving.value) return
        val session = SessionManager.currentSession.value ?: return
        if (session.user.role != UserRole.ADMIN) {
            _businessDetailsError.value = "Only an administrator can edit business details."
            return
        }

        viewModelScope.launch {
            _businessDetailsSaving.value = true
            _businessDetailsError.value = null
            try {
                when (
                    val result = settingsRepository.updateBusinessDetails(
                        businessId = session.business.id,
                        branchId = session.branch.id,
                        input = BusinessDetailsInput(
                            businessName = businessName,
                            branchAddress = branchAddress,
                            branchPhone = branchPhone,
                            branchTaxRegistrationNumber = branchTaxRegistrationNumber
                        )
                    )
                ) {
                    is SettingsBusinessDetailsResult.Success -> {
                        _isBusinessDetailsFormOpen.value = false
                        refreshSessionBusinessAndBranch(session.business.id, session.branch.id)
                    }
                    is SettingsBusinessDetailsResult.Invalid ->
                        _businessDetailsError.value = result.message
                    is SettingsBusinessDetailsResult.PersistenceFailure ->
                        _businessDetailsError.value = result.message
                }
            } catch (error: Exception) {
                _businessDetailsError.value = error.message ?: "Could not save business details."
            } finally {
                _businessDetailsSaving.value = false
            }
        }
    }

    /** Re-reads business/branch from the DB and refreshes the active session so the new
     *  details are used immediately (e.g. by the next invoice) without requiring re-login. */
    private suspend fun refreshSessionBusinessAndBranch(businessId: String, branchId: String) {
        val current = SessionManager.currentSession.value ?: return
        if (current.business.id != businessId || current.branch.id != branchId) return
        val (business, branch) =
            settingsRepository.getBusinessAndBranch(businessId, branchId) ?: return
        SessionManager.setSession(current.copy(business = business, branch = branch))
    }

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
