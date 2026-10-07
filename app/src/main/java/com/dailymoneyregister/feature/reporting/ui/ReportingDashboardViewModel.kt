package com.dailymoneyregister.feature.reporting.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.reporting.data.ReportingReadRepository
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodPreset
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodResolver
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    private val repository: ReportingReadRepository
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            ReportingDashboardUiState()
        )

    val uiState: StateFlow<ReportingDashboardUiState> =
        _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
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

        viewModelScope.launch {
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
                            cashierUserId =
                                null,
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