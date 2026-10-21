package com.dailymoneyregister.core.cloud.sync

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dailymoneyregister.core.database.DmrDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Read-only certification snapshot of authoritative financial tables.
 * Missing tables fail closed so schema drift cannot silently produce partial evidence.
 */
object FinancialSnapshotCollector {
    data class TableSpec(val table: String, val primaryKey: String)

    val tableSpecs = listOf(
        TableSpec("operations", "id"),
        TableSpec("transactions", "id"),
        TableSpec("transaction_items", "id"),
        TableSpec("settlement_entries", "id"),
        TableSpec("refund_events", "id"),
        TableSpec("void_events", "id"),
        TableSpec("amount_correction_events", "id"),
        TableSpec("payment_method_change_events", "id"),
        TableSpec("expenses", "id"),
        TableSpec("expense_events", "id"),
        TableSpec("credit_repayments", "id"),
        TableSpec("credit_repayment_allocations", "id"),
        TableSpec("credit_ledger_events", "id"),
        TableSpec("coupon_wallets", "id"),
        TableSpec("coupon_ledger_events", "id"),
        TableSpec("cash_shifts", "id"),
        TableSpec("cash_shift_events", "id"),
    )

    fun capture(database: DmrDatabase, runId: String, operationId: String? = null): String =
        capture(database.openHelper.readableDatabase, runId, operationId)

