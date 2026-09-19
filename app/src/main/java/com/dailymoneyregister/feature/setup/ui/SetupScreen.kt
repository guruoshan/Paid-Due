package com.dailymoneyregister.feature.setup.ui

import com.dailymoneyregister.core.ui.englishLabel

import com.dailymoneyregister.core.ui.nativeLabel

import com.dailymoneyregister.core.ui.asString

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.i18n.LanguageRegistry
import com.dailymoneyregister.core.i18n.SupportedLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onSetupCompleted: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            state.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(
                        text = error.asString(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            when (state.currentStep) {
                SetupStep.LANGUAGE_SELECTION -> {
                    LanguageStepContent(
                        selectedLanguage = state.selectedLanguage,
                        onSelectLanguage = { viewModel.onLanguageSelected(it) },
                        onContinue = { viewModel.onNextFromLanguage() }
                    )
                }
                SetupStep.BUSINESS_PROFILE -> {
                    BusinessProfileStepContent(
                        businessName = state.businessName,
                        branchName = state.branchName,
                        branchCode = state.branchCode,
                        onChanged = { b, br, code -> viewModel.onBusinessDetailsChanged(b, br, code) },
                        onContinue = { viewModel.onNextFromBusiness() }
                    )
                }
                SetupStep.COUNTRY_SELECTION -> {
                    CountryStepContent(
                        countryCode = state.countryCode,
                        onSelectCountry = { viewModel.onCountrySelected(it) },
                        onContinue = { viewModel.onNextFromCountry() }
                    )
                }
                SetupStep.CURRENCY_CONFIG -> {
                    CurrencyStepContent(
                        currencyCode = state.currencyCode,
                        onSelectCurrency = { viewModel.onCurrencySelected(it) },
                        onContinue = { viewModel.onNextFromCurrency() }
                    )
                }
                SetupStep.TIMEZONE_CONFIG -> {
                    TimezoneStepContent(
                        timezoneId = state.timezoneId,
                        onSelectTimezone = { viewModel.onTimezoneSelected(it) },
                        onContinue = { viewModel.onNextFromTimezone() }
                    )
                }
                SetupStep.ADMIN_CREATION -> {
                    AdminCreationStepContent(
                        name = state.adminDisplayName,
                        phone = state.adminPhone,
                        password = state.adminPasswordPlain,
                        isExecuting = state.isExecuting,
                        onChanged = { n, u, p -> viewModel.onAdminDetailsChanged(n, u, p) },
                        onFinish = { viewModel.executeFinishSetup(onSetupCompleted) }
                    )
                }
                SetupStep.COMPLETE -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.setup_complete), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageStepContent(
    selectedLanguage: SupportedLanguage,
    onSelectLanguage: (SupportedLanguage) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        text = stringResource(R.string.choose_language),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    LanguageRegistry.allLanguages.forEach { lang ->
        val isSelected = lang.code == selectedLanguage.code
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onSelectLanguage(lang) },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = stringResource(lang.nativeLabel), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = stringResource(lang.englishLabel), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(stringResource(R.string.continue_btn), fontSize = 16.sp)
    }
}

@Composable
private fun BusinessProfileStepContent(
    businessName: String,
    branchName: String,
    branchCode: String,
    onChanged: (String, String, String) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        text = stringResource(R.string.business_profile),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    OutlinedTextField(
        value = businessName,
        onValueChange = { onChanged(it, branchName, branchCode) },
        label = { Text(stringResource(R.string.business_name)) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    )

    OutlinedTextField(
        value = branchName,
        onValueChange = { onChanged(businessName, it, branchCode) },
        label = { Text(stringResource(R.string.branch_name)) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    )

    OutlinedTextField(
        value = branchCode,
        onValueChange = { onChanged(businessName, branchName, it) },
        label = { Text(stringResource(R.string.branch_code)) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    )

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(stringResource(R.string.continue_btn), fontSize = 16.sp)
    }
}

@Composable
private fun CountryStepContent(
    countryCode: String,
    onSelectCountry: (String) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        text = stringResource(R.string.select_country),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    val countries = listOf(
        Pair("OM", stringResource(R.string.country_oman)),
        Pair("IN", stringResource(R.string.country_india)),
        Pair("AE", stringResource(R.string.country_uae)),
        Pair("SA", stringResource(R.string.country_saudi_arabia)),
        Pair("BD", stringResource(R.string.country_bangladesh)),
        Pair("US", stringResource(R.string.country_us))
    )

    countries.forEach { (code, label) ->
        val isSelected = code == countryCode
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onSelectCountry(code) },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(stringResource(R.string.continue_btn), fontSize = 16.sp)
    }
}

@Composable
private fun CurrencyStepContent(
    currencyCode: String,
    onSelectCurrency: (String) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        text = stringResource(R.string.confirm_currency),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    val currencies = listOf(
        Pair("OMR", stringResource(R.string.currency_omr)),
        Pair("INR", stringResource(R.string.currency_inr)),
        Pair("AED", stringResource(R.string.currency_aed)),
        Pair("SAR", stringResource(R.string.currency_sar)),
        Pair("BDT", stringResource(R.string.currency_bdt)),
        Pair("USD", stringResource(R.string.currency_usd))
    )

    currencies.forEach { (code, label) ->
        val isSelected = code == currencyCode
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onSelectCurrency(code) },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(stringResource(R.string.continue_btn), fontSize = 16.sp)
    }
}

@Composable
private fun TimezoneStepContent(
    timezoneId: String,
    onSelectTimezone: (String) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        text = stringResource(R.string.confirm_timezone),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    val timezones = listOf(
        Pair("Asia/Muscat", stringResource(R.string.timezone_muscat)),
        Pair("Asia/Kolkata", stringResource(R.string.timezone_kolkata)),
        Pair("Asia/Dubai", stringResource(R.string.timezone_dubai)),
        Pair("Asia/Riyadh", stringResource(R.string.timezone_riyadh)),
        Pair("Asia/Dhaka", stringResource(R.string.timezone_dhaka)),
        Pair("America/New_York", stringResource(R.string.timezone_new_york)),
        Pair("UTC", stringResource(R.string.timezone_utc))
    )

    timezones.forEach { (tzId, label) ->
        val isSelected = tzId == timezoneId
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onSelectTimezone(tzId) },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(stringResource(R.string.continue_btn), fontSize = 16.sp)
    }
}

@Composable
private fun AdminCreationStepContent(
    name: String,
    phone: String,
    password: String,
    isExecuting: Boolean,
    onChanged: (String, String, String) -> Unit,
    onFinish: () -> Unit
) {
    Text(
        text = stringResource(R.string.create_admin_account),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    OutlinedTextField(
        value = name,
        onValueChange = { onChanged(it, phone, password) },
        label = { Text(stringResource(R.string.admin_name)) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    )

    OutlinedTextField(
        value = phone,
        onValueChange = { onChanged(name, it, password) },
        label = { Text(stringResource(R.string.whatsapp_mobile_number)) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    )

    OutlinedTextField(
        value = password,
        onValueChange = { onChanged(name, phone, it) },
        label = { Text(stringResource(R.string.admin_password)) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    )

    Button(
        onClick = onFinish,
        enabled = !isExecuting,
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        if (isExecuting) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
        } else {
            Text(stringResource(R.string.create_account), fontSize = 16.sp)
        }
    }
}
