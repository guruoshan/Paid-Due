package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession

internal data class RegisterFinancialSessionContext(
    val businessId: String,
    val branchId: String,
    val userId: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val defaultTaxRateBps: Int,
    val taxRoundingMode: TaxRoundingMode
)

internal fun ActiveSession?.toRegisterFinancialSessionContext():
    RegisterFinancialSessionContext? =
    this?.let { session ->
        val branch = session.branch

        RegisterFinancialSessionContext(
            businessId = session.business.id,
            branchId = branch.id,
            userId = session.user.id,
            currencyCode = branch.currencyCode,
            currencyDecimalPlaces = branch.currencyDecimalPlaces,
            defaultTaxRateBps = branch.defaultTaxRateBps,
            taxRoundingMode = branch.taxRoundingMode
        )
    }
