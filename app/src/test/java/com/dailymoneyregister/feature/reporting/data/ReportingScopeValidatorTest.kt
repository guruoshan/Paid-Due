package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriod
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReportingScopeValidatorTest {

    private val business =
        BusinessEntity(id = "business", name = "Biz", createdAt = 0L, updatedAt = 0L)

    private fun branch(id: String, currency: String, businessId: String = "business") =
        BranchEntity(
            id = id,
            businessId = businessId,
            name = "Branch $id",
            code = id.uppercase(),
            countryCode = "OM",
            currencyCode = currency,
            currencyDecimalPlaces = 3,
            currencySymbol = currency,
            timezoneId = "Asia/Muscat",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val b1 = branch("b1", "OMR")
    private val b2 = branch("b2", "OMR")
    private val b3 = branch("b3", "INR")
    private val foreign = branch("b9", "OMR", businessId = "other")

    private val cashier =
        UserAccountEntity(
            id = "cashier",
            businessId = "business",
            displayName = "Cashier",
            username = "cashier",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val admin = cashier.copy(id = "admin", username = "admin", role = UserRole.ADMIN, authType = AuthType.PASSWORD)

    private fun session(requiresPinChange: Boolean = false, businessOverride: BusinessEntity = business) =
        ActiveSession(
            business = businessOverride,
            branch = b1,
            user = admin,
            membership = null,
            requiresPinChange = requiresPinChange
        )

    private fun scope(
        businessId: String = "business",
        branchId: String? = null,
        currency: String = "OMR"
    ) = ReportingScope(
        businessId = businessId,
        branchId = branchId,
        currencyCode = currency,
        period = ReportingPeriod(fromInclusive = 100L, toExclusive = 200L)
    )

    private val allBranches = listOf(b1, b2, b3, foreign)

    private fun assertDenied(expected: ReportingDenyReason, block: () -> Unit) {
        try {
            block()
            fail("Expected ReportingAccessDeniedException($expected)")
        } catch (e: ReportingAccessDeniedException) {
            assertEquals(expected, e.reason)
        }
    }

    @Test
    fun noSessionIsDenied() {
        assertDenied(ReportingDenyReason.NO_ACTIVE_SESSION) {
            ReportingAccessValidator.validate(null, scope(), allBranches)
        }
    }

    @Test
    fun cashierIsDeniedBecauseReportsAreAdminOnly() {
        val cashierSession = ActiveSession(
            business = business,
            branch = b1,
            user = cashier,
            membership = null
        )

        assertDenied(ReportingDenyReason.NOT_PERMITTED) {
            ReportingAccessValidator.validate(cashierSession, scope(), allBranches)
        }
    }

    @Test
    fun adminWhoMustChangePinIsDenied() {
        assertDenied(ReportingDenyReason.NOT_PERMITTED) {
            ReportingAccessValidator.validate(
                session(requiresPinChange = true),
                scope(),
                allBranches
            )
        }
    }

    @Test
    fun scopeForAnotherBusinessIsDenied() {
        assertDenied(ReportingDenyReason.BUSINESS_MISMATCH) {
            ReportingAccessValidator.validate(
                session(),
                scope(businessId = "other"),
                allBranches,
                null
            )
        }
    }

    @Test
    fun branchOfAnotherBusinessIsDenied() {
        assertDenied(ReportingDenyReason.BRANCH_NOT_IN_BUSINESS) {
            ReportingAccessValidator.validate(
                session(),
                scope(branchId = "b9"),
                allBranches,
                null
            )
        }
    }

    @Test
    fun singleBranchWithDifferentCurrencyIsDenied() {
        assertDenied(ReportingDenyReason.CURRENCY_MISMATCH) {
            ReportingAccessValidator.validate(
                session(),
                scope(branchId = "b3", currency = "OMR"),
                allBranches,
                null
            )
        }
    }

    @Test
    fun allBranchesSelectsOnlyMatchingCurrencyOfThisBusiness() {
        val access = ReportingAccessValidator.validate(
            session(),
            scope(),
            allBranches,
            null
        )

        assertEquals(listOf("b1", "b2"), access.branchIds)
        assertEquals("OMR", access.currencyCode)
    }

    @Test
    fun noBranchWithRequestedCurrencyIsDenied() {
        assertDenied(ReportingDenyReason.NO_BRANCH_IN_SCOPE) {
            ReportingAccessValidator.validate(
                session(),
                scope(currency = "USD"),
                allBranches,
                null
            )
        }
    }


}