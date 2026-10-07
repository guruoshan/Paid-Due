package com.dailymoneyregister.feature.setup.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.cloud.RemoteAdminAuthRepository
import com.dailymoneyregister.core.cloud.RemoteAdminAuthResult
import com.dailymoneyregister.core.cloud.RemoteTenantBootstrapCoordinator
import com.dailymoneyregister.core.cloud.RemoteTenantCoordinationResult
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.core.ui.PhoneNumberInputValue

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
    ADMIN_PERSONAL,
    LANGUAGE_SELECTION,
    BUSINESS_PROFILE,
    COUNTRY_SELECTION,
    CURRENCY_CONFIG,
    TIMEZONE_CONFIG,
    ADMIN_CREATION,
    COMPLETE
}

data class SetupUiState(
    val currentStep: SetupStep = SetupStep.ADMIN_PERSONAL,
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
    val adminEmail: String = "",
    val adminPhone: String = "",
    val adminPhoneCountryCode: String = "OM",
    val adminPasswordPlain: String = "",
    val isExecuting: Boolean = false,
    val errorMessage: UiText? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val languageManager: LanguageManager,
    private val remoteAdminAuthRepository: RemoteAdminAuthRepository,
    private val remoteTenantBootstrapCoordinator: RemoteTenantBootstrapCoordinator
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

    fun onAdminNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(adminDisplayName = name)
    }

    fun onAdminPhoneChanged(phone: PhoneNumberInputValue) {
        val state = _uiState.value
        val previousDigits = state.adminPhone.filter { it.isDigit() }
        val newDigits = phone.rawNumber.filter { it.isDigit() }
        val shouldPrefillUsername = state.adminUsername.isBlank() || state.adminUsername == previousDigits
        _uiState.value = state.copy(
            adminPhone = phone.rawNumber,
            adminPhoneCountryCode = phone.countryCode,
            adminUsername = if (shouldPrefillUsername) newDigits else state.adminUsername
        )
    }

    fun onAdminUsernameChanged(username: String) {
        _uiState.value = _uiState.value.copy(adminUsername = username)
    }

    fun onAdminEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(
            adminEmail = email,
            errorMessage = null
        )
    }

    fun onNextFromAdminPersonal() {
        val state = _uiState.value
        if (state.adminDisplayName.isBlank() || state.adminUsername.isBlank() || state.adminPhone.isBlank()) {
            _uiState.value = state.copy(errorMessage = UiText.Resource(R.string.admin_personal_required))
            return
        }
        val normalizedPhone = runCatching {
            PhoneNumberNormalizer.normalize(state.adminPhone, state.adminPhoneCountryCode)
        }.getOrElse { error ->
            _uiState.value = state.copy(
                errorMessage = UiText.Dynamic(error.message ?: "Invalid WhatsApp / Mobile Number")
            )
            return
        }
        _uiState.value = state.copy(
            adminPhone = normalizedPhone,
            currentStep = SetupStep.BUSINESS_PROFILE,
            errorMessage = null
        )
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

    fun onAdminPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(adminPasswordPlain = password)
    }

    fun onPreviousStep() {
        val previous = when (_uiState.value.currentStep) {
            SetupStep.BUSINESS_PROFILE -> SetupStep.ADMIN_PERSONAL
            SetupStep.COUNTRY_SELECTION -> SetupStep.BUSINESS_PROFILE
            SetupStep.CURRENCY_CONFIG -> SetupStep.COUNTRY_SELECTION
            SetupStep.TIMEZONE_CONFIG -> SetupStep.CURRENCY_CONFIG
            SetupStep.ADMIN_CREATION -> SetupStep.TIMEZONE_CONFIG
            SetupStep.COMPLETE -> SetupStep.ADMIN_CREATION
            SetupStep.LANGUAGE_SELECTION,
            SetupStep.ADMIN_PERSONAL -> return
        }
        _uiState.value = _uiState.value.copy(currentStep = previous, errorMessage = null)
    }

    fun executeFinishSetup(onSuccess: () -> Unit) {
        val s = _uiState.value

        val email =
            s.adminEmail
                .trim()
                .lowercase()

        if (!EMAIL_PATTERN.matches(email)) {
            _uiState.value = s.copy(
                errorMessage = UiText.Dynamic(
                    "Enter a valid Admin email address"
                )
            )
            return
        }

        if (s.adminPasswordPlain.length < 8) {
            _uiState.value = s.copy(
                errorMessage =
                    UiText.Resource(
                        R.string.admin_password_too_short
                    )
            )
            return
        }

        val rateBps =
            TaxRateParser.parseToBps(
                s.defaultTaxRatePercent
            )

        val params =
            SetupParams(
                businessName = s.businessName,
                branchName = s.branchName,
                branchCode = s.branchCode,
                countryCode = s.countryCode,
                currencyCode = s.currencyCode,
                currencyDecimalPlaces =
                    s.currencyDecimalPlaces,
                currencySymbol = s.currencySymbol,
                timezoneId = s.timezoneId,
                taxRoundingMode = s.taxRoundingMode,
                defaultTaxRateBps = rateBps,
                adminDisplayName =
                    s.adminDisplayName,
                adminUsername =
                    s.adminUsername,
                adminPhone =
                    s.adminPhone,
                adminPasswordPlain =
                    s.adminPasswordPlain,
                languageCode =
                    s.selectedLanguage.code
            )

        _uiState.value =
            s.copy(
                adminEmail = email,
                isExecuting = true,
                errorMessage = null
            )

        viewModelScope.launch {

            val authResult =
                ensureRemoteAdminAuthenticated(
                    email = email,
                    password =
                        s.adminPasswordPlain
                )

            when (authResult) {

                is RemoteAdminAuthResult.Authenticated -> {

                    /*
                     * BOARDLOCK 3D-4
                     *
                     * A failed cloud bootstrap may happen AFTER local Setup
                     * has already committed successfully.
                     *
                     * In that case, never run executeSetup() again.
                     * Reuse the durable local Business / Branch / Admin and
                     * retry only the pending remote tenant bootstrap.
                     */
                    if (setupRepository.isSetupComplete()) {

                        resumeExistingSetup(
                            onSuccess = onSuccess
                        )

                    } else {

                        val armResult =
                            remoteTenantBootstrapCoordinator
                                .armForSetup(email)

                        if (armResult.isFailure) {

                            _uiState.value =
                                _uiState.value.copy(
                                    isExecuting = false,
                                    errorMessage =
                                        UiText.Dynamic(
                                            armResult
                                                .exceptionOrNull()
                                                ?.message
                                                ?: "Cloud setup recovery could not be prepared"
                                        )
                                )

                            return@launch
                        }

                        completeLocalSetup(
                            params = params,
                            onSuccess = onSuccess
                        )
                    }
                }

                is RemoteAdminAuthResult.EmailVerificationRequired -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(
                                    "Verification email sent to ${authResult.email}. " +
                                        "Open the email, verify the address, then return to Paid&Due " +
                                        "and tap Create Account again."
                                )
                        )
                }

                is RemoteAdminAuthResult.Failed -> {
                    _uiState.value =
                        _uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Dynamic(
                                    authResult.message
                                )
                        )
                }
            }
        }
    }

    private suspend fun ensureRemoteAdminAuthenticated(
        email: String,
        password: String
    ): RemoteAdminAuthResult {

        if (
            remoteAdminAuthRepository
                .hasAuthenticatedSession()
        ) {

            val currentEmail =
                remoteAdminAuthRepository
                    .currentEmail()

            val currentUserId =
                remoteAdminAuthRepository
                    .currentAuthUserId()

            if (
                currentEmail == email &&
                currentUserId != null
            ) {
                return RemoteAdminAuthResult.Authenticated(
                    authUserId = currentUserId,
                    email = email
                )
            }

            return RemoteAdminAuthResult.Failed(
                "A different Admin cloud session is already active. " +
                    "Close and reopen Paid&Due before creating this account."
            )
        }

        /*
         * First try sign-in.
         *
         * This is essential for the email-confirmation retry flow:
         *
         * 1. User signs up.
         * 2. Supabase requests email confirmation.
         * 3. No local business is created.
         * 4. User verifies email.
         * 5. User returns and taps Create Account again.
         * 6. This sign-in now succeeds.
         */
        val signInResult =
            remoteAdminAuthRepository.signIn(
                email = email,
                password = password
            )

        if (
            signInResult
                is RemoteAdminAuthResult.Authenticated
        ) {
            return signInResult
        }

        /*
         * Sign-in did not authenticate.
         * Attempt first-time signup.
         */
        return remoteAdminAuthRepository.signUp(
            email = email,
            password = password
        )
    }

    private suspend fun resumeExistingSetup(
        onSuccess: () -> Unit
    ) {

        when (
            val result =
                remoteTenantBootstrapCoordinator
                    .resumePendingIfNeeded()
        ) {

            is RemoteTenantCoordinationResult.Success -> {

                val setupResult =
                    com.dailymoneyregister.feature.setup.data.SetupResult(
                        businessId =
                            com.dailymoneyregister.core.model.ids.BusinessId(
                                result.businessId
                            ),
                        branchId =
                            com.dailymoneyregister.core.model.ids.BranchId(
                                result.branchId
                            ),
                        adminUserId =
                            com.dailymoneyregister.core.model.ids.UserId(
                                result.adminUserId
                            )
                    )

                setupRepository.establishAdminSession(
                    setupResult
                )

                _uiState.value =
                    _uiState.value.copy(
                        isExecuting = false,
                        currentStep = SetupStep.COMPLETE,
                        errorMessage = null
                    )

                onSuccess()
            }

            RemoteTenantCoordinationResult.NothingPending -> {

                _uiState.value =
                    _uiState.value.copy(
                        isExecuting = false,
                        errorMessage =
                            UiText.Dynamic(
                                "Local setup already exists, but no pending cloud setup was found."
                            )
                    )
            }

            is RemoteTenantCoordinationResult.Failed -> {

                _uiState.value =
                    _uiState.value.copy(
                        isExecuting = false,
                        errorMessage =
                            UiText.Dynamic(
                                result.message +
                                    " Paid&Due kept the existing local setup and did not create a duplicate."
                            )
                    )
            }
        }
    }
    private suspend fun completeLocalSetup(
        params: SetupParams,
        onSuccess: () -> Unit
    ) {

        when (
            val result =
                setupRepository.executeSetup(params)
        ) {

            is CommandResult.Success -> {

                when (
                    val cloudResult =
                        remoteTenantBootstrapCoordinator
                            .bootstrapAfterLocalSetup(
                                result.data
                            )
                ) {

                    is RemoteTenantCoordinationResult.Success -> {

                        setupRepository
                            .establishAdminSession(
                                result.data
                            )

                        _uiState.value =
                            _uiState.value.copy(
                                isExecuting = false,
                                currentStep =
                                    SetupStep.COMPLETE,
                                errorMessage = null
                            )

                        onSuccess()
                    }

                    RemoteTenantCoordinationResult.NothingPending -> {

                        _uiState.value =
                            _uiState.value.copy(
                                isExecuting = false,
                                errorMessage =
                                    UiText.Dynamic(
                                        "Cloud setup recovery state was unexpectedly missing"
                                    )
                            )
                    }

                    is RemoteTenantCoordinationResult.Failed -> {

                        /*
                         * Fail closed.
                         *
                         * Local setup is already durable, but the
                         * Admin session is NOT established until
                         * Supabase confirms the tenant bootstrap.
                         *
                         * The durable pending marker is intentionally
                         * retained for startup retry.
                         */
                        _uiState.value =
                            _uiState.value.copy(
                                isExecuting = false,
                                errorMessage =
                                    UiText.Dynamic(
                                        cloudResult.message +
                                            " Retry Create Account, or reopen Paid&Due when the connection is available."
                                    )
                            )
                    }
                }
            }

            is CommandResult.Conflict -> {

                _uiState.value =
                    _uiState.value.copy(
                        isExecuting = false,
                        errorMessage =
                            UiText.Dynamic(
                                result.message
                            )
                    )
            }

            is CommandResult.PermissionDenied -> {

                _uiState.value =
                    _uiState.value.copy(
                        isExecuting = false,
                        errorMessage =
                            UiText.Dynamic(
                                result.message
                            )
                    )
            }

            is CommandResult.Error -> {

                _uiState.value =
                    _uiState.value.copy(
                        isExecuting = false,
                        errorMessage =
                            result.exception.message
                                ?.let {
                                    UiText.Dynamic(it)
                                }
                                ?: UiText.Resource(
                                    R.string.setup_failed
                                )
                    )
            }
        }
    }

    private companion object {

        val EMAIL_PATTERN =
            Regex(
                "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
                RegexOption.IGNORE_CASE
            )
    }
}
