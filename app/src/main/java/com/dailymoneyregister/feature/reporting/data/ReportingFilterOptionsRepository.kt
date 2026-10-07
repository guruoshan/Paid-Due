package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.database.DmrDatabase
import javax.inject.Inject

data class ReportingBranchOption(
    val id: String,
    val name: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val currencySymbol: String,
    val timezoneId: String
)

data class ReportingCashierOption(
    val id: String,
    val displayName: String
)

data class ReportingFilterOptions(
    val branches: List<ReportingBranchOption>,
    val cashiers: List<ReportingCashierOption>
)

class ReportingFilterOptionsRepository @Inject constructor(
    private val database: DmrDatabase
) {
    suspend fun load(
        businessId: String,
        currencyCode: String
    ): ReportingFilterOptions {

        val branches =
            database.businessBranchDao()
                .getBranchesByBusinessId(businessId)
                .filter {
                    it.currencyCode == currencyCode
                }
                .sortedBy {
                    it.name.lowercase()
                }
                .map {
                    ReportingBranchOption(
                        id = it.id,
                        name = it.name,
                        currencyCode = it.currencyCode,
                        currencyDecimalPlaces =
                            it.currencyDecimalPlaces,
                        currencySymbol =
                            it.currencySymbol,
                        timezoneId =
                            it.timezoneId
                    )
                }

        val cashiers =
            database.userDao()
                .getCashiers(businessId)
                .filter {
                    it.isActive
                }
                .sortedBy {
                    it.displayName.lowercase()
                }
                .map {
                    ReportingCashierOption(
                        id = it.id,
                        displayName = it.displayName
                    )
                }

        return ReportingFilterOptions(
            branches = branches,
            cashiers = cashiers
        )
    }
}