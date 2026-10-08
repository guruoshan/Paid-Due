package com.dailymoneyregister.feature.reporting.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.reporting.data.ReportingAccessDeniedException
import com.dailymoneyregister.feature.reporting.data.ReportingBranchOption
import com.dailymoneyregister.feature.reporting.data.ReportingFilterOptionsRepository
import com.dailymoneyregister.feature.reporting.data.ReportingIntegrityException
import com.dailymoneyregister.feature.reporting.data.ReportingReadRepository
import com.dailymoneyregister.feature.reporting.domain.ReportActivityFilter
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentFilter
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriod
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodPreset
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodResolver
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ReportingViewModel @Inject constructor(
    private val repository: ReportingReadRepository,
    private val filterOptionsRepository:
        ReportingFilterOptionsRepository,
    private val syncScheduler: com.dailymoneyregister.core.cloud.sync.FinancialSyncScheduler
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            ReportingUiState()
        )

    val uiState: StateFlow<ReportingUiState> =
        _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        syncScheduler.triggerSync()
        initialize()
    }

    fun selectPreset(
        preset: ReportingPeriodPreset
    ) {
        if (
            preset ==
            ReportingPeriodPreset.CUSTOM
        ) {
            _uiState.value =
                _uiState.value.copy(
                    selectedPreset = preset,
                    error = null
                )
            return
        }

        _uiState.value =
            _uiState.value.copy(
                selectedPreset = preset,
                error = null
            )

        load()
    }

    fun updateQuery(
        value: String
    ) {
        _uiState.value =
            project(
                _uiState.value.copy(
                    query = value
                )
            )
    }

    fun selectActivity(
        value: ReportActivityFilter
    ) {
        _uiState.value =
            project(
                _uiState.value.copy(
                    activityFilter = value
                )
            )
    }

    fun selectPayment(
        value: ReportPaymentFilter
    ) {
        _uiState.value =
            project(
                _uiState.value.copy(
                    paymentFilter = value
                )
            )
    }

    fun selectBranch(
        branchId: String
    ) {
        if (
            _uiState.value.branchOptions
                .none { it.id == branchId }
        ) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                selectedBranchId = branchId,
                selectedCashierId = null,
                error = null
            )

        load()
    }

    fun selectCashier(
        cashierId: String?
    ) {
        if (
            cashierId != null &&
            _uiState.value.cashierOptions
                .none { it.id == cashierId }
        ) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                selectedCashierId = cashierId,
                error = null
            )

        load()
    }

    fun updateCustomFromDate(
        value: String
    ) {
        _uiState.value =
            _uiState.value.copy(
                customFromDate = value,
                error = null
            )
    }

    fun updateCustomToDate(
        value: String
    ) {
        _uiState.value =
            _uiState.value.copy(
                customToDate = value,
                error = null
            )
    }

    fun applyCustomPeriod() {
        _uiState.value =
            _uiState.value.copy(
                selectedPreset =
                    ReportingPeriodPreset.CUSTOM,
                error = null
            )

        load()
    }

    fun applyNavigationActivity(
        value: String?
    ) {
        val parsed =
            value
                ?.let {
                    runCatching {
                        ReportActivityFilter.valueOf(it)
                    }.getOrNull()
                }
                ?: ReportActivityFilter.ALL

        selectActivity(parsed)
    }

    fun retry() {
        load()
    }

    private fun initialize() {

        loadJob?.cancel()

        loadJob =
            viewModelScope.launch {

                val session =
                    SessionManager.currentSession.value

                if (session == null) {
                    _uiState.value =
                        ReportingUiState(
                            isLoading = false,
                            error =
                                ReportingUiError.ACCESS_DENIED
                        )
                    return@launch
                }

                try {
                    val options =
                        filterOptionsRepository.load(
                            businessId =
                                session.business.id,
                            currencyCode =
                                session.branch.currencyCode
                        )

                    val selectedBranch =
                        options.branches
                            .firstOrNull {
                                it.id ==
                                    session.branch.id
                            }
                            ?: ReportingBranchOption(
                                id =
                                    session.branch.id,
                                name =
                                    session.branch.name,
                                currencyCode =
                                    session.branch.currencyCode,
                                currencyDecimalPlaces =
                                    session.branch
                                        .currencyDecimalPlaces,
                                currencySymbol =
                                    session.branch.currencySymbol,
                                timezoneId =
                                    session.branch.timezoneId
                            )

                    val branches =
                        if (
                            options.branches.any {
                                it.id ==
                                    selectedBranch.id
                            }
                        ) {
                            options.branches
                        } else {
                            listOf(selectedBranch) +
                                options.branches
                        }

                    _uiState.value =
                        _uiState.value.copy(
                            branchOptions = branches,
                            cashierOptions =
                                options.cashiers,
                            selectedBranchId =
                                selectedBranch.id,
                            isLoading = false,
                            error = null
                        )

                    load()
                }
                catch (e: CancellationException) {
                    throw e
                }
                catch (_: Exception) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error =
                                ReportingUiError.LOAD_FAILED
                        )
                }
            }
    }

    private fun load() {

        loadJob?.cancel()

        val session =
            SessionManager.currentSession.value

        if (session == null) {
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    readModel = null,
                    rows = emptyList(),
                    summaryCards = emptyList(),
                    error =
                        ReportingUiError.ACCESS_DENIED
                )
            return
        }

        val branch =
            selectedBranch()
                ?: run {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error =
                                ReportingUiError.INTEGRITY
                        )
                    return
                }

        val period =
            try {
                resolvePeriod(
                    branch
                )
            }
            catch (_: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        readModel = null,
                        rows = emptyList(),
                        summaryCards = emptyList(),
                        error =
                            ReportingUiError.INVALID_CUSTOM_PERIOD
                    )
                return
            }

        val scope =
            ReportingScope(
                businessId =
                    session.business.id,
                branchId =
                    branch.id,
                cashierUserId =
                    _uiState.value
                        .selectedCashierId,
                currencyCode =
                    branch.currencyCode,
                period =
                    period
            )

        _uiState.value =
            _uiState.value.copy(
                isLoading = true,
                readModel = null,
                rows = emptyList(),
                summaryCards = emptyList(),
                error = null
            )

        loadJob =
            viewModelScope.launch {

                try {
                    val result =
                        repository.read(
                            scope
                        )

                    _uiState.value =
                        project(
                            _uiState.value.copy(
                                isLoading = false,
                                readModel = result,
                                error = null
                            )
                        )
                }
                catch (e: CancellationException) {
                    throw e
                }
                catch (_: ReportingAccessDeniedException) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            readModel = null,
                            rows = emptyList(),
                            summaryCards = emptyList(),
                            error =
                                ReportingUiError.ACCESS_DENIED
                        )
                }
                catch (_: ReportingIntegrityException) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            readModel = null,
                            rows = emptyList(),
                            summaryCards = emptyList(),
                            error =
                                ReportingUiError.INTEGRITY
                        )
                }
                catch (_: Exception) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            readModel = null,
                            rows = emptyList(),
                            summaryCards = emptyList(),
                            error =
                                ReportingUiError.LOAD_FAILED
                        )
                }
            }
    }

    private fun project(
        state: ReportingUiState
    ): ReportingUiState {

        val result =
            state.readModel
                ?: return state.copy(
                    rows = emptyList(),
                    summaryCards = emptyList()
                )

        val branch =
            selectedBranch(state)
                ?: return state.copy(
                    rows = emptyList(),
                    summaryCards = emptyList(),
                    error =
                        ReportingUiError.INTEGRITY
                )

        val filtered =
            ReportingUiProjection.filterRows(
                rows = result.rows,
                query = state.query,
                activity = state.activityFilter,
                payment = state.paymentFilter
            )

        return state.copy(
            rows =
                ReportingUiProjection.toRows(
                    rows = filtered,
                    decimalPlaces =
                        branch.currencyDecimalPlaces,
                    currencySymbol =
                        branch.currencySymbol,
                    timezoneId =
                        branch.timezoneId
                ),
            summaryCards =
                ReportingUiProjection.summaryCards(
                    summary = result.summary,
                    decimalPlaces =
                        branch.currencyDecimalPlaces,
                    currencySymbol =
                        branch.currencySymbol
                )
        )
    }

    private fun selectedBranch(
        state: ReportingUiState =
            _uiState.value
    ): ReportingBranchOption? =
        state.branchOptions
            .firstOrNull {
                it.id ==
                    state.selectedBranchId
            }

    private fun resolvePeriod(
        branch: ReportingBranchOption
    ): ReportingPeriod {

        val state =
            _uiState.value

        if (
            state.selectedPreset !=
            ReportingPeriodPreset.CUSTOM
        ) {
            return ReportingPeriodResolver.resolve(
                preset =
                    state.selectedPreset,
                timezoneId =
                    branch.timezoneId
            )
        }

        val zone =
            ZoneId.of(
                branch.timezoneId
            )

        val from =
            LocalDate.parse(
                state.customFromDate.trim()
            )

        val to =
            LocalDate.parse(
                state.customToDate.trim()
            )

        require(!to.isBefore(from))

        return ReportingPeriodResolver.custom(
            fromInclusive =
                from.atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli(),
            toExclusive =
                to.plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
        )
    }
}