    fun capture(database: SupportSQLiteDatabase, runId: String, operationId: String? = null): String {
        require(runId.isNotBlank()) { "runId must not be blank" }
        val tables = JSONObject()
        tableSpecs.forEach { spec ->
            val rows = JSONArray()
            // Capture the full authoritative table set. This avoids silently omitting linked
            // rows whose relationship column differs by entity; operationId remains run metadata.
            val sql = "SELECT * FROM `" + spec.table + "` ORDER BY `" + spec.primaryKey + "`"
            database.query(sql, emptyArray()).use { cursor ->
                val columns = (0 until cursor.columnCount).map { cursor.getColumnName(it) }
                require(spec.primaryKey in columns) {
                    "Snapshot schema mismatch: " + spec.table + " has no primary key column " + spec.primaryKey
                }
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    columns.sorted().forEach { column ->
                        val index = cursor.getColumnIndexOrThrow(column)
                        row.put(column, cursorValue(cursor, index))
                    }
                    rows.put(row)
                }
            }
            tables.put(spec.table, JSONObject()
                .put("primary_key", spec.primaryKey)
                .put("row_count", rows.length())
                .put("rows", rows))
        }
        if (operationId != null) filterToOperation(tables, operationId)
        return canonical(JSONObject()
            .put("schema_version", 1)
            .put("run_id", runId)
            .put("operation_id", operationId ?: JSONObject.NULL)
            .put("tables", tables))
    }

    /**
     * Keep the operation and all rows linked through stable transaction, expense,
     * customer, and cash-shift identifiers. Relationships are read from the actual
     * entity rows rather than inferred from report aggregates.
     */
    private fun filterToOperation(tables: JSONObject, operationId: String) {
        val operationRows = tables.optJSONObject("operations")?.optJSONArray("rows") ?: JSONArray()
        val operation = (0 until operationRows.length()).map { operationRows.getJSONObject(it) }
            .firstOrNull { it.optString("id") == operationId }
        val transactionIds = mutableSetOf<String>()
        val expenseIds = mutableSetOf<String>()
        val customerIds = mutableSetOf<String>()
        val repaymentIds = mutableSetOf<String>()
        val walletIds = mutableSetOf<String>()
        val shiftIds = mutableSetOf<String>()
        operation?.optString("transaction_id")?.takeIf { it.isNotBlank() }?.let(transactionIds::add)
        operation?.optString("cash_shift_id")?.takeIf { it.isNotBlank() }?.let(shiftIds::add)
        operation?.optString("subject_id")?.takeIf { it.isNotBlank() }?.let(expenseIds::add)

        val transactions = tables.optJSONObject("transactions")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until transactions.length()) {
            val row = transactions.getJSONObject(i)
            if (row.optString("id") in transactionIds || row.optString("operation_id") == operationId) {
                row.optString("id").takeIf { it.isNotBlank() }?.let(transactionIds::add)
                row.optString("customer_id").takeIf { it.isNotBlank() }?.let(customerIds::add)
            }
        }
        val expenses = tables.optJSONObject("expenses")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until expenses.length()) {
            val row = expenses.getJSONObject(i)
            if (row.optString("operation_id") == operationId || row.optString("id") in expenseIds) {
                row.optString("id").takeIf { it.isNotBlank() }?.let(expenseIds::add)
            }
        }
        val repayments = tables.optJSONObject("credit_repayments")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until repayments.length()) {
            val row = repayments.getJSONObject(i)
            if (row.optString("operation_id") == operationId) {
                row.optString("id").takeIf { it.isNotBlank() }?.let(repaymentIds::add)
                row.optString("customer_id").takeIf { it.isNotBlank() }?.let(customerIds::add)
            }
        }
        val creditEvents = tables.optJSONObject("credit_ledger_events")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until creditEvents.length()) {
            val row = creditEvents.getJSONObject(i)
            if (row.optString("operation_id") == operationId) {
                row.optString("customer_id").takeIf { it.isNotBlank() }?.let(customerIds::add)
                row.optString("repayment_id").takeIf { it.isNotBlank() }?.let(repaymentIds::add)
            }
        }
        val couponEvents = tables.optJSONObject("coupon_ledger_events")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until couponEvents.length()) {
            val row = couponEvents.getJSONObject(i)
            if (row.optString("operation_id") == operationId) {
                row.optString("wallet_id").takeIf { it.isNotBlank() }?.let(walletIds::add)
            }
        }
        val shiftEvents = tables.optJSONObject("cash_shift_events")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until shiftEvents.length()) {
            val row = shiftEvents.getJSONObject(i)
            if (row.optString("operation_id") == operationId) {
                row.optString("shift_id").takeIf { it.isNotBlank() }?.let(shiftIds::add)
            }
        }
        val shifts = tables.optJSONObject("cash_shifts")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until shifts.length()) {
            val row = shifts.getJSONObject(i)
            if (row.optString("id") in shiftIds || row.optString("operation_id") == operationId) {
                row.optString("id").takeIf { it.isNotBlank() }?.let(shiftIds::add)
            }
        }

        val transactionTables = setOf("transactions", "transaction_items", "settlement_entries",
            "refund_events", "void_events", "amount_correction_events", "payment_method_change_events")
        val expenseTables = setOf("expenses", "expense_events")
        val customerTables = setOf("credit_repayments", "credit_repayment_allocations",
            "credit_ledger_events", "coupon_wallets", "coupon_ledger_events")
        val shiftTables = setOf("cash_shifts", "cash_shift_events")
        tableSpecs.forEach { spec ->
            val tableObject = tables.getJSONObject(spec.table)
            val source = tableObject.getJSONArray("rows")
            val kept = JSONArray()
            for (i in 0 until source.length()) {
                val row = source.getJSONObject(i)
                val keep = when (spec.table) {
                    "operations" -> row.optString("id") == operationId
                    in transactionTables -> row.optString("operation_id") == operationId ||
                        row.optString("id") in transactionIds || row.optString("transaction_id") in transactionIds
                    in expenseTables -> row.optString("operation_id") == operationId ||
                        row.optString("id") in expenseIds || row.optString("expense_id") in expenseIds
                    in customerTables -> row.optString("operation_id") == operationId ||
                        row.optString("customer_id") in customerIds ||
                        row.optString("repayment_id") in repaymentIds ||
                        row.optString("id") in walletIds || row.optString("wallet_id") in walletIds ||
                        row.optString("transaction_id") in transactionIds
                    in shiftTables -> row.optString("operation_id") == operationId ||
                        row.optString("id") in shiftIds || row.optString("shift_id") in shiftIds ||
                        row.optString("cash_shift_id") in shiftIds
                    else -> row.optString("operation_id") == operationId || row.optString("id") == operationId
                }
                if (keep) kept.put(row)
            }
            tableObject.put("rows", kept).put("row_count", kept.length())
        }
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(index))
        else -> error("Unsupported SQLite value type at column " + cursor.getColumnName(index))
    }

    /** Stable JSON: recursively sort object keys; preserve array order and exact values. */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
