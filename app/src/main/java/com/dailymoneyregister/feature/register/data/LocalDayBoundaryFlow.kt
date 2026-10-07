package com.dailymoneyregister.feature.register.data

import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * BOARDLOCK non-financial local-day boundary flow.
 *
 * This helper owns only clock/day-boundary scheduling.
 * It does not read or mutate financial state.
 *
 * The function below is transplanted verbatim from
 * RegisterRepository.kt.
 */
internal fun observeLocalDayStarts(
    zoneId: ZoneId,
    nowMillis: () -> Long = { System.currentTimeMillis() },
    waitFor: suspend (Long) -> Unit = { delay(it) }
): Flow<Long> = flow {
    while (currentCoroutineContext().isActive) {
        val now = nowMillis()
        val currentDate = java.time.Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val startOfDay = currentDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        emit(startOfDay)

        val nextStartOfDay = currentDate.plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        waitFor((nextStartOfDay - now).coerceAtLeast(1L))
    }
}
