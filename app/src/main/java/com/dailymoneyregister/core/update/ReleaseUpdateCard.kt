package com.dailymoneyregister.core.update

import androidx.compose.ui.res.stringResource
import com.dailymoneyregister.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ReleaseUpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updateInitial = stringResource(R.string.audit_update_initial)
    val updateCheckApproved = stringResource(R.string.audit_update_check_approved)
    val updateUpToDate = stringResource(R.string.audit_update_up_to_date)
    val updateApkMissing = stringResource(R.string.audit_update_apk_missing)
    val updateVersionAvailableFormat = stringResource(R.string.audit_update_version_available, "%s")
    val updateCheckFailed = stringResource(R.string.audit_update_check_failed)
    val updateOpeningInstaller = stringResource(R.string.audit_update_opening_installer)
    val updatePrepareFailed = stringResource(R.string.audit_update_prepare_failed)
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var message by remember(updateInitial) { mutableStateOf(updateInitial) }
    var result by remember { mutableStateOf<ReleaseUpdater.CheckResult?>(null) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.audit_update_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (checking || downloading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(if (checking) stringResource(R.string.audit_update_checking_releases) else stringResource(R.string.audit_update_verifying_preparing))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        checking = true
                        message = updateCheckApproved
                        scope.launch {
                            try {
                                val checked = ReleaseUpdater.check(context)
                                result = checked
                                message = when {
                                    !checked.updateAvailable -> updateUpToDate
                                    checked.downloadUrl == null -> updateApkMissing
                                    else -> String.format(Locale.getDefault(), updateVersionAvailableFormat, checked.latestVersion.toString())
                                }
                            } catch (error: Exception) {
                                message = error.message ?: updateCheckFailed
                            } finally {
                                checking = false
                            }
                        }
                    }, enabled = !checking && !downloading) {
                        Text(stringResource(R.string.audit_update_button_check))
                    }
                    if (result?.updateAvailable == true && result?.downloadUrl != null) {
                        Button(onClick = {
                            downloading = true
                            scope.launch {
                                try {
                                    ReleaseUpdater.downloadAndInstall(context, result!!)
                                    message = updateOpeningInstaller
                                } catch (error: Exception) {
                                    message = error.message ?: updatePrepareFailed
                                } finally {
                                    downloading = false
                                }
                            }
                        }, enabled = !checking && !downloading) {
                            Text(stringResource(R.string.audit_update_button_update))
                        }
                    }
                }
            }
        }
    }
}
