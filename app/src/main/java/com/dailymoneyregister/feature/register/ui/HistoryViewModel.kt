package com.dailymoneyregister.feature.register.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.HistoryFilter
import com.dailymoneyregister.feature.register.data.HistoryFilterEngine
import com.dailymoneyregister.feature.register.data.HistoryActivityFilterEngine
import com.dailymoneyregister.feature.register.data.HistoryRepository
import com.dailymoneyregister.feature.register.data.HistoryActivityRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HistoryLoadError {
    PERMISSION_DENIED,
    LOAD_FAILED
}

data class HistoryUiState(
    val loading: Boolean = false,
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.ALL,
    val allRows: List<HistoryActivityRow> = emptyList(),
    val visibleRows: List<HistoryActivityRow> = emptyList(),
    val openedBusinessId: String? = null,
    val openedBranchId: String? = null,
    val error: HistoryLoadError? = null
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
    private val syncScheduler: com.dailymoneyregister.core.cloud.sync.FinancialSyncScheduler
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            HistoryUiState()
        )

    val uiState:
        StateFlow<HistoryUiState> =
        _uiState.asStateFlow()

    private var historyJob: Job? = null

    fun loadHistory() {
        syncScheduler.triggerSync()

        val session =
            SessionManager.currentSession.value

        if (session == null) {

            _uiState.value =
                _uiState.value.copy(
                    loading = false,
                    allRows = emptyList(),
                    visibleRows = emptyList(),
                    openedBusinessId = null,
                    openedBranchId = null,
                    error =
                        HistoryLoadError.PERMISSION_DENIED
                )

            return
        }

        historyJob?.cancel()

        _uiState.value =
            _uiState.value.copy(
                loading = true,
                openedBusinessId =
                    session.business.id,
                openedBranchId =
                    session.branch.id,
                error = null
            )

        historyJob =
            viewModelScope.launch {

                try {

                    repository
                        .observeHistory()
                        .collect { rows ->

                            _uiState.value =
                                _uiState.value.copy(
                                    allRows = rows,
                                    loading = false,
                                    error = null
                                )

                            applyFilter()
                        }

                } catch (
                    cancellation:
                    CancellationException
                ) {
                    throw cancellation

                } catch (
                    denied:
                    PermissionDeniedException
                ) {

                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            allRows = emptyList(),
                            visibleRows = emptyList(),
                            error =
                                HistoryLoadError.PERMISSION_DENIED
                        )

                } catch (
                    denied:
                    SecurityException
                ) {

                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            allRows = emptyList(),
                            visibleRows = emptyList(),
                            error =
                                HistoryLoadError.PERMISSION_DENIED
                        )

                } catch (
                    error:
                    RuntimeException
                ) {

                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            allRows = emptyList(),
                            visibleRows = emptyList(),
                            error =
                                HistoryLoadError.LOAD_FAILED
                        )
                }
            }
    }

    /**
     * Close only History.
     *
     * Query/filter are intentionally preserved so:
     * History -> Details -> Back returns to the same search state.
     */
    fun stopHistory() {

        historyJob?.cancel()
        historyJob = null

        _uiState.value =
            _uiState.value.copy(
                loading = false
            )
    }

    fun updateQuery(
        value: String
    ) {

        _uiState.value =
            _uiState.value.copy(
                query = value
            )

        applyFilter()
    }

    fun updateFilter(
        value: HistoryFilter
    ) {

        _uiState.value =
            _uiState.value.copy(
                filter = value
            )

        applyFilter()
    }

    private fun applyFilter() {

        val state =
            _uiState.value

        val session =
            SessionManager.currentSession.value

        if (
            session == null ||
            session.business.id !=
                state.openedBusinessId ||
            session.branch.id !=
                state.openedBranchId
        ) {

            _uiState.value =
                state.copy(
                    loading = false,
                    allRows = emptyList(),
                    visibleRows = emptyList(),
                    error =
                        HistoryLoadError.PERMISSION_DENIED
                )

            return
        }

        val visible =
            HistoryActivityFilterEngine.filter(
                rows =
                    state.allRows,
                query =
                    state.query,
                filter =
                    HistoryFilter.ALL,
                timezoneId =
                    session.branch.timezoneId,
                decimalPlaces =
                    session.branch.currencyDecimalPlaces
            )

        _uiState.value =
            state.copy(
                visibleRows = visible
            )
    }
}
