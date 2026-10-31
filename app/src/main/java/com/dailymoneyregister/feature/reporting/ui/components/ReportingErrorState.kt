package com.dailymoneyregister.feature.reporting.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.reporting.ui.ReportingUiError

@Composable
internal fun ReportingErrorState(
    error: ReportingUiError,
    detail: String?,
    onRetry: () -> Unit
) {
    val message =
        when (error) {
            ReportingUiError.ACCESS_DENIED ->
                stringResource(
                    R.string.reporting_access_denied
                )

            ReportingUiError.INTEGRITY ->
                stringResource(
                    R.string.reporting_integrity_error
                )

            ReportingUiError.INVALID_CUSTOM_PERIOD ->
                stringResource(
                    R.string.reporting_invalid_custom_period
                )

            ReportingUiError.LOAD_FAILED ->
                stringResource(
                    R.string.reporting_load_failed
                )
        }

    Column(
        modifier =
            Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        Text(message)
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            onClick = onRetry
        ) {
            Text(
                stringResource(
                    R.string.retry
                )
            )
        }
    }
}
