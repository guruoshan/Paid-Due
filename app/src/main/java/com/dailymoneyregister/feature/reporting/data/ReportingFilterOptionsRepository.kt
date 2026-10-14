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
        currencyCode: String,
        branchId: String? = null
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

        val localCashiers = if (branchId == null) {
            database.userDao().getCashiers(businessId).filter { it.isActive }
        } else {
            database.userDao().getCashiersForBranch(businessId, branchId)
        }
        val cashiersById = localCashiers
            .associate { it.id to ReportingCashierOption(it.id, it.displayName) }
            .toMutableMap()

        val identityBranchIds = if (branchId == null) {
            branches.map { it.id }
        } else {
            listOf(branchId)
        }
        val cloudIdentities = identityBranchIds.flatMap { identityBranchId ->
            database.cloudStaffIdentityDao().listForBranch(businessId, identityBranchId)
        }
        cloudIdentities.groupBy { it.localUserId }.forEach { (localUserId, identities) ->
            val activeIdentity = identities.firstOrNull {
                it.role == "CASHIER" && it.isActive && !it.isRemoved
            }
            if (activeIdentity == null) {
                // A synchronized tombstone overrides a stale local cashier row.
                cashiersById.remove(localUserId)
            } else {
                cashiersById[localUserId] = ReportingCashierOption(
                    id = localUserId,
                    displayName = activeIdentity.displayName.ifBlank {
                        cashiersById[localUserId]?.displayName ?: localUserId
                    }
                )
            }
        }

        val cashiers = cashiersById.values.sortedBy { it.displayName.lowercase() }

        return ReportingFilterOptions(
            branches = branches,
            cashiers = cashiers
        )
    }
}