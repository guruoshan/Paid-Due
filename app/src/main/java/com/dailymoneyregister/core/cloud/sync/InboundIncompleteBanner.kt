package com.dailymoneyregister.core.cloud.sync

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager

private const val INBOUND_INCOMPLETE_WARNING =
    "Some operations from other devices are not applied yet. Totals may be incomplete."

@Composable
fun InboundIncompleteBanner() {
    val context = LocalContext.current
    val session by SessionManager.currentSession.collectAsStateWithLifecycle()
    val counts by FinancialSyncStatus.unsupportedInboundCounts.collectAsStateWithLifecycle()
    val businessId = session?.business?.id
    LaunchedEffect(businessId) {
        businessId?.let { FinancialSyncStatus.loadUnsupportedCount(context, it) }
    }
    if (session?.user?.role == UserRole.ADMIN && (counts[businessId] ?: 0) > 0) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(
                INBOUND_INCOMPLETE_WARNING,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
