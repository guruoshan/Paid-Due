package com.dailymoneyregister.core.model.timezone

import java.time.DateTimeException
import java.time.ZoneId

class InvalidTimezoneException(
    val timezoneId: String,
    cause: Throwable? = null
) : IllegalArgumentException(
    "Invalid timezone ID: '$timezoneId'",
    cause
)

/**
 * Single timezone authority for PaidnDue.
 *
 * A timezone is valid because java.time.ZoneId accepts it.
 * setupTimezoneIds is only the current curated setup list.
 *
 * Future worldwide timezone support must not require rewriting
 * business-day or financial-date logic.
 */
object TimezoneRegistry {

    val setupTimezoneIds: List<String> = listOf(
        "Asia/Muscat",
        "Asia/Kolkata",
        "Asia/Dubai",
        "Asia/Riyadh",
        "Asia/Dhaka",
        "America/New_York",
        "UTC"
    )

    fun resolve(timezoneId: String): ZoneId {
        val normalizedId = timezoneId.trim()

        if (normalizedId.isEmpty()) {
            throw InvalidTimezoneException(timezoneId)
        }

        return try {
            ZoneId.of(normalizedId)
        } catch (error: DateTimeException) {
            throw InvalidTimezoneException(timezoneId, error)
        }
    }

    fun isValid(timezoneId: String): Boolean =
        try {
            resolve(timezoneId)
            true
        } catch (_: InvalidTimezoneException) {
            false
        }
}
