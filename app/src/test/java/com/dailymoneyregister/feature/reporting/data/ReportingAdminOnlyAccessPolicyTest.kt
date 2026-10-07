package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.feature.admin.ui.CashierPermissionKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BOARDLOCK Reporting access policy.
 *
 * Reports are an administrator function.
 *
 * Cashiers use their operational Register / Day Closing flow and must never
 * gain access to Reports merely because an old persisted canViewReports value
 * happens to be true.
 *
 * The legacy field remains in persistence for compatibility; it is deliberately
 * non-authoritative for Report access.
 */
class ReportingAdminOnlyAccessPolicyTest {

    private fun user(
        id: String,
        role: UserRole
    ) =
        UserAccountEntity(
            id = id,
            identityId = null,
            businessId = "business-1",
            displayName =
                if (role == UserRole.ADMIN) {
                    "Admin"
                } else {
                    "Cashier"
                },
            username = id,
            role = role,
            authType =
                if (role == UserRole.ADMIN) {
                    AuthType.PASSWORD
                } else {
                    AuthType.PIN
                },
            credentialHash = "hash",
            credentialSalt = "salt",
            isActive = true,
            createdAt = 1L,
            updatedAt = 1L
        )

    private fun membership(
        userId: String,
        role: UserRole,
        legacyCanViewReports: Boolean
    ) =
        UserBranchMembershipEntity(
            id = "membership-$userId",
            userId = userId,
            branchId = "branch-1",
            role = role,
            canViewReports = legacyCanViewReports,
            createdAt = 1L
        )

    @Test
    fun adminCanViewReports() {

        val admin =
            user(
                id = "admin-1",
                role = UserRole.ADMIN
            )

        assertTrue(
            PermissionChecker.isPermitted(
                admin,
                membership(
                    userId = admin.id,
                    role = UserRole.ADMIN,
                    legacyCanViewReports = false
                ),
                PermissionAction.VIEW_REPORTS
            )
        )
    }

    @Test
    fun cashierWithoutLegacyFlagCannotViewReports() {

        val cashier =
            user(
                id = "cashier-1",
                role = UserRole.CASHIER
            )

        assertFalse(
            PermissionChecker.isPermitted(
                cashier,
                membership(
                    userId = cashier.id,
                    role = UserRole.CASHIER,
                    legacyCanViewReports = false
                ),
                PermissionAction.VIEW_REPORTS
            )
        )
    }

    @Test
    fun cashierStillCannotViewReportsWhenLegacyFlagIsTrue() {

        val cashier =
            user(
                id = "cashier-2",
                role = UserRole.CASHIER
            )

        assertFalse(
            PermissionChecker.isPermitted(
                cashier,
                membership(
                    userId = cashier.id,
                    role = UserRole.CASHIER,
                    legacyCanViewReports = true
                ),
                PermissionAction.VIEW_REPORTS
            )
        )
    }

    @Test
    fun cashierPermissionEditorDoesNotOfferViewReports() {

        assertFalse(
            CashierPermissionKey.editableValues.contains(
                CashierPermissionKey.VIEW_REPORTS
            )
        )
    }
}