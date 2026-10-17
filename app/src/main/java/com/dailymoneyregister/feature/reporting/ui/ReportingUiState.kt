package com.dailymoneyregister.feature.reporting.ui

import com.dailymoneyregister.feature.reporting.data.ReportingBranchOption
import com.dailymoneyregister.feature.reporting.domain.ReportActivityFilter
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentFilter
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodPreset
import com.dailymoneyregister.feature.reporting.domain.ReportingReadModel

enum class ReportingUiError {
    ACCESS_DENIED,
    INTEGRITY,
    INVALID_CUSTOM_PERIOD,
    LOAD_FAILED
}

data class ReportingUiState(
    val selectedPreset: ReportingPeriodPreset =
        ReportingPeriodPreset.TODAY,
    val query: String = "",
    val activityFilter: ReportActivityFilter =
        ReportActivityFilter.ALL,
    val paymentFilter: ReportPaymentFilter =
        ReportPaymentFilter.ALL,
    val customFromDate: String = "",
    val customToDate: String = "",
    val branchOptions: List<ReportingBranchOption> =
        emptyList(),
    val selectedBranchId: String? = null,
    val isLoading: Boolean = true,
    val readModel: ReportingReadModel? = null,
    val rows: List<ReportingRowUiModel> = emptyList(),
    val summaryCards: List<ReportingSummaryUiModel> =
        emptyList(),
    val error: ReportingUiError? = null
) {
    val isEmpty: Boolean
        get() =
            !isLoading &&
                error == null &&
                readModel != null &&
                rows.isEmpty()
}