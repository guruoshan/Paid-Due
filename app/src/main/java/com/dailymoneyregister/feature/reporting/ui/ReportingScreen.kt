package com.dailymoneyregister.feature.reporting.ui

import com.dailymoneyregister.feature.reporting.ui.components.ReportingErrorState
import com.dailymoneyregister.feature.reporting.ui.components.ReportingRowCard
import com.dailymoneyregister.feature.reporting.ui.components.ReportingSummarySection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.R
import com.dailymoneyregister.core.cloud.sync.InboundIncompleteBanner
import com.dailymoneyregister.feature.reporting.domain.ReportActivityFilter
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

