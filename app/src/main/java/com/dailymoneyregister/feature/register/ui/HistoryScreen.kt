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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.cloud.sync.InboundIncompleteBanner
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.feature.register.data.HistoryFilter
import com.dailymoneyregister.feature.register.data.HistoryActivityRow
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
    onExpenseSelected: (String) -> Unit = {},
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

        InboundIncompleteBanner()

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
                            it.activityKey
                        }
                    ) { row ->

                        when (row) {

                            is HistoryActivityRow.Transaction -> {

                                HistoryTransactionCard(
                                    row =
                                        row.row,
                                    currency =
                                        currency,
                                    timezoneId =
                                        timezoneId,
                                    onClick = {
                                        onTransactionSelected(
                                            row.sourceId
                                        )
                                    }
                                )
                            }

                            is HistoryActivityRow.Expense -> {

                                HistoryExpenseCard(
                                    row =
                                        row,
                                    currency =
                                        currency,
                                    timezoneId =
                                        timezoneId,
                                    onClick = {
                                        onExpenseSelected(
                                            row.sourceId
                                        )
                                    }
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
private fun HistoryExpenseCard(
    row: HistoryActivityRow.Expense,
    currency: CurrencyConfig,
    timezoneId: String,
    onClick: () -> Unit
) {

    val expense =
        row.expense

    val timestamp =
        Instant.ofEpochMilli(
            expense.occurredAt
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

    val payment =
        historyPaymentCategoryLabel(
            expense.paymentCategory
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

            Surface(
                color = Color(0xFFD32F2F).copy(alpha = 0.12f),
                contentColor = Color(0xFFD32F2F),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "EXPENSE",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                text = "- ${com.dailymoneyregister.core.model.money.MoneyMinorUnits(kotlin.math.abs(expense.signedEffect.amount)).formatDisplay(currency)}",
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFD32F2F),
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Ref: ${expense.displayReference}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Time: $timestamp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Status: RECORDED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            expense.payeeName
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { name ->

                    Text(name)
                }

            expense.payeePhone
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { phone ->

                    Text(phone)
                }

            Text(
                text =
                    expense.category
                        .replace(
                            "_",
                            " "
                        )
                        .lowercase()
                        .replaceFirstChar {
                            if (it.isLowerCase()) {
                                it.titlecase(
                                    Locale.getDefault()
                                )
                            } else {
                                it.toString()
                            }
                        }
            )

            Text(payment)

            expense.note
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { note ->

                    Text(
                        text = note,
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
        }
    }
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

            val transactionTypeLabel = when (financial.financialKind) {
                com.dailymoneyregister.core.model.financial.FinancialTransactionKind.MERCHANDISE_SALE -> "SALE"
                com.dailymoneyregister.core.model.financial.FinancialTransactionKind.COUPON_SALE -> "COUPON SALE"
                com.dailymoneyregister.core.model.financial.FinancialTransactionKind.COUPON_TOP_UP -> "COUPON TOP-UP"
            }

            Surface(
                color = Color(0xFF1565C0).copy(alpha = 0.12f),
                contentColor = Color(0xFF1565C0),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = transactionTypeLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                text = historyDisplayedAmount(financial).formatDisplay(currency),
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1565C0),
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Ref: ${financial.displayRef}",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Time: $timestamp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Status: ${historyLifecycleLabel(financial.lifecycleStates)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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

            financial.description
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { note ->
                    Text(
                        text = note,
                        style =
                            MaterialTheme.typography.bodySmall
                    )
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
        historyDisplayedPaymentCategories(
            financial
        )

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

/**
 * History's prominent amount is the lifecycle-resolved amount still in force.
 * The pre-refund current amount remains available in Transaction Details.
 */
internal fun historyDisplayedAmount(
    financial: FinancialTransactionView
) = financial.netAmount

/**
 * A payment-method change reclassifies the authoritative current funding
 * identity. Original funding remains in the audit timeline, but History must
 * present the resulting method rather than the superseded one.
 */
internal fun historyDisplayedPaymentCategories(
    financial: FinancialTransactionView
): List<PaymentCategory> {
    if (
        FinancialLifecycleState.PAYMENT_METHOD_CHANGED in
            financial.lifecycleStates
    ) {
        return listOfNotNull(
            financial.currentPaymentCategory
        )
    }

    val funded =
        financial.fundingBreakdown
            .map { it.category }
            .distinct()

    return funded.ifEmpty {
        listOfNotNull(
            financial.currentPaymentCategory
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

    return labels.joinToString(" â€¢ ")
}

