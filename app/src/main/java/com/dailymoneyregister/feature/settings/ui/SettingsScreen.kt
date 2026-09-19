package com.dailymoneyregister.feature.settings.ui

import com.dailymoneyregister.core.security.SessionManager

import com.dailymoneyregister.core.ui.nativeLabel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.i18n.LanguageRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit
) {
    val currentLang by viewModel.languageManager.currentLanguage.collectAsState()
    val session by SessionManager.currentSession.collectAsState()

    var isLanguagePickerOpen by remember { mutableStateOf(false) }

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
                .padding(16.dp),
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
                        Text(stringResource(R.string.profile_user, s.user.displayName, stringResource(if (s.user.role == com.dailymoneyregister.core.model.enums.UserRole.ADMIN) R.string.role_admin else R.string.role_cashier)), fontSize = 15.sp)
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
