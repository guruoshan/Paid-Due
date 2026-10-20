package com.dailymoneyregister.feature.reporting.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.R

@Composable
fun ReportingDashboardSection(
    onNavigateToReports: () -> Unit,
    viewModel: ReportingDashboardViewModel =
        hiltViewModel()
) {
    val state =
        viewModel.uiState
            .collectAsStateWithLifecycle()
            .value

    Column(
        modifier =
            Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text =
                stringResource(
                    R.string.reporting_today_snapshot
                ),
            style =
                MaterialTheme.typography.titleMedium,
            fontWeight =
                FontWeight.SemiBold
        )

        Text(
            text = stringResource(R.string.reporting_financial_definitions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when {
            state.isLoading -> {
                CircularProgressIndicator()
            }

            state.unavailable -> {
                Text(
                    stringResource(
                        R.string.reporting_summary_unavailable
                    )
                )

                OutlinedButton(
                    onClick =
                        viewModel::retry
                ) {
                    Text(
                        stringResource(
                            R.string.retry
                        )
                    )
                }
            }

            else -> {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(
                                rememberScrollState()
                            ),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    state.cards.forEach { card ->
                        Card(
                            onClick =
                                onNavigateToReports
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(12.dp)
                            ) {
                                Text(
                                    dashboardMetricLabel(
                                        card.metric
                                    ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )

                                Text(
                                    card.amountText,
                                    fontWeight =
                                        FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dashboardMetricLabel(
    metric: ReportingSummaryMetric
): String =
    when (metric) {
        ReportingSummaryMetric.NET_SALES_ACTIVITY ->
            stringResource(
                R.string.reporting_net_sales_activity
            )

        ReportingSummaryMetric.CASH_SALES ->
            stringResource(
                R.string.reporting_cash_sales
            )

        ReportingSummaryMetric.ONLINE_SALES ->
            stringResource(
                R.string.reporting_online_sales
            )

        ReportingSummaryMetric.CREDIT_SALES ->
            stringResource(
                R.string.reporting_credit_sales
            )

        ReportingSummaryMetric.CREDIT_REPAYMENTS ->
            stringResource(
                R.string.reporting_credit_repayments
            )

        ReportingSummaryMetric.REFUNDS ->
            stringResource(
                R.string.reporting_refunds
            )

        ReportingSummaryMetric.EXPENSES ->
            stringResource(
                R.string.reporting_expenses
            )
    }