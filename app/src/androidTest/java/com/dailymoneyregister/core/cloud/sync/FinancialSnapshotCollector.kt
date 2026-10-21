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
        TableSpec("financial_sync_outbox", "id")
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
        return canonical(JSONObject()
            .put("schema_version", 1)
            .put("run_id", runId)
            .put("operation_id", operationId ?: JSONObject.NULL)
            .put("tables", tables))
    }

    private fun whereClause(table: String): String = when (table) {
        "operations", "financial_sync_outbox" -> "id = ? OR operation_id = ?"
        "transactions" -> "id = ? OR id IN (SELECT transaction_id FROM operations WHERE id = ?)"
        "transaction_items", "settlement_entries", "refund_events", "void_events",
        "amount_correction_events", "payment_method_change_events" ->
            "transaction_id IN (SELECT transaction_id FROM operations WHERE id = ?) OR operation_id = ?"
        "expenses" -> "operation_id = ? OR id IN (SELECT subject_id FROM operations WHERE id = ?)"
        "expense_events" -> "operation_id = ? OR expense_id IN (SELECT id FROM expenses WHERE operation_id = ?)"
        "credit_repayments", "credit_repayment_allocations", "credit_ledger_events",
        "coupon_wallets", "coupon_ledger_events", "cash_shifts", "cash_shift_events" ->
            "operation_id = ? OR id = ?"
        else -> "id = ?"
    }

    private fun argsFor(table: String, operationId: String): Array<String> =
        if (table in setOf("operations", "financial_sync_outbox", "transactions", "transaction_items",
                "settlement_entries", "refund_events", "void_events", "amount_correction_events",
                "payment_method_change_events", "expenses", "expense_events", "credit_repayments",
                "credit_repayment_allocations", "credit_ledger_events", "coupon_wallets",
                "coupon_ledger_events", "cash_shifts", "cash_shift_events")) {
            arrayOf(operationId, operationId)
        } else arrayOf(operationId)

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
