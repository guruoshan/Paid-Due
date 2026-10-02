package com.dailymoneyregister.feature.register.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.feature.register.data.HistoryFilter
import com.dailymoneyregister.feature.register.data.HistoryTransactionRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    currency: CurrencyConfig,
    timezoneId: String,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (HistoryFilter) -> Unit,
    onTransactionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {

    BackHandler(
        enabled = true,
        onBack = onBack
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp
                ),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                androidx.compose.ui.Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack
            ) {
                Text(
                    stringResource(
                        R.string.history_back
                    )
                )
            }

            Text(
                text =
                    stringResource(
                        R.string.history
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = 72.dp),
                style =
                    MaterialTheme.typography.headlineSmall,
                fontWeight =
                    FontWeight.Bold,
                textAlign =
                    androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        OutlinedTextField(
            value =
                state.query,
            onValueChange =
                onQueryChanged,
            modifier =
                Modifier.fillMaxWidth(),
            singleLine =
                true,
            label = {
                Text(
                    stringResource(
                        R.string.history_search_hint
                    )
                )
            }
        )

        LazyRow(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            items(
                HistoryFilter.values().toList()
            ) { filter ->

                FilterChip(
                    selected =
                        state.filter == filter,
                    onClick = {
                        onFilterChanged(filter)
                    },
                    label = {
                        Text(
                            historyFilterLabel(
                                filter
                            )
                        )
                    }
                )
            }
        }

        when {

            state.loading -> {

                Text(
                    stringResource(
                        R.string.history_loading
                    )
                )
            }

            state.error ==
                HistoryLoadError.PERMISSION_DENIED -> {

                Text(
                    text =
                        stringResource(
                            R.string.history_permission_denied
                        ),
                    color =
                        MaterialTheme.colorScheme.error
                )
            }

            state.error ==
                HistoryLoadError.LOAD_FAILED -> {

                Text(
                    text =
                        stringResource(
                            R.string.history_load_failed
                        ),
                    color =
                        MaterialTheme.colorScheme.error
                )
            }

            state.allRows.isEmpty() -> {

                Text(
                    stringResource(
                        R.string.history_empty
                    )
                )
            }

            state.visibleRows.isEmpty() -> {

                Text(
                    stringResource(
                        R.string.history_no_match
                    )
                )
            }

            else -> {

                LazyColumn(
                    modifier =
                        Modifier.fillMaxSize(),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    items(
                        items =
                            state.visibleRows,
                        key = {
                            it.transactionId
                        }
                    ) { row ->

                        HistoryTransactionCard(
                            row =
                                row,
                            currency =
                                currency,
                            timezoneId =
                                timezoneId,
                            onClick = {
                                onTransactionSelected(
                                    row.transactionId
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun historyFilterLabel(
    filter: HistoryFilter
): String =
    when (filter) {

        HistoryFilter.ALL ->
            stringResource(
                R.string.history_filter_all
            )

        HistoryFilter.DATE ->
            stringResource(
                R.string.history_filter_date
            )

        HistoryFilter.PHONE ->
            stringResource(
                R.string.history_filter_phone
            )

        HistoryFilter.NAME ->
            stringResource(
                R.string.history_filter_name
            )

        HistoryFilter.AMOUNT ->
            stringResource(
                R.string.history_filter_amount
            )
    }

@Composable
private fun HistoryTransactionCard(
    row: HistoryTransactionRow,
    currency: CurrencyConfig,
    timezoneId: String,
    onClick: () -> Unit
) {

    val financial =
        row.financial

    val timestamp =
        Instant.ofEpochMilli(
            financial.createdAt
        )
            .atZone(
                ZoneId.of(timezoneId)
            )
            .format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm",
                    Locale.getDefault()
                )
            )

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick
                )
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp)
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                Text(
                    text =
                        financial.displayRef,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Text(
                    text =
                        financial.currentAmount
                            .formatDisplay(currency),
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Text(timestamp)

            row.customerName
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    Text(it)
                }

            row.canonicalPhone
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    Text(it)
                }

            Text(
                historyPaymentLabel(
                    financial
                )
            )

            Text(
                historyLifecycleLabel(
                    financial.lifecycleStates
                )
            )
        }
    }
}

@Composable
private fun historyPaymentLabel(
    financial: FinancialTransactionView
): String {

    val categories =
        financial
            .fundingBreakdown
            .map {
                it.category
            }
            .distinct()

    if (categories.isNotEmpty()) {

        val labels =
            mutableListOf<String>()

        for (category in categories) {

            labels +=
                historyPaymentCategoryLabel(
                    category
                )
        }

        return labels.joinToString(" + ")
    }

    val current =
        financial.currentPaymentCategory

    return if (current != null) {

        historyPaymentCategoryLabel(
            current
        )

    } else {

        stringResource(
            R.string.history_payment_not_available
        )
    }
}

@Composable
private fun historyPaymentCategoryLabel(
    category: PaymentCategory
): String =
    when (category) {

        PaymentCategory.CASH ->
            stringResource(
                R.string.tx_payment_cash
            )

        PaymentCategory.ONLINE ->
            stringResource(
                R.string.tx_payment_online
            )

        PaymentCategory.CREDIT ->
            stringResource(
                R.string.tx_payment_credit
            )

        PaymentCategory.COUPON ->
            stringResource(
                R.string.tx_payment_coupon
            )
    }

@Composable
private fun historyLifecycleLabel(
    states:
        Set<FinancialLifecycleState>
): String {

    if (states.isEmpty()) {

        return stringResource(
            R.string.tx_details_active
        )
    }

    val labels =
        mutableListOf<String>()

    for (
        state in
        states.sortedBy { it.ordinal }
    ) {

        val label =
            when (state) {

                FinancialLifecycleState.CORRECTED ->
                    stringResource(
                        R.string.recent_status_corrected
                    )

                FinancialLifecycleState
                    .PAYMENT_METHOD_CHANGED ->
                    stringResource(
                        R.string.recent_status_payment_method_changed
                    )

                FinancialLifecycleState
                    .PARTIALLY_REFUNDED ->
                    stringResource(
                        R.string.recent_status_partially_refunded
                    )

                FinancialLifecycleState
                    .FULLY_REFUNDED ->
                    stringResource(
                        R.string.recent_status_fully_refunded
                    )

                FinancialLifecycleState.VOIDED ->
                    stringResource(
                        R.string.recent_status_voided
                    )
            }

        labels += label
    }

    return labels.joinToString(" • ")
}