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

enum class HistoryActivitySourceType {
    TRANSACTION,
    EXPENSE
}

/**
 * BOARDLOCK cross-screen History discovery row.
 *
 * This is presentation/read state only.
 *
 * Transaction financial meaning remains owned by FinancialTransactionView.
 * Expense financial meaning remains owned by ExpenseReadProjection, which
 * itself is derived directly from authoritative ExpenseEntity evidence.
 *
 * No TransactionEntity is manufactured for an Expense.
 */
sealed interface HistoryActivityRow {

    val sourceType: HistoryActivitySourceType

    val sourceId: String

    val activityKey: String
        get() =
            "${sourceType.name}:$sourceId"

    val displayRef: String

    val occurredAt: Long

    val customerName: String?

    val canonicalPhone: String?

    val searchableAmounts: List<MoneyMinorUnits>

    data class Transaction(
        val row: HistoryTransactionRow
    ) : HistoryActivityRow {

        override val sourceType:
            HistoryActivitySourceType =
            HistoryActivitySourceType.TRANSACTION

        override val sourceId: String
            get() =
                row.transactionId

        override val displayRef: String
            get() =
                row.displayRef

        override val occurredAt: Long
            get() =
                row.financial.createdAt

        override val customerName: String?
            get() =
                row.customerName

        override val canonicalPhone: String?
            get() =
                row.canonicalPhone

        override val searchableAmounts:
            List<MoneyMinorUnits>
            get() =
                listOf(
                    row.financial.originalAmount,
                    row.financial.currentAmount,
                    row.financial.netAmount
                ).distinct()
    }

    data class Expense(
        val expense: ExpenseReadProjection
    ) : HistoryActivityRow {

        override val sourceType:
            HistoryActivitySourceType =
            HistoryActivitySourceType.EXPENSE

        override val sourceId: String
            get() =
                expense.expenseId

        override val displayRef: String
            get() =
                expense.displayReference

        override val occurredAt: Long
            get() =
                expense.occurredAt

        override val customerName: String?
            get() =
                expense.payeeName

        override val canonicalPhone: String?
            get() =
                expense.payeePhone

        /*
         * Search uses the entered positive Expense amount.
         * Display/financial effect remains negative via signedEffect.
         */
        override val searchableAmounts:
            List<MoneyMinorUnits>
            get() =
                listOf(expense.amount)
    }
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

        fun matchesNote(
            row: HistoryTransactionRow
        ): Boolean =
            row.financial.description
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
                        matchesNote(row) ||
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

/**
 * BOARDLOCK unified History filter.
 *
 * Pure read-model filtering only.
 * No financial calculations or persistence.
 */
object HistoryActivityFilterEngine {

    fun filter(
        rows: List<HistoryActivityRow>,
        query: String,
        filter: HistoryFilter,
        timezoneId: String,
        decimalPlaces: Int
    ): List<HistoryActivityRow> {

        val clean =
            query.trim()

        val sorted =
            rows.sortedWith(
                compareByDescending<HistoryActivityRow> {
                    it.occurredAt
                }.thenByDescending {
                    it.activityKey
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
            row: HistoryActivityRow
        ): Boolean =
            row.displayRef.contains(
                clean,
                ignoreCase = true
            ) ||
                row.sourceId.contains(
                    clean,
                    ignoreCase = true
                )

        fun matchesDate(
            row: HistoryActivityRow
        ): Boolean =
            historyDateMatches(
                epochMillis =
                    row.occurredAt,
                timezoneId =
                    timezoneId,
                query =
                    clean
            )

        fun matchesPhone(
            row: HistoryActivityRow
        ): Boolean =
            historyPhoneMatches(
                canonicalPhone =
                    row.canonicalPhone,
                query =
                    clean
            )

        fun matchesName(
            row: HistoryActivityRow
        ): Boolean =
            row.customerName
                ?.contains(
                    clean,
                    ignoreCase = true
                )
                ?: false

                fun matchesNote(
            row: HistoryActivityRow
        ): Boolean =
            when (row) {
                is HistoryActivityRow.Transaction ->
                    row.row.financial.description
                        ?.contains(
                            clean,
                            ignoreCase = true
                        )
                        ?: false

                is HistoryActivityRow.Expense ->
                    false
            }
fun matchesAmount(
            row: HistoryActivityRow
        ): Boolean {

            val wanted =
                parsedAmount ?: return false

            return wanted in
                row.searchableAmounts
        }

        return sorted.filter { row ->

            when (filter) {

                HistoryFilter.ALL ->
                    matchesReference(row) ||
                        matchesDate(row) ||
                        matchesPhone(row) ||
                        matchesName(row) ||
                        matchesNote(row) ||
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

