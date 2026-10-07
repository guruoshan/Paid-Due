package com.dailymoneyregister.feature.reporting.domain

/**
 * Reporting interval using the project's required half-open time range:
 *
 *     fromInclusive <= occurredAt < toExclusive
 *
 * Actual Today / Yesterday / Week / Month boundaries must later be resolved
 * with the authoritative branch timezone.
 */
data class ReportingPeriod(
    val fromInclusive: Long,
    val toExclusive: Long,
    val preset: ReportingPeriodPreset = ReportingPeriodPreset.CUSTOM
) {
    init {
        require(fromInclusive >= 0L) {
            "Reporting period start must be non-negative."
        }

        require(toExclusive > fromInclusive) {
            "Reporting period end must be greater than start."
        }
    }

    fun contains(occurredAt: Long): Boolean =
        occurredAt >= fromInclusive &&
            occurredAt < toExclusive
}

enum class ReportingPeriodPreset {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    THIS_MONTH,
    CUSTOM
}