package com.dailymoneyregister.feature.reporting.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.R
import com.dailymoneyregister.core.cloud.sync.InboundIncompleteBanner
import com.dailymoneyregister.feature.reporting.domain.ReportActivityFilter
import com.dailymoneyregister.feature.reporting.domain.ReportActivityType
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentFilter
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodPreset
import com.dailymoneyregister.feature.reporting.pdf.ReportingPdfActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportingScreen(
    viewModel: ReportingViewModel,
    onNavigateBack: () -> Unit,
    onOpenCustomer: (String) -> Unit,
    onExportCustomers: () -> Unit,
    initialActivityName: String? = null
) {

    val state =
        viewModel.uiState
            .collectAsStateWithLifecycle()
            .value

    LaunchedEffect(initialActivityName) {
        viewModel.applyNavigationActivity(
            initialActivityName
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.reporting_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    R.string.reporting_back
                                )
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            InboundIncompleteBanner()

            OutlinedButton(onClick = onExportCustomers, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.audit_export_customers)) }

            ReportingPeriodSelector(
                selected =
                    state.selectedPreset,
                onSelected =
                    viewModel::selectPreset
            )

            if (
                state.selectedPreset ==
                ReportingPeriodPreset.CUSTOM
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value =
                            state.customFromDate,
                        onValueChange =
                            viewModel::updateCustomFromDate,
                        modifier =
                            Modifier.weight(1f),
                        label = {
                            Text(
                                stringResource(
                                    R.string.reporting_from_date
                                )
                            )
                        },
                        placeholder = {
                            Text(stringResource(R.string.audit_date_format_hint))
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value =
                            state.customToDate,
                        onValueChange =
                            viewModel::updateCustomToDate,
                        modifier =
                            Modifier.weight(1f),
                        label = {
                            Text(
                                stringResource(
                                    R.string.reporting_to_date
                                )
                            )
                        },
                        placeholder = {
                            Text(stringResource(R.string.audit_date_format_hint))
                        },
                        singleLine = true
                    )
                }

                OutlinedButton(
                    onClick =
                        viewModel::applyCustomPeriod
                ) {
                    Text(
                        stringResource(
                            R.string.reporting_apply_dates
                        )
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange =
                    viewModel::updateQuery,
                modifier =
                    Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(
                            R.string.reporting_search
                        )
                    )
                },
                singleLine = true
            )

            ReportingScopeSelectors(
                state = state,
                onBranch = viewModel::selectBranch
            )

            ReportingActivityFilters(
                selected =
                    state.activityFilter,
                onSelected =
                    viewModel::selectActivity
            )

            ReportingPaymentFilters(
                selected =
                    state.paymentFilter,
                onSelected =
                    viewModel::selectPayment
            )

            ReportingPdfActions(
                state = state,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            when {
                state.isLoading -> {
                    Column(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(
                                R.string.reporting_loading
                            )
                        )
                    }
                }

                state.error != null -> {
                    ReportingErrorState(
                        error = state.error,
                        detail = state.errorDetail,
                        onRetry =
                            viewModel::retry
                    )
                }

                else -> {
                    ReportingSummarySection(
                        cards =
                            state.summaryCards
                    )

                    if (state.isEmpty) {
                        Text(
                            stringResource(
                                R.string.reporting_no_results
                            )
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = state.rows,
                                key = {
                                    it.stableId
                                }
                            ) { row ->
                                ReportingRowCard(
                                    row = row,
                                    onOpenCustomer =
                                        onOpenCustomer
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
private fun ReportingPeriodSelector(
    selected: ReportingPeriodPreset,
    onSelected: (ReportingPeriodPreset) -> Unit
) {
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
        ReportingPeriodPreset.entries
            .forEach { preset ->
                FilterChip(
                    selected =
                        selected == preset,
                    onClick = {
                        onSelected(preset)
                    },
                    label = {
                        Text(
                            periodLabel(preset)
                        )
                    }
                )
            }
    }
}

@Composable
private fun periodLabel(
    preset: ReportingPeriodPreset
): String =
    when (preset) {
        ReportingPeriodPreset.TODAY ->
            stringResource(
                R.string.reporting_today
            )

        ReportingPeriodPreset.YESTERDAY ->
            stringResource(
                R.string.reporting_yesterday
            )

        ReportingPeriodPreset.THIS_WEEK ->
            stringResource(
                R.string.reporting_this_week
            )

        ReportingPeriodPreset.THIS_MONTH ->
            stringResource(
                R.string.reporting_this_month
            )

        ReportingPeriodPreset.CUSTOM ->
            stringResource(
                R.string.reporting_custom
            )
    }

@Composable
private fun ReportingScopeSelectors(
    state: ReportingUiState,
    onBranch: (String) -> Unit
) {
    var branchExpanded by
        remember {
            mutableStateOf(false)
        }

    val selectedBranch =
        state.branchOptions
            .firstOrNull {
                it.id ==
                    state.selectedBranchId
            }

    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier =
                Modifier.weight(1f)
        ) {
            TextButton(
                onClick = {
                    branchExpanded = true
                }
            ) {
                Text(
                    selectedBranch?.name
                        ?: stringResource(
                            R.string.reporting_branch
                        )
                )
            }

            DropdownMenu(
                expanded = branchExpanded,
                onDismissRequest = {
                    branchExpanded = false
                }
            ) {
                state.branchOptions
                    .forEach { branch ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    branch.name
                                )
                            },
                            onClick = {
                                branchExpanded = false
                                onBranch(branch.id)
                            }
                        )
                    }
            }
        }


    }
}

