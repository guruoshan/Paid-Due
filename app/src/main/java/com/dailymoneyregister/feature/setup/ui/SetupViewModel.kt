package com.dailymoneyregister.feature.setup.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.UiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.i18n.LanguageRegistry
import com.dailymoneyregister.core.i18n.SupportedLanguage
import com.dailymoneyregister.core.model.currency.CurrencyRegistry
import com.dailymoneyregister.core.model.tax.TaxRateParser
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep {
    LANGUAGE_SELECTION,
    BUSINESS_PROFILE,
    COUNTRY_SELECTION,
    CURRENCY_CONFIG,
    TIMEZONE_CONFIG,
    ADMIN_CREATION,
    COMPLETE
}

data class SetupUiState(
    val currentStep: SetupStep = SetupStep.LANGUAGE_SELECTION,
    val selectedLanguage: SupportedLanguage = LanguageRegistry.ENGLISH,
    val businessName: String = "",
    val branchName: String = "Main Branch",
    val branchCode: String = "MAIN",
    val countryCode: String = "OM",
    val currencyCode: String = "OMR",
    val currencyDecimalPlaces: Int = 3,
    val currencySymbol: String = "OMR",
    val timezoneId: String = "Asia/Muscat",
    val taxRoundingMode: TaxRoundingMode = TaxRoundingMode.HALF_UP,
    val defaultTaxRatePercent: String = "5.0",
    val adminDisplayName: String = "",
    val adminUsername: String = "",
    val adminPhone: String = "",
    val adminPasswordPlain: String = "",
    val isExecuting: Boolean = false,
    val errorMessage: UiText? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val languageManager: LanguageManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState(selectedLanguage = languageManager.currentLanguage.value))
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onLanguageSelected(lang: SupportedLanguage) {
        languageManager.setLanguage(lang.code)
        _uiState.value = _uiState.value.copy(selectedLanguage = lang)
    }

    fun onNextFromLanguage() {
        _uiState.value = _uiState.value.copy(currentStep = SetupStep.BUSINESS_PROFILE)
    }

    fun onBusinessDetailsChanged(businessName: String, branchName: String, branchCode: String) {
        _uiState.value = _uiState.value.copy(
            businessName = businessName,
            branchName = branchName,
            branchCode = branchCode.uppercase()
        )
    }

    fun onNextFromBusiness() {
        if (_uiState.value.businessName.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = UiText.Resource(R.string.business_name_required))
            return
        }
        _uiState.value = _uiState.value.copy(
            currentStep = SetupStep.COUNTRY_SELECTION,
            errorMessage = null
        )
    }

    fun onCountrySelected(countryCode: String) {
        val (suggestedCurr, suggestedTz) = when (countryCode) {
            "IN" -> Pair("INR", "Asia/Kolkata")
            "AE" -> Pair("AED", "Asia/Dubai")
            "SA" -> Pair("SAR", "Asia/Riyadh")
            "BD" -> Pair("BDT", "Asia/Dhaka")
            "US" -> Pair("USD", "America/New_York")
            else -> Pair("OMR", "Asia/Muscat") // OM
        }
        val currConfig = CurrencyRegistry.findByCode(suggestedCurr)

        _uiState.value = _uiState.value.copy(
            countryCode = countryCode,
            currencyCode = currConfig.code,
            currencyDecimalPlaces = currConfig.decimalPlaces,
            currencySymbol = currConfig.symbol,
            timezoneId = suggestedTz
        )
    }

    fun onNextFromCountry() {
        _uiState.value = _uiState.value.copy(
            currentStep = SetupStep.CURRENCY_CONFIG,
            errorMessage = null
        )
    }

    fun onCurrencySelected(currencyCode: String) {
        val currConfig = CurrencyRegistry.findByCode(currencyCode)
        _uiState.value = _uiState.value.copy(
            currencyCode = currConfig.code,
            currencyDecimalPlaces = currConfig.decimalPlaces,
            currencySymbol = currConfig.symbol
        )
    }

    fun onNextFromCurrency() {
        _uiState.value = _uiState.value.copy(
            currentStep = SetupStep.TIMEZONE_CONFIG,
            errorMessage = null
        )
    }

    fun onTimezoneSelected(timezoneId: String) {
        _uiState.value = _uiState.value.copy(timezoneId = timezoneId)
    }

    fun onNextFromTimezone() {
        _uiState.value = _uiState.value.copy(
            currentStep = SetupStep.ADMIN_CREATION,
            errorMessage = null
        )
    }

    fun onAdminDetailsChanged(name: String, phone: String, password: String) {
        _uiState.value = _uiState.value.copy(
            adminDisplayName = name,
            adminUsername = phone.trim(),
            adminPhone = phone.trim(),
            adminPasswordPlain = password
        )
    }

    fun executeFinishSetup(onSuccess: () -> Unit) {
        val s = _uiState.value
        if (s.adminDisplayName.isBlank() || s.adminPhone.isBlank() || s.adminPasswordPlain.length < 8) {
            _uiState.value = s.copy(errorMessage = UiText.Resource(R.string.admin_details_required))
            return
        }
        runCatching { PhoneNumberNormalizer.normalize(s.adminPhone, s.countryCode) }
            .onFailure { error ->
                _uiState.value = s.copy(errorMessage = UiText.Dynamic(error.message ?: "Invalid WhatsApp / Mobile Number"))
            }
            .onFailure { return }

        val rateBps = TaxRateParser.parseToBps(s.defaultTaxRatePercent)

        val params = SetupParams(
            businessName = s.businessName,
            branchName = s.branchName,
            branchCode = s.branchCode,
            countryCode = s.countryCode,
            currencyCode = s.currencyCode,
            currencyDecimalPlaces = s.currencyDecimalPlaces,
            currencySymbol = s.currencySymbol,
            timezoneId = s.timezoneId,
            taxRoundingMode = s.taxRoundingMode,
            defaultTaxRateBps = rateBps,
            adminDisplayName = s.adminDisplayName,
            adminUsername = s.adminUsername,
            adminPhone = s.adminPhone,
            adminPasswordPlain = s.adminPasswordPlain,
            languageCode = s.selectedLanguage.code
        )

        _uiState.value = s.copy(isExecuting = true, errorMessage = null)

        viewModelScope.launch {
            when (val result = setupRepository.executeSetup(params)) {
                is CommandResult.Success -> {
                    setupRepository.establishAdminSession(result.data)
                    _uiState.value = _uiState.value.copy(isExecuting = false, currentStep = SetupStep.COMPLETE)
                    onSuccess()
                }
                is CommandResult.Conflict -> {
                    _uiState.value = _uiState.value.copy(isExecuting = false, errorMessage = UiText.Dynamic(result.message))
                }
                is CommandResult.PermissionDenied -> {
                    _uiState.value = _uiState.value.copy(isExecuting = false, errorMessage = UiText.Dynamic(result.message))
                }
                is CommandResult.Error -> {
                    _uiState.value = _uiState.value.copy(isExecuting = false, errorMessage = result.exception.message?.let { UiText.Dynamic(it) } ?: UiText.Resource(R.string.setup_failed))
                }
            }
        }
    }
}
