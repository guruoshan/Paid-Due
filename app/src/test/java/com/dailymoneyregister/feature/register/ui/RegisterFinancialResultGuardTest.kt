package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterFinancialResultGuardTest {

    private val business = BusinessEntity(
        id = "business-1",
        name = "Business",
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun resultAppliesOnlyToSameFinancialSessionAndSameGeneration() {
        val branchA = branch("branch-a")
        val branchB = branch("branch-b")
        val userA = user("user-a")
        val userB = user("user-b")

        val executionSession = session(branchA, userA)
        val executionContext =
            executionSession.toRegisterFinancialSessionContext()

        assertTrue(
            shouldApplyFinancialResultToRegister(
                executionContext = executionContext,
                currentSession = session(branchA, userA),
                executionGeneration = 4L,
                currentGeneration = 4L
            )
        )

        assertFalse(
            shouldApplyFinancialResultToRegister(
                executionContext = executionContext,
                currentSession = session(branchB, userA),
                executionGeneration = 4L,
                currentGeneration = 5L
            )
        )

        assertFalse(
            shouldApplyFinancialResultToRegister(
                executionContext = executionContext,
                currentSession = session(branchA, userB),
                executionGeneration = 4L,
                currentGeneration = 5L
            )
        )

        assertFalse(
            shouldApplyFinancialResultToRegister(
                executionContext = executionContext,
                currentSession = null,
                executionGeneration = 4L,
                currentGeneration = 5L
            )
        )

        // A -> B -> A must still reject the old A operation result.
        assertFalse(
            shouldApplyFinancialResultToRegister(
                executionContext = executionContext,
                currentSession = session(branchA, userA),
                executionGeneration = 4L,
                currentGeneration = 6L
            )
        )
    }

    private fun session(
        branch: BranchEntity,
        user: UserAccountEntity
    ) = ActiveSession(
        business = business,
        branch = branch,
        user = user,
        membership = null
    )

    private fun user(id: String) = UserAccountEntity(
        id = id,
        businessId = business.id,
        displayName = id,
        username = id,
        role = UserRole.CASHIER,
        authType = AuthType.PIN,
        credentialHash = "hash",
        credentialSalt = "salt",
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun branch(id: String) = BranchEntity(
        id = id,
        businessId = business.id,
        name = id,
        code = id.uppercase(),
        countryCode = "OM",
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        currencySymbol = "OMR",
        timezoneId = "Asia/Muscat",
        createdAt = 1L,
        updatedAt = 1L
    )
}
