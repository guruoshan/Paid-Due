package com.dailymoneyregister.core.cloud.sync

import org.json.JSONArray
import org.json.JSONObject

/** Strict row-level parity comparison over FinancialSnapshotCollector canonical JSON. */
object FinancialParityAssertions {
    data class Mismatch(
        val table: String,
        val primaryId: String,
        val field: String,
        val senderValue: String?,
        val receiverValue: String?,
        val assertion: String
    )

    data class Result(val passed: Boolean, val mismatches: List<Mismatch>) {
        fun requirePass() {
            check(passed) {
                mismatches.joinToString("\n") {
                    it.table + "[" + it.primaryId + "]." + it.field + ": " + it.assertion +
                        " (sender=" + it.senderValue + ", receiver=" + it.receiverValue + ")"
                }
            }
        }
    }

    fun compare(senderSnapshot: String, receiverSnapshot: String): Result {
        val sender = JSONObject(senderSnapshot).getJSONObject("tables")
        val receiver = JSONObject(receiverSnapshot).getJSONObject("tables")
        val mismatches = mutableListOf<Mismatch>()
        val allTables = (sender.keys().asSequence().toSet() + receiver.keys().asSequence().toSet()).sorted()
        allTables.forEach { table ->
            if (!sender.has(table) || !receiver.has(table)) {
                mismatches += Mismatch(table, "*", "*", sender.has(table).toString(),
                    receiver.has(table).toString(), "table presence differs")
                return@forEach
            }
            val left = sender.getJSONObject(table)
            val right = receiver.getJSONObject(table)
            val leftRows = left.getJSONArray("rows")
            val rightRows = right.getJSONArray("rows")
            if (leftRows.length() != rightRows.length()) {
                mismatches += Mismatch(table, "*", "row_count", leftRows.length().toString(),
                    rightRows.length().toString(), "row counts differ")
            }
            val pk = left.optString("primary_key", "id")
            val leftById = indexRows(leftRows, pk, table, mismatches, "sender")
            val rightById = indexRows(rightRows, pk, table, mismatches, "receiver")
            (leftById.keys + rightById.keys).toSortedSet().forEach { id ->
                val l = leftById[id]
                val r = rightById[id]
                if (l == null || r == null) {
                    mismatches += Mismatch(table, id, pk, l?.toString(), r?.toString(),
                        "primary ID missing on one side")
                } else {
                    val fields = (l.keys().asSequence().toSet() + r.keys().asSequence().toSet()).sorted()
                    fields.forEach { field ->
                        val lv = if (l.has(field)) FinancialSnapshotCollector.canonical(l.get(field)) else null
                        val rv = if (r.has(field)) FinancialSnapshotCollector.canonical(r.get(field)) else null
                        if (lv != rv) mismatches += Mismatch(table, id, field, lv, rv,
                            if (field.endsWith("_id") || field in setOf("transaction_id", "operation_id", "shift_id"))
                                "relationship/link differs" else "authoritative value differs")
                    }
                }
            }
        }
        return Result(mismatches.isEmpty(), mismatches)
    }

    private fun indexRows(
        rows: JSONArray,
        primaryKey: String,
        table: String,
        mismatches: MutableList<Mismatch>,
        side: String
    ): Map<String, JSONObject> {
        val indexed = linkedMapOf<String, JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.optString(primaryKey, "")
            if (id.isBlank()) {
                mismatches += Mismatch(table, "row#$i", primaryKey, side, null,
                    "row has no usable primary ID")
            } else if (indexed.put(id, row) != null) {
                mismatches += Mismatch(table, id, primaryKey, side, null,
                    "duplicate primary ID in snapshot")
            }
        }
        return indexed
    }
}
