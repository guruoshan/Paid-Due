package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.security.ActiveSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal val registerRecentSharingStarted = SharingStarted.WhileSubscribed(
    stopTimeoutMillis = 5_000,
    replayExpirationMillis = 0
)

internal data class RecentBranchContext(
    val branchId: String,
    val timezoneId: String
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> observeRecentForSession(
    sessionFlow: Flow<ActiveSession?>,
    observeToday: (branchId: String, timezoneId: String) -> Flow<List<T>>
): Flow<List<T>> =
    sessionFlow
        .map { session ->
            session?.branch?.let { branch ->
                RecentBranchContext(
                    branchId = branch.id,
                    timezoneId = branch.timezoneId
                )
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { context ->
            if (context == null) {
                flowOf(emptyList())
            } else {
                observeToday(
                    context.branchId,
                    context.timezoneId
                ).onStart { emit(emptyList()) }
            }
        }
