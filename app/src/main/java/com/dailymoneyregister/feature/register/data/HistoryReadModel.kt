package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class HistoryFilter {
    ALL,
    DATE,
    PHONE,
    NAME,
    AMOUNT
}

/**
 * Read-only transaction discovery row.
 *
 * BOARDLOCK:
 * - FinancialTransactionView remains authoritative for financial meaning.
 * - No independent balance or lifecycle math exists here.
 * - Customer identity comes from TransactionFinancialRecord.customer.
 */
data class HistoryTransactionRow(
    val financial: FinancialTransactionView,
    val customerName: String?,
    val canonicalPhone: String?
) {
    val transactionId: String
        get() = financial.id

    val displayRef: String
        get() = financial.displayRef
}

internal fun historyPhoneDigits(
    value: String
): String =
    value.filter(Char::isDigit)

internal fun historyPhoneMatches(
    canonicalPhone: String?,
    query: String
): Boolean {

    val stored =
        canonicalPhone ?: return false

    val storedDigits =
        historyPhoneDigits(stored)

    val queryDigits =
        historyPhoneDigits(query)

    if (queryDigits.isBlank()) {
        return false
    }

    /*
     * canonicalPhone remains the authoritative stored identity.
     * This only makes History search tolerant of presentation formatting.
     */
    return storedDigits == queryDigits ||
        storedDigits.endsWith(queryDigits)
}

internal fun historyDateMatches(
    epochMillis: Long,
    timezoneId: String,
    query: String
): Boolean {

    val zone =
        ZoneId.of(timezoneId)

    val localDate =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .toLocalDate()

    val slash =
        DateTimeFormatter.ofPattern(
            "dd/MM/yyyy",
            Locale.ROOT
        ).format(localDate)

    val dash =
        DateTimeFormatter.ofPattern(
            "dd-MM-yyyy",
            Locale.ROOT
        ).format(localDate)

    val clean =
        query.trim()

    return clean == slash ||
        clean == dash
}

internal fun historyAmountQuery(
    query: String,
    decimalPlaces: Int
): MoneyMinorUnits? =
    MoneyMinorUnits.parse(
        query.trim(),
        decimalPlaces
    ).getOrNull()

object HistoryFilterEngine {

    fun filter(
        rows: List<HistoryTransactionRow>,
        query: String,
        filter: HistoryFilter,
        timezoneId: String,
        decimalPlaces: Int
    ): List<HistoryTransactionRow> {

        val clean =
            query.trim()

        val sorted =
            rows.sortedWith(
                compareByDescending<HistoryTransactionRow> {
                    it.financial.createdAt
                }.thenByDescending {
                    it.transactionId
                }
            )

        if (clean.isBlank()) {
            return sorted
        }

        val parsedAmount =
            historyAmountQuery(
                clean,
                decimalPlaces
            )

        fun matchesReference(
            row: HistoryTransactionRow
        ): Boolean =
            row.displayRef.contains(
                clean,
                ignoreCase = true
            ) ||
                row.transactionId.contains(
                    clean,
                    ignoreCase = true
                )

        fun matchesDate(
            row: HistoryTransactionRow
        ): Boolean =
            historyDateMatches(
                epochMillis =
                    row.financial.createdAt,
                timezoneId =
                    timezoneId,
                query =
                    clean
            )

        fun matchesPhone(
            row: HistoryTransactionRow
        ): Boolean =
            historyPhoneMatches(
                canonicalPhone =
                    row.canonicalPhone,
                query =
                    clean
            )

        fun matchesName(
            row: HistoryTransactionRow
        ): Boolean =
            row.customerName
                ?.contains(
                    clean,
                    ignoreCase = true
                )
                ?: false

        fun matchesAmount(
            row: HistoryTransactionRow
        ): Boolean {

            val wanted =
                parsedAmount ?: return false

            /*
             * These are FinancialReadModelResolver outputs.
             * History does not recalculate them.
             */
            return row.financial.originalAmount == wanted ||
                row.financial.currentAmount == wanted ||
                row.financial.netAmount == wanted
        }

        return sorted.filter { row ->

            when (filter) {

                HistoryFilter.ALL ->
                    matchesReference(row) ||
                        matchesDate(row) ||
                        matchesPhone(row) ||
                        matchesName(row) ||
                        matchesAmount(row)

                HistoryFilter.DATE ->
                    matchesDate(row)

                HistoryFilter.PHONE ->
                    matchesPhone(row)

                HistoryFilter.NAME ->
                    matchesName(row)

                HistoryFilter.AMOUNT ->
                    matchesAmount(row)
            }
        }
    }
}