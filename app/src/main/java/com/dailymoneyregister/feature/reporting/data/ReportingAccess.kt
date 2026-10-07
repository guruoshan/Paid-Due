package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.feature.reporting.domain.ReportingScope

enum class ReportingDenyReason {
    NO_ACTIVE_SESSION,
    NOT_PERMITTED,
    BUSINESS_MISMATCH,
    BRANCH_NOT_IN_BUSINESS,
    NO_BRANCH_IN_SCOPE,
    CURRENCY_MISMATCH,
    UNKNOWN_CASHIER
}

class ReportingAccessDeniedException(
    val reason: ReportingDenyReason
) : SecurityException("Reporting access denied: $reason")

/**
 * Raised when authoritative evidence is inconsistent or unsupported.
 * Reporting fails closed rather than showing a wrong number.
 */
class ReportingIntegrityException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Validated, server-of-truth scope for one Reporting read.
 */
data class ReportingAccess(
    val businessId: String,
    val branchIds: List<String>,
    val branchesById: Map<String, BranchEntity>,
    val currencyCode: String
)

/**
 * Validates a requested ReportingScope against the authenticated session.
 *
 * Policy (matches PermissionChecker + PermissionCheckerTest):
 *  - Reports are Admin-only. VIEW_REPORTS is false for every cashier,
 *    regardless of the legacy canViewReports flag.
 *  - The scope business must equal the session business.
 *  - A requested branch must belong to that business.
 *  - Every selected branch must use the requested reporting currency.
 *  - When no branch is requested, ALL business branches with the requested
 *    currency are included. Branches in other currencies are excluded, never
 *    converted or mixed.
 *  - A requested cashier filter must be a user of the same business.
 */
object ReportingAccessValidator {

    fun validate(
        session: ActiveSession?,
        scope: ReportingScope,
        businessBranches: List<BranchEntity>,
        requestedCashierKnownInBusiness: Boolean?
    ): ReportingAccess {

        val active = session ?: deny(ReportingDenyReason.NO_ACTIVE_SESSION)

        if (!PermissionChecker.isPermitted(active, PermissionAction.VIEW_REPORTS)) {
            deny(ReportingDenyReason.NOT_PERMITTED)
        }

        if (scope.businessId != active.business.id) {
            deny(ReportingDenyReason.BUSINESS_MISMATCH)
        }

        val candidates = businessBranches.filter {
            it.businessId == scope.businessId
        }

        val requestedBranchId = scope.branchId

        val selected: List<BranchEntity> =
            if (requestedBranchId != null) {

                val branch = candidates.firstOrNull { it.id == requestedBranchId }
                    ?: deny(ReportingDenyReason.BRANCH_NOT_IN_BUSINESS)

                if (branch.currencyCode != scope.currencyCode) {
                    deny(ReportingDenyReason.CURRENCY_MISMATCH)
                }

                listOf(branch)
            } else {
                candidates.filter { it.currencyCode == scope.currencyCode }
            }

        if (selected.isEmpty()) {
            deny(ReportingDenyReason.NO_BRANCH_IN_SCOPE)
        }

        if (scope.cashierUserId != null && requestedCashierKnownInBusiness != true) {
            deny(ReportingDenyReason.UNKNOWN_CASHIER)
        }

        return ReportingAccess(
            businessId = scope.businessId,
            branchIds = selected.map { it.id },
            branchesById = selected.associateBy { it.id },
            currencyCode = scope.currencyCode
        )
    }

    private fun deny(reason: ReportingDenyReason): Nothing =
        throw ReportingAccessDeniedException(reason)
}