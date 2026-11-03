package com.dailymoneyregister.feature.reporting.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.cloud.sync.FinancialSyncScheduler
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.reporting.data.ReportingReadRepository
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodPreset
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodResolver
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReportingDashboardUiState(
    val isLoading: Boolean = true,
    val cards: List<ReportingSummaryUiModel> =
        emptyList(),
    val unavailable: Boolean = false
)

@HiltViewModel
class ReportingDashboardViewModel @Inject constructor(
    private val repository: ReportingReadRepository,
    private val database: DmrDatabase,
    private val syncScheduler: FinancialSyncScheduler
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            ReportingDashboardUiState()
        )

    val uiState: StateFlow<ReportingDashboardUiState> =
        _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Refresh whenever inbound sync or a local financial command commits to Room.
        viewModelScope.launch {
            database.invalidationTracker.createFlow(
                "transactions",
                "transaction_items",
                "settlement_entries",
                "refund_events",
                "void_events",
                "amount_correction_events",
                "payment_method_change_events",
                "credit_repayments",
                "credit_ledger_events",
                "coupon_ledger_events",
                "coupon_wallets",
                "expenses",
                "expense_events",
                "operations",
                "cash_shift_events"
            ).collect {
                load()
            }
        }
        syncScheduler.triggerSync()
    }

    fun retry() {
        syncScheduler.triggerSync()
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val session =
            SessionManager.currentSession.value

        if (session == null) {
            _uiState.value =
                ReportingDashboardUiState(
                    isLoading = false,
                    unavailable = true
                )
            return
        }

        _uiState.value =
            ReportingDashboardUiState(
                isLoading = true
            )

        loadJob = viewModelScope.launch {
            try {
                val period =
                    ReportingPeriodResolver.resolve(
                        preset =
                            ReportingPeriodPreset.TODAY,
                        timezoneId =
                            session.branch.timezoneId
                    )

                val result =
                    repository.read(
                        ReportingScope(
                            businessId =
                                session.business.id,
                            branchId =
                                session.branch.id,
                            currencyCode =
                                session.branch.currencyCode,
                            period =
                                period
                        )
                    )

                _uiState.value =
                    ReportingDashboardUiState(
                        isLoading = false,
                        cards =
                            ReportingUiProjection.summaryCards(
                                summary =
                                    result.summary,
                                decimalPlaces =
                                    session.branch
                                        .currencyDecimalPlaces,
                                currencySymbol =
                                    session.branch.currencySymbol
                            ),
                        unavailable = false
                    )
            }
            catch (e: CancellationException) {
                throw e
            }
            catch (_: Exception) {
                _uiState.value =
                    ReportingDashboardUiState(
                        isLoading = false,
                        unavailable = true
                    )
            }
        }
    }
}