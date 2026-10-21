package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftState

/**
 * Keeps durable command replay stable across the VAT taxable-base correction.
 *
 * Payloads written before taxableBaseMinor existed are historical records: their
 * tax was calculated on subtotal only, so replay must preserve that original base.
 * New payloads persist the base explicitly and must match subtotal plus all charges.
 */
internal fun DraftState.withPersistedTaxableBase(payload: Map<String, String>): DraftState {
    val hasPersistedBase = payload.containsKey("taxableBaseMinor")
    val persistedBase = if (hasPersistedBase) {
        payload["taxableBaseMinor"]?.toLongOrNull()
            ?: error("Persisted taxableBaseMinor is missing or invalid.")
    } else {
        null
    }

    val expectedBase = if (hasPersistedBase) {
        subtotal + txShipping + txPacking + itemShippingTotal + itemPackingTotal
    } else {
        subtotal
    }
    val resolvedBase = persistedBase?.let(::MoneyMinorUnits) ?: subtotal

    require(resolvedBase == expectedBase) {
        "Persisted taxableBaseMinor does not match the payload amounts."
    }

    return copy(taxableBaseOverride = resolvedBase)
}
