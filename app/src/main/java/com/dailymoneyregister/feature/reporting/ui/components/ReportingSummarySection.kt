package com.dailymoneyregister.feature.reporting.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.reporting.ui.ReportingSummaryMetric
import com.dailymoneyregister.feature.reporting.ui.ReportingSummaryUiModel

@Composable
internal fun ReportingSummarySection(
    cards: List<ReportingSummaryUiModel>
) {
    if (cards.isEmpty()) {
        return
    }

    Text(
        text =
            stringResource(
                R.string.reporting_period_summary
            ),
        style =
            MaterialTheme.typography.titleMedium,
        fontWeight =
            FontWeight.SemiBold
    )

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
        cards.forEach { card ->
            Card {
                Column(
                    modifier =
                        Modifier.padding(12.dp)
                ) {
                    Text(
                        summaryMetricLabel(
                            card.metric
                        ),
                        style =
                            MaterialTheme.typography.bodySmall
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

    if (cards.any { it.metric == ReportingSummaryMetric.CREDIT_OUTSTANDING }) {
        Text(
            text = stringResource(R.string.reporting_credit_outstanding_note),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun summaryMetricLabel(
    metric: ReportingSummaryMetric
): String =
    when (metric) {
        ReportingSummaryMetric.NET_SALES_ACTIVITY ->
            stringResource(
                R.string.reporting_net_sales_activity
            )

        ReportingSummaryMetric.CASH_IN_HAND ->
            stringResource(
                R.string.reporting_cash_in_hand
            )

        ReportingSummaryMetric.CASH_AT_BANK ->
            stringResource(
                R.string.reporting_cash_at_bank
            )

        ReportingSummaryMetric.CREDIT_SALES ->
            stringResource(
                R.string.reporting_credit_sales
            )

        ReportingSummaryMetric.CREDIT_REPAYMENTS ->
            stringResource(
                R.string.reporting_credit_repayments
            )

        ReportingSummaryMetric.CREDIT_OUTSTANDING ->
            stringResource(
                R.string.reporting_credit_outstanding
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
