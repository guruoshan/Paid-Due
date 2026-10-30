package com.dailymoneyregister.feature.register.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dailymoneyregister.R

@Composable
internal fun DayClosingCashTotals(
    cashSalesDisplay: String,
    cashCouponFundingDisplay: String,
    cashCreditDisplay: String,
    cashRefundDisplay: String,
    cashExpenseDisplay: String,
    expectedCashDisplay: String,
    onlineExpenseDisplay: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.dmr21_day_cash_sales, cashSalesDisplay))
        Text(stringResource(R.string.dmr21_day_cash_coupon_funding, cashCouponFundingDisplay))
        Text(stringResource(R.string.dmr21_day_cash_credit_payments, cashCreditDisplay))
        Text(stringResource(R.string.dmr21_day_cash_refunds, cashRefundDisplay))
        Text(stringResource(R.string.dmr21_day_cash_expenses, cashExpenseDisplay))
        Text(
            text = stringResource(R.string.dmr21_day_expected_cash, expectedCashDisplay),
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.dmr21_day_online_expenses, onlineExpenseDisplay),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun DayClosingReconciliationSummary(
    expectedDisplay: String,
    countedDisplay: String,
    differenceDisplay: String,
    statusDisplay: String
) {
    HorizontalDivider()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.dmr22_expected_cash, expectedDisplay))
        Text(stringResource(R.string.dmr22_cash_counted, countedDisplay))
        Text(
            text = stringResource(R.string.dmr22_difference, differenceDisplay),
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.dmr22_status, statusDisplay),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun DayClosingResultDetails(
    shiftOwnerDisplayName: String,
    closedByDisplayName: String,
    closedAtDisplay: String
) {
    HorizontalDivider()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.dmr22_shift_owner, shiftOwnerDisplayName))
        Text(stringResource(R.string.dmr22_closed_by, closedByDisplayName))
        Text(stringResource(R.string.dmr22_closed_at, closedAtDisplay))
    }
}

@Composable
internal fun DayClosingConfirmActions(
    hasReport: Boolean,
    hasResult: Boolean,
    isReview: Boolean,
    isExecuting: Boolean,
    onShareSummary: (String) -> Unit,
    onDone: () -> Unit,
    onConfirmClose: () -> Unit,
    onReview: () -> Unit
) {
    if (hasReport) {
        if (hasResult) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dayCloseSummaryTitle = stringResource(R.string.day_close_summary_title)
                OutlinedButton(onClick = { onShareSummary(dayCloseSummaryTitle) }) {
                    Text(stringResource(R.string.share_day_summary))
                }
                Button(onClick = onDone) {
                    Text(stringResource(R.string.done))
                }
            }
        } else if (isReview) {
            Button(
                onClick = onConfirmClose,
                enabled = !isExecuting
            ) {
                Text(
                    if (isExecuting) {
                        stringResource(R.string.dmr20_closing)
                    } else {
                        stringResource(R.string.dmr20_confirm_close)
                    }
                )
            }
        } else {
            Button(
                onClick = onReview,
                enabled = !isExecuting
            ) {
                Text(stringResource(R.string.dmr20_review))
            }
        }
    }
}

@Composable
internal fun DayClosingDismissAction(
    hasResult: Boolean,
    isReview: Boolean,
    isExecuting: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit
) {
    if (hasResult) {
        // Final result has one explicit Done action.
    } else if (isReview) {
        TextButton(
            onClick = onBack,
            enabled = !isExecuting
        ) {
            Text(stringResource(R.string.dmr20_back))
        }
    } else {
        TextButton(
            onClick = onCancel,
            enabled = !isExecuting
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}
