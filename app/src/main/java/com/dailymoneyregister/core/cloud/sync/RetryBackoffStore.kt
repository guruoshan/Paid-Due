package com.dailymoneyregister.core.cloud.sync

import android.content.SharedPreferences

/** Durable retry gate; attempt counts remain authoritative in the Room outbox row. */
class RetryBackoffStore(private val preferences: SharedPreferences?) {
    private val memory = mutableMapOf<String, Long>()
    fun nextRetryAt(operationId: String): Long? =
        preferences?.getLong(key(operationId), -1L)?.takeIf { it >= 0 } ?: memory[operationId]
    fun save(operationId: String, timestamp: Long) {
        memory[operationId] = timestamp
        preferences?.edit()?.putLong(key(operationId), timestamp)?.commit()
    }
    fun clear(operationId: String) {
        memory.remove(operationId)
        preferences?.edit()?.remove(key(operationId))?.commit()
    }
    private fun key(operationId: String) = "next_retry_$operationId"
}
