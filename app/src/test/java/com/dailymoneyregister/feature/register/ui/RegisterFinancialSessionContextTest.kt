package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegisterFinancialSessionContextTest {

    private val business = BusinessEntity(
        id = "business-1",
        name = "Business",
        createdAt = 1L,
        updatedAt = 1L
    )

    private val user = user("user-1")

    @Test
    fun nullSessionHasNoFinancialRegisterContext() {
        val session: ActiveSession? = null

        assertNull(session.toRegisterFinancialSessionContext())
    }

    @Test
    fun sameFinancialSessionProducesSameContext() {
        val branch = branch(
            id = "branch-a",
            currencyCode = "OMR",
            decimalPlaces = 3
        )

        val first = session(branch, user)
            .toRegisterFinancialSessionContext()

        val second = session(branch.copy(), user.copy())
            .toRegisterFinancialSessionContext()

        assertEquals(first, second)
    }

    @Test
    fun branchOrCashierOrFinancialConfigurationChangesContext() {
        val originalBranch = branch(
            id = "branch-a",
            currencyCode = "OMR",
            decimalPlaces = 3,
            defaultTaxRateBps = 500,
            taxRoundingMode = TaxRoundingMode.HALF_UP
        )

        val original =
            session(originalBranch, user)
                .toRegisterFinancialSessionContext()

        assertNotEquals(
            original,
            session(
                originalBranch.copy(id = "branch-b"),
                user
            ).toRegisterFinancialSessionContext()
        )

        assertNotEquals(
            original,
            session(
                originalBranch,
                user("user-2")
            ).toRegisterFinancialSessionContext()
        )

        assertNotEquals(
            original,
            session(
                originalBranch.copy(
                    currencyCode = "INR",
                    currencyDecimalPlaces = 2
                ),
                user
            ).toRegisterFinancialSessionContext()
        )

        assertNotEquals(
            original,
            session(
                originalBranch.copy(
                    defaultTaxRateBps = 725
                ),
                user
            ).toRegisterFinancialSessionContext()
        )

        assertNotEquals(
            original,
            session(
                originalBranch.copy(
                    taxRoundingMode = TaxRoundingMode.FLOOR
                ),
                user
            ).toRegisterFinancialSessionContext()
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

    private fun branch(
        id: String,
        currencyCode: String,
        decimalPlaces: Int,
        defaultTaxRateBps: Int = 0,
        taxRoundingMode: TaxRoundingMode = TaxRoundingMode.HALF_UP
    ) = BranchEntity(
        id = id,
        businessId = business.id,
        name = id,
        code = id.uppercase(),
        countryCode = "OM",
        currencyCode = currencyCode,
        currencyDecimalPlaces = decimalPlaces,
        currencySymbol = currencyCode,
        timezoneId = "Asia/Muscat",
        taxRoundingMode = taxRoundingMode,
        defaultTaxRateBps = defaultTaxRateBps,
        createdAt = 1L,
        updatedAt = 1L
    )
}
