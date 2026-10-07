package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReadBatchingTest {

    @Test
    fun emptyIdsPerformNoDatabaseQuery() = runBlocking {
        var queryCount = 0

        val rows = loadRecentRowsInBatches<String>(emptyList()) {
            queryCount++
            it
        }

        assertEquals(emptyList<String>(), rows)
        assertEquals(0, queryCount)
    }

    @Test
    fun idsAreDeduplicatedAndSplitBelowSqliteBindLimit() = runBlocking {
        val ids = (0 until 901).map { "id-$it" } + listOf("id-0", "", "id-900")
        val batches = mutableListOf<List<String>>()

        val rows = loadRecentRowsInBatches(ids) { batch ->
            batches += batch
            batch
        }

        assertEquals(listOf(900, 1), batches.map { it.size })
        assertEquals(901, rows.size)
        assertEquals(901, rows.distinct().size)
    }
}
