package com.dailymoneyregister.feature.invoice.domain

import com.dailymoneyregister.feature.register.domain.DraftState

/**
 * Immutable invoice content, frozen at issue time and stored in
 * documents.snapshot_json.
 *
 * An invoice is a NON-FINANCIAL document. Issuing one never creates a
 * transaction, settlement, credit, coupon movement or ledger entry.
 * All money is in minor units (same as the rest of the app).
 */
data class InvoiceCustomerSnapshot(
    val title: String? = null,
    val name: String,
    val phone: String,
    val address: String? = null
)

data class InvoiceBusinessSnapshot(
    val name: String? = null,
    val registrationNumber: String? = null,
    val taxId: String? = null,
    val address: String? = null,
    val phone: String? = null
)

data class InvoiceItemSnapshot(
    val id: String,
    val name: String,
    val description: String? = null,
    val quantity: Int,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    /** Per-unit charges, exactly like DraftItem. */
    val itemPackingMinor: Long = 0L,
    val itemShippingMinor: Long = 0L
)

data class InvoiceSnapshot(
    val schemaVersion: Int = InvoiceSnapshotCodec.CURRENT_SCHEMA_VERSION,
    val documentId: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val customer: InvoiceCustomerSnapshot,
    val business: InvoiceBusinessSnapshot,
    val items: List<InvoiceItemSnapshot>,
    val subtotalMinor: Long,
    val taxRateBps: Int,
    val taxAmountMinor: Long,
    val txShippingMinor: Long,
    val txPackingMinor: Long,
    val itemShippingTotalMinor: Long,
    val itemPackingTotalMinor: Long,
    val grandTotalMinor: Long,
    val note: String? = null,
    val createdAt: Long
) {
    companion object {
        /**
         * Freezes the current register draft into an invoice snapshot.
         * Uses the DraftState math as the single source of truth.
         */
        fun fromDraft(
            documentId: String,
            draft: DraftState,
            customer: InvoiceCustomerSnapshot,
            business: InvoiceBusinessSnapshot,
            currencyCode: String,
            currencyDecimalPlaces: Int,
            note: String?,
            createdAt: Long
        ): InvoiceSnapshot = InvoiceSnapshot(
            documentId = documentId,
            currencyCode = currencyCode,
            currencyDecimalPlaces = currencyDecimalPlaces,
            customer = customer,
            business = business,
            items = draft.items.map {
                InvoiceItemSnapshot(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    quantity = it.quantity,
                    unitPriceMinor = it.unitPrice.amount,
                    lineTotalMinor = it.lineTotal.amount,
                    itemPackingMinor = it.itemPackingCharge.amount,
                    itemShippingMinor = it.itemShippingCharge.amount
                )
            },
            subtotalMinor = draft.subtotal.amount,
            taxRateBps = if (draft.isTaxEnabled) draft.taxRateBps else 0,
            taxAmountMinor = draft.taxAmount.amount,
            txShippingMinor = draft.txShipping.amount,
            txPackingMinor = draft.txPacking.amount,
            itemShippingTotalMinor = draft.itemShippingTotal.amount,
            itemPackingTotalMinor = draft.itemPackingTotal.amount,
            grandTotalMinor = draft.grandTotal.amount,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = createdAt
        )
    }
}

/**
 * Returns null when the snapshot is internally consistent,
 * otherwise a short reason. Pure function, no I/O.
 */
fun InvoiceSnapshot.validationError(): String? {
    if (documentId.isBlank()) return "Invoice document id is required"
    if (customer.name.isBlank()) return "Invoice customer name is required"
    if (customer.phone.isBlank()) return "Invoice customer phone is required"
    if (items.isEmpty()) return "Invoice must contain at least one item"
    if (items.map { it.id }.toSet().size != items.size) return "Invoice item ids must be unique"
    if (taxRateBps !in 0..10_000) return "Invoice tax rate is invalid"
    if (taxRateBps == 0 && taxAmountMinor != 0L) return "Invoice tax amount requires a tax rate"

    val nonNegative = listOf(
        subtotalMinor, taxAmountMinor, txShippingMinor, txPackingMinor,
        itemShippingTotalMinor, itemPackingTotalMinor, grandTotalMinor
    )
    if (nonNegative.any { it < 0L }) return "Invoice amounts cannot be negative"

    return try {
        var lineSum = 0L
        var packSum = 0L
        var shipSum = 0L
        for (item in items) {
            if (item.name.isBlank()) return "Invoice item name is required"
            if (item.quantity <= 0) return "Invoice item quantity must be positive"
            if (item.unitPriceMinor < 0L || item.itemPackingMinor < 0L || item.itemShippingMinor < 0L) {
                return "Invoice item amounts cannot be negative"
            }
            val expectedLine = Math.multiplyExact(item.unitPriceMinor, item.quantity.toLong())
            if (item.lineTotalMinor != expectedLine) return "Invoice item line total is inconsistent"
            lineSum = Math.addExact(lineSum, item.lineTotalMinor)
            packSum = Math.addExact(packSum, Math.multiplyExact(item.itemPackingMinor, item.quantity.toLong()))
            shipSum = Math.addExact(shipSum, Math.multiplyExact(item.itemShippingMinor, item.quantity.toLong()))
        }
        if (lineSum != subtotalMinor) return "Invoice subtotal is inconsistent"
        if (packSum != itemPackingTotalMinor) return "Invoice item packing total is inconsistent"
        if (shipSum != itemShippingTotalMinor) return "Invoice item shipping total is inconsistent"

        val expectedGrand = listOf(
            subtotalMinor, taxAmountMinor, txShippingMinor, txPackingMinor,
            itemShippingTotalMinor, itemPackingTotalMinor
        ).fold(0L) { acc, v -> Math.addExact(acc, v) }
        if (expectedGrand != grandTotalMinor) return "Invoice grand total is inconsistent"
        if (grandTotalMinor <= 0L) return "Invoice grand total must be greater than zero"
        null
    } catch (_: ArithmeticException) {
        "Invoice amounts overflow"
    }
}
