package com.dailymoneyregister.feature.invoice.domain

import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic JSON codec for InvoiceSnapshot.
 *
 * The encoded text is used BOTH as documents.snapshot_json and as the
 * durable operation intent_payload, so encoding must be stable.
 */
object InvoiceSnapshotCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(s: InvoiceSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", s.schemaVersion)
        root.put("documentId", s.documentId)
        root.put("currencyCode", s.currencyCode)
        root.put("currencyDecimalPlaces", s.currencyDecimalPlaces)

        root.put("customer", JSONObject().apply {
            put("title", s.customer.title ?: JSONObject.NULL)
            put("name", s.customer.name)
            put("phone", s.customer.phone)
            put("address", s.customer.address ?: JSONObject.NULL)
        })

        root.put("business", JSONObject().apply {
            put("name", s.business.name ?: JSONObject.NULL)
            put("registrationNumber", s.business.registrationNumber ?: JSONObject.NULL)
            put("taxId", s.business.taxId ?: JSONObject.NULL)
            put("address", s.business.address ?: JSONObject.NULL)
            put("phone", s.business.phone ?: JSONObject.NULL)
        })

        root.put("items", JSONArray().also { arr ->
            s.items.forEach { i ->
                arr.put(JSONObject().apply {
                    put("id", i.id)
                    put("name", i.name)
                    put("description", i.description ?: JSONObject.NULL)
                    put("quantity", i.quantity)
                    put("unitPriceMinor", i.unitPriceMinor)
                    put("lineTotalMinor", i.lineTotalMinor)
                    put("itemPackingMinor", i.itemPackingMinor)
                    put("itemShippingMinor", i.itemShippingMinor)
                })
            }
        })

        root.put("subtotalMinor", s.subtotalMinor)
        root.put("taxRateBps", s.taxRateBps)
        root.put("taxAmountMinor", s.taxAmountMinor)
        root.put("txShippingMinor", s.txShippingMinor)
        root.put("txPackingMinor", s.txPackingMinor)
        root.put("itemShippingTotalMinor", s.itemShippingTotalMinor)
        root.put("itemPackingTotalMinor", s.itemPackingTotalMinor)
        root.put("grandTotalMinor", s.grandTotalMinor)
        root.put("note", s.note ?: JSONObject.NULL)
        root.put("createdAt", s.createdAt)
        // Omit absent fields so re-encoding a legacy snapshot preserves its old payload shape.
        s.taxableBaseMinor?.let { root.put("taxableBaseMinor", it) }
        s.taxRoundingMode?.let { root.put("taxRoundingMode", it.name) }
        s.taxBaseIncludesCharges?.let { root.put("taxBaseIncludesCharges", it) }
        return root.toString()
    }

    fun decode(json: String): InvoiceSnapshot {
        val r = JSONObject(json)
        val version = r.getInt("schemaVersion")
        require(version == CURRENT_SCHEMA_VERSION) {
            "Unsupported invoice snapshot schema version: $version"
        }

        val c = r.getJSONObject("customer")
        val b = r.getJSONObject("business")
        val arr = r.getJSONArray("items")

        return InvoiceSnapshot(
            schemaVersion = version,
            documentId = r.getString("documentId"),
            currencyCode = r.getString("currencyCode"),
            currencyDecimalPlaces = r.getInt("currencyDecimalPlaces"),
            customer = InvoiceCustomerSnapshot(
                title = c.optNullable("title"),
                name = c.getString("name"),
                phone = c.getString("phone"),
                address = c.optNullable("address")
            ),
            business = InvoiceBusinessSnapshot(
                name = b.optNullable("name"),
                registrationNumber = b.optNullable("registrationNumber"),
                taxId = b.optNullable("taxId"),
                address = b.optNullable("address"),
                phone = b.optNullable("phone")
            ),
            items = List(arr.length()) { idx ->
                val i = arr.getJSONObject(idx)
                InvoiceItemSnapshot(
                    id = i.getString("id"),
                    name = i.getString("name"),
                    description = i.optNullable("description"),
                    quantity = i.getInt("quantity"),
                    unitPriceMinor = i.getLong("unitPriceMinor"),
                    lineTotalMinor = i.getLong("lineTotalMinor"),
                    itemPackingMinor = i.optLong("itemPackingMinor", 0L),
                    itemShippingMinor = i.optLong("itemShippingMinor", 0L)
                )
            },
            subtotalMinor = r.getLong("subtotalMinor"),
            taxRateBps = r.getInt("taxRateBps"),
            taxAmountMinor = r.getLong("taxAmountMinor"),
            txShippingMinor = r.optLong("txShippingMinor", 0L),
            txPackingMinor = r.optLong("txPackingMinor", 0L),
            itemShippingTotalMinor = r.optLong("itemShippingTotalMinor", 0L),
            itemPackingTotalMinor = r.optLong("itemPackingTotalMinor", 0L),
            grandTotalMinor = r.getLong("grandTotalMinor"),
            note = r.optNullable("note"),
            createdAt = r.getLong("createdAt"),
            taxableBaseMinor = if (r.has("taxableBaseMinor") && !r.isNull("taxableBaseMinor")) {
                r.getLong("taxableBaseMinor")
            } else {
                null
            },
            taxRoundingMode = r.optNullable("taxRoundingMode")?.let(TaxRoundingMode::valueOf),
            taxBaseIncludesCharges = if (r.has("taxBaseIncludesCharges") && !r.isNull("taxBaseIncludesCharges")) {
                r.getBoolean("taxBaseIncludesCharges")
            } else {
                null
            }
        )
    }

    private fun JSONObject.optNullable(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