@Composable
private fun ReportingActivityFilters(
    selected: ReportActivityFilter,
    onSelected: (ReportActivityFilter) -> Unit
) {
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
        ReportActivityFilter.entries
            .forEach { filter ->
                FilterChip(
                    selected =
                        selected == filter,
                    onClick = {
                        onSelected(filter)
                    },
                    label = {
                        Text(
                            activityFilterLabel(
                                filter
                            )
                        )
                    }
                )
            }
    }
}

@Composable
private fun activityFilterLabel(
    filter: ReportActivityFilter
): String =
    when (filter) {
        ReportActivityFilter.ALL ->
            stringResource(
                R.string.reporting_all
            )

        ReportActivityFilter.SALES ->
            stringResource(
                R.string.reporting_sales
            )

        ReportActivityFilter.CREDIT ->
            stringResource(
                R.string.reporting_credit_sales
            )

        ReportActivityFilter.CREDIT_REPAYMENTS ->
            stringResource(
                R.string.reporting_credit_repayments
            )

        ReportActivityFilter.COUPON ->
            stringResource(
                R.string.reporting_coupon
            )

        ReportActivityFilter.REFUNDS ->
            stringResource(
                R.string.reporting_refunds
            )

        ReportActivityFilter.EXPENSES ->
            stringResource(
                R.string.reporting_expenses
            )

        ReportActivityFilter.VOIDS ->
            stringResource(
                R.string.reporting_voids
            )

        ReportActivityFilter.CORRECTIONS ->
            stringResource(
                R.string.reporting_corrections
            )

        ReportActivityFilter.PAYMENT_METHOD_CHANGES ->
            stringResource(
                R.string.reporting_payment_changes
            )
    }

@Composable
private fun ReportingPaymentFilters(
    selected: ReportPaymentFilter,
    onSelected: (ReportPaymentFilter) -> Unit
) {
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
        ReportPaymentFilter.entries
            .forEach { filter ->
                FilterChip(
                    selected =
                        selected == filter,
                    onClick = {
                        onSelected(filter)
                    },
                    label = {
                        Text(
                            when (filter) {
                                ReportPaymentFilter.ALL ->
                                    stringResource(
                                        R.string.reporting_all
                                    )

                                ReportPaymentFilter.CASH ->
                                    stringResource(
                                        R.string.reporting_cash
                                    )

                                ReportPaymentFilter.ONLINE ->
                                    stringResource(
                                        R.string.reporting_online
                                    )

                                ReportPaymentFilter.CREDIT ->
                                    stringResource(
                                        R.string.reporting_credit
                                    )

                                ReportPaymentFilter.COUPON ->
                                    stringResource(
                                        R.string.reporting_coupon
                                    )
                            }
                        )
                    }
                )
            }
    }
}

@Composable
private fun ReportingSummarySection(
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

        ReportingSummaryMetric.REFUNDS ->
            stringResource(
                R.string.reporting_refunds
            )

        ReportingSummaryMetric.EXPENSES ->
            stringResource(
                R.string.reporting_expenses
            )
    }

