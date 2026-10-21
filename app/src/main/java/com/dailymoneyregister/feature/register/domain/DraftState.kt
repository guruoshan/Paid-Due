package com.dailymoneyregister.feature.register.domain

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxPolicyRegistry
import com.dailymoneyregister.core.model.tax.TaxRoundingMode

data class DraftItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val unitPrice: MoneyMinorUnits,
    val quantity: Int = 1,
    val barcode: String? = null,
    val itemPackingCharge: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val itemShippingCharge: MoneyMinorUnits = MoneyMinorUnits.ZERO
) {
    val lineTotal: MoneyMinorUnits
        get() = unitPrice * quantity.toLong()

    val totalPackingCharge: MoneyMinorUnits
        get() = itemPackingCharge * quantity.toLong()

    val totalShippingCharge: MoneyMinorUnits
        get() = itemShippingCharge * quantity.toLong()
}

data class DraftState(
    val items: List<DraftItem> = emptyList(),
    val manualAmount: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val manualDescription: String? = null,
    val taxRateBps: Int = 0, // e.g. 500 = 5.00%
    val isTaxEnabled: Boolean = false,
    val taxRoundingMode: TaxRoundingMode = TaxRoundingMode.HALF_UP,
    val txShipping: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val txPacking: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    /**
     * Explicit persisted base for durable payload replay. Missing values in legacy
     * payloads are decoded as subtotal-only so recovery never reprices an old operation.
     */
    val taxableBaseOverride: MoneyMinorUnits? = null
) {
    val isItemized: Boolean get() = items.isNotEmpty()

    val subtotal: MoneyMinorUnits
        get() = if (isItemized) {
            items.fold(MoneyMinorUnits.ZERO) { acc, item -> acc + item.lineTotal }
        } else {
            manualAmount
        }

    val itemShippingTotal: MoneyMinorUnits
        get() = items.fold(MoneyMinorUnits.ZERO) { acc, item -> acc + item.totalShippingCharge }

    val itemPackingTotal: MoneyMinorUnits
        get() = items.fold(MoneyMinorUnits.ZERO) { acc, item -> acc + item.totalPackingCharge }

    /**
     * Oman VAT taxable value includes incidental packing/shipping charges charged by
     * the supplier. Legacy durable payloads may provide a subtotal-only override so
     * replay uses the exact historical calculation rather than repricing a pending sale.
     */
    val taxableBase: MoneyMinorUnits
        get() = taxableBaseOverride
            ?: (subtotal + txShipping + txPacking + itemShippingTotal + itemPackingTotal)

    val taxAmount: MoneyMinorUnits
        get() = if (isTaxEnabled && taxRateBps > 0) {
            val policy = TaxPolicyRegistry.getPolicy(taxRoundingMode)
            policy.calculateTax(taxableBase, taxRateBps)
        } else {
            MoneyMinorUnits.ZERO
        }

    val grandTotal: MoneyMinorUnits
        get() = subtotal + taxAmount + txShipping + txPacking + itemShippingTotal + itemPackingTotal
}
