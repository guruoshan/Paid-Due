package com.dailymoneyregister.feature.settings.ui

import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.update.ReleaseUpdateCard
import com.dailymoneyregister.feature.customers.data.CustomerRepository

import com.dailymoneyregister.core.ui.nativeLabel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.i18n.LanguageRegistry
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToHelpFaq: () -> Unit,
    onLogout: () -> Unit
) {
    val currentLang by viewModel.languageManager.currentLanguage.collectAsState()
    val session by SessionManager.currentSession.collectAsState()
    val merchantQr by viewModel.merchantQr.collectAsState()
    val qrError by viewModel.qrError.collectAsState()
    val qrSaving by viewModel.qrSaving.collectAsState()
    val isBusinessDetailsFormOpen by viewModel.isBusinessDetailsFormOpen.collectAsState()
    val businessDetailsSaving by viewModel.businessDetailsSaving.collectAsState()
    val businessDetailsError by viewModel.businessDetailsError.collectAsState()

    var isLanguagePickerOpen by remember { mutableStateOf(false) }

    // SETTINGS_PROFESSIONAL_UX_R2
    // Presentation-only state. Merchant QR truth remains in SettingsViewModel/repository.
    var isOnlinePaymentSettingsOpen by remember { mutableStateOf(false) }

    var displayLabel by remember(merchantQr?.config?.id) { mutableStateOf(merchantQr?.config?.displayLabel ?: "") }
    var bankAppName by remember(merchantQr?.config?.id) { mutableStateOf(merchantQr?.config?.bankAppName ?: "") }
    var accountLabel by remember(merchantQr?.config?.id) { mutableStateOf(merchantQr?.config?.accountLabel ?: "") }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        selectedUri = uri.toString()
        displayLabel = displayLabel.ifBlank { "Merchant QR" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Business Profile Summary
            session?.let { s ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.business_profile),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(stringResource(R.string.profile_business, s.business.name), fontSize = 15.sp)
                        Text(stringResource(R.string.profile_branch, s.branch.name, s.branch.code), fontSize = 15.sp)
                        Text(stringResource(R.string.profile_currency, s.branch.currencyCode, s.branch.currencyDecimalPlaces.toString()), fontSize = 15.sp)
                        Text(stringResource(R.string.profile_timezone, s.branch.timezoneId), fontSize = 15.sp)
                        Text(stringResource(R.string.profile_user, s.user.displayName, stringResource(R.string.role_admin)), fontSize = 15.sp)
                    }
                }
            }

            if (session?.user?.role == com.dailymoneyregister.core.model.enums.UserRole.ADMIN) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.openBusinessDetailsForm() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.biz_details_title),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = stringResource(R.string.biz_details_entry_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isOnlinePaymentSettingsOpen = true
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.online_payment_settings),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = stringResource(R.string.online_payment_settings_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (session != null && CustomerRepository.canViewCustomers()) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigateToCustomers() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.customers_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(stringResource(R.string.customers_entry_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Language Selector Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isLanguagePickerOpen = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.change_language), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(stringResource(currentLang.nativeLabel), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    }
                    Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToHelpFaq() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.help_faq_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(stringResource(R.string.help_faq_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            ReleaseUpdateCard()

            Spacer(modifier = Modifier.weight(1f))

            // Logout Button
            OutlinedButton(
                onClick = {
                    onLogout()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.logout), fontSize = 16.sp)
            }
        }

        if (isOnlinePaymentSettingsOpen) {
            AlertDialog(
                onDismissRequest = {
                    if (!qrSaving) {
                        isOnlinePaymentSettingsOpen = false
                    }
                },
                title = {
                    Text(
                        text = stringResource(
                            R.string.online_payment_settings
                        )
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.online_payment_settings_desc
                            ),
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            style =
                                MaterialTheme.typography.bodyMedium
                        )

                        merchantQr?.let { selected ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text =
                                            selected.config.displayLabel
                                                .ifBlank {
                                                    "Merchant QR"
                                                },
                                        fontWeight =
                                            FontWeight.SemiBold
                                    )

                                    MerchantQrImage(
                                        path =
                                            selected.media.localReference,
                                        contentDescription =
                                            stringResource(
                                                R.string.merchant_qr_preview
                                            ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                picker.launch(
                                    arrayOf("image/*")
                                )
                            },
                            enabled = !qrSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (merchantQr == null) {
                                        R.string.choose_merchant_qr
                                    } else {
                                        R.string.replace_merchant_qr
                                    }
                                )
                            )
                        }

                        OutlinedTextField(
                            value = displayLabel,
                            onValueChange = {
                                displayLabel = it
                            },
                            enabled = !qrSaving,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.qr_display_label
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = bankAppName,
                            onValueChange = {
                                bankAppName = it
                            },
                            enabled = !qrSaving,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.qr_bank_app_label
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = accountLabel,
                            onValueChange = {
                                accountLabel = it
                            },
                            enabled = !qrSaving,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.qr_account_label
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        qrError?.let { error ->
                            Text(
                                text = error,
                                color =
                                    MaterialTheme.colorScheme.error,
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            selectedUri?.let { uri ->
                                viewModel.saveMerchantQr(
                                    uri,
                                    displayLabel,
                                    bankAppName,
                                    accountLabel
                                )
                            }
                        },
                        enabled =
                            selectedUri != null &&
                                !qrSaving
                    ) {
                        Text(
                            stringResource(
                                R.string.save_merchant_qr
                            )
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            isOnlinePaymentSettingsOpen = false
                        },
                        enabled = !qrSaving
                    ) {
                        Text(
                            stringResource(R.string.close)
                        )
                    }
                }
            )
        }

        if (isBusinessDetailsFormOpen) {
            session?.let { s ->
                BusinessDetailsDialog(
                    business = s.business,
                    branch = s.branch,
                    isSaving = businessDetailsSaving,
                    error = businessDetailsError,
                    onSave = { name, address, phone, taxId ->
                        viewModel.saveBusinessDetails(name, address, phone, taxId)
                    },
                    onDismiss = { viewModel.dismissBusinessDetailsForm() }
                )
            }
        }

        if (isLanguagePickerOpen) {
            AlertDialog(
                onDismissRequest = { isLanguagePickerOpen = false },
                title = { Text(stringResource(R.string.choose_language)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LanguageRegistry.allLanguages.forEach { lang ->
                            val isSelected = lang.code == currentLang.code
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateLanguage(lang.code)
                                        isLanguagePickerOpen = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(lang.nativeLabel), fontSize = 17.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { isLanguagePickerOpen = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }
    }
}