@Composable
private fun ReportingRowCard(
    row: ReportingRowUiModel,
    onOpenCustomer: (String) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val flowColor = when (row.activityType) {
        ReportActivityType.SALE,
        ReportActivityType.CREDIT_SALE,
        ReportActivityType.CREDIT_REPAYMENT,
        ReportActivityType.COUPON_PURCHASE,
        ReportActivityType.COUPON_TOP_UP,
        ReportActivityType.COUPON_CREDIT_REPAYMENT ->
            if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
        ReportActivityType.REFUND,
        ReportActivityType.COUPON_REFUND,
        ReportActivityType.EXPENSE,
        ReportActivityType.EXPENSE_VOID,
        ReportActivityType.VOID ->
            if (isDarkTheme) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        ReportActivityType.AMOUNT_CORRECTION -> when {
            row.amountText?.startsWith("-") == true ->
                if (isDarkTheme) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
            row.amountText?.startsWith("+") == true ->
                if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
            else -> if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
        }
        ReportActivityType.COUPON_BONUS ->
            if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
        ReportActivityType.COUPON_REDEMPTION,
        ReportActivityType.PAYMENT_METHOD_CHANGE ->
            if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
    }
    val typeLabel = when (row.activityType) {
        ReportActivityType.SALE -> stringResource(R.string.audit_status_sale_recorded)
        ReportActivityType.COUPON_PURCHASE -> stringResource(R.string.audit_status_coupon_funds_sold)
        ReportActivityType.COUPON_TOP_UP -> stringResource(R.string.audit_status_coupon_topup)
        ReportActivityType.CREDIT_SALE,
        ReportActivityType.CREDIT_REPAYMENT,
        ReportActivityType.COUPON_CREDIT_REPAYMENT -> stringResource(R.string.audit_status_credit)
        ReportActivityType.EXPENSE -> stringResource(R.string.audit_status_expense_recorded)
        ReportActivityType.EXPENSE_VOID -> stringResource(R.string.audit_status_expense_void)
        ReportActivityType.REFUND,
        ReportActivityType.COUPON_REFUND -> stringResource(R.string.audit_status_refund_to_coupon)
        ReportActivityType.VOID -> stringResource(R.string.audit_status_void)
        ReportActivityType.AMOUNT_CORRECTION -> stringResource(R.string.audit_status_amount_correction)
        ReportActivityType.PAYMENT_METHOD_CHANGE -> stringResource(R.string.audit_status_payment_method_change)
        ReportActivityType.COUPON_REDEMPTION -> stringResource(R.string.audit_status_coupon_use)
        ReportActivityType.COUPON_BONUS -> stringResource(R.string.audit_status_other_income)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.widthIn(min = 112.dp, max = 180.dp).heightIn(min = 24.dp),
                    color = flowColor.copy(alpha = if (isDarkTheme) 0.20f else 0.12f),
                    contentColor = flowColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            typeLabel,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = row.occurredAtText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    textAlign = TextAlign.End
                )
            }

            row.amountText?.let { amount ->
                Text(
                    text = amount,
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.ExtraBold,
                    color = flowColor,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2
                )
            }

            row.referenceText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (row.customerName != null || row.customerPhone != null) {
                val customerText = listOfNotNull(row.customerName, row.customerPhone)
                    .joinToString(" · ")
                Text(
                    text = customerText,
                    modifier = if (row.customerId != null) {
                        Modifier.clickable { onOpenCustomer(row.customerId) }
                    } else Modifier,
                    color = if (row.customerId != null) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            row.paymentText?.let {
                Text(
                    text = stringResource(R.string.reporting_payment_value, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            row.reasonText?.let {
                Text(
                    text = stringResource(R.string.reporting_reason_value, it),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = listOfNotNull(row.branchName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun activityTypeLabel(
    type: ReportActivityType
): String =
    when (type) {
        ReportActivityType.SALE ->
            stringResource(
                R.string.reporting_sale
            )

        ReportActivityType.CREDIT_SALE ->
            stringResource(
                R.string.reporting_credit_sale
            )

        ReportActivityType.CREDIT_REPAYMENT ->
            stringResource(
                R.string.reporting_credit_repayment
            )

        ReportActivityType.COUPON_PURCHASE ->
            stringResource(
                R.string.reporting_coupon_purchase
            )

        ReportActivityType.COUPON_TOP_UP ->
            stringResource(
                R.string.reporting_coupon_topup
            )

        ReportActivityType.COUPON_REDEMPTION ->
            stringResource(
                R.string.reporting_coupon_redemption
            )

        ReportActivityType.COUPON_REFUND ->
            stringResource(
                R.string.reporting_coupon_refund
            )

        ReportActivityType.COUPON_BONUS ->
            stringResource(
                R.string.reporting_coupon_bonus
            )

        ReportActivityType.COUPON_CREDIT_REPAYMENT ->
            stringResource(
                R.string.reporting_coupon_credit_repayment
            )

        ReportActivityType.REFUND ->
            stringResource(
                R.string.reporting_refund
            )

        ReportActivityType.EXPENSE ->
            stringResource(
                R.string.reporting_expense
            )

        ReportActivityType.EXPENSE_VOID ->
            "EXPENSE VOID"

        ReportActivityType.VOID ->
            stringResource(
                R.string.reporting_void
            )

        ReportActivityType.AMOUNT_CORRECTION ->
            stringResource(
                R.string.reporting_amount_correction
            )

        ReportActivityType.PAYMENT_METHOD_CHANGE ->
            stringResource(
                R.string.reporting_payment_method_change
            )
    }

@Composable
private fun ReportingErrorState(
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
