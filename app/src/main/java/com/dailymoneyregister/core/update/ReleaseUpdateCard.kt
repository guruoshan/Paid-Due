package com.dailymoneyregister.core.update

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
import androidx.core.content.ContextCompat
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch

@Composable
fun ReleaseUpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Check GitHub for the latest approved version of Paid&Due.") }
    var result by remember { mutableStateOf<ReleaseUpdater.CheckResult?>(null) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "App updates",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (checking || downloading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(if (checking) "Checking GitHub releases…" else "Verifying and preparing update…")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        checking = true
                        message = "Checking for an approved release…"
                        scope.launch {
                            try {
                                val checked = ReleaseUpdater.check(context)
                                result = checked
                                message = when {
                                    !checked.updateAvailable -> "Paid&Due is up to date."
                                    checked.downloadUrl == null -> "A newer release was found, but its APK is missing."
                                    else -> "Version 1.0.${checked.latestVersion} is available."
                                }
                            } catch (error: Exception) {
                                message = error.message ?: "Could not check for updates. Please try again."
                            } finally {
                                checking = false
                            }
                        }
                    }, enabled = !checking && !downloading) {
                        Text("Check for updates")
                    }
                    if (result?.updateAvailable == true && result?.downloadUrl != null) {
                        Button(onClick = {
                            downloading = true
                            scope.launch {
                                try {
                                    ReleaseUpdater.downloadAndInstall(context, result!!)
                                    message = "Opening Android installer…"
                                } catch (error: Exception) {
                                    message = error.message ?: "Could not prepare the update."
                                } finally {
                                    downloading = false
                                }
                            }
                        }, enabled = !checking && !downloading) {
                            Text("Update")
                        }
                    }
                }
            }
        }
    }
}
