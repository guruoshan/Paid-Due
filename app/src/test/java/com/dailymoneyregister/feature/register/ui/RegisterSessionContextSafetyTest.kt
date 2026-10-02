package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegisterSessionContextSafetyTest {

    private val business = BusinessEntity(
        id = "business-1",
        name = "Test Business",
        createdAt = 1L,
        updatedAt = 1L
    )

    private val user = UserAccountEntity(
        id = "user-1",
        businessId = business.id,
        displayName = "Test Cashier",
        username = "test_cashier",
        role = UserRole.CASHIER,
        authType = AuthType.PIN,
        credentialHash = "hash",
        credentialSalt = "salt",
        createdAt = 1L,
        updatedAt = 1L
    )

    @Before
    fun setUp() {
        SessionManager.clearSession()
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
    }

    @Test
    fun sessionManagerExposesBranchChangesAndClearImmediately() {
        val branchA = branch(
            id = "branch-a",
            currencyCode = "OMR",
            decimalPlaces = 3
        )
        val branchB = branch(
            id = "branch-b",
            currencyCode = "INR",
            decimalPlaces = 2
        )

        SessionManager.setSession(
            ActiveSession(
                business = business,
                branch = branchA,
                user = user,
                membership = null
            )
        )

        assertEquals(
            "branch-a",
            SessionManager.currentSession.value?.branch?.id
        )

        SessionManager.setSession(
            ActiveSession(
                business = business,
                branch = branchB,
                user = user,
                membership = null
            )
        )

        assertEquals(
            "branch-b",
            SessionManager.currentSession.value?.branch?.id
        )

        SessionManager.clearSession()

        assertNull(SessionManager.currentSession.value)
    }

    @Test
    fun clearedRegisterDraftUsesBranchDefaultsWithoutCarryingUnsavedMoney() {
        val branch = branch(
            id = "branch-b",
            currencyCode = "INR",
            decimalPlaces = 2,
            defaultTaxRateBps = 725,
            taxRoundingMode = TaxRoundingMode.FLOOR
        )

        val cleared = clearedRegisterDraft(branch)

        assertEquals(0, cleared.taxRateBps)
        assertEquals(TaxRoundingMode.FLOOR, cleared.taxRoundingMode)
        assertFalse(cleared.isTaxEnabled)

        assertEquals(MoneyMinorUnits.ZERO, cleared.manualAmount)
        assertTrue(cleared.items.isEmpty())
        assertNull(cleared.manualDescription)
        assertEquals(MoneyMinorUnits.ZERO, cleared.txShipping)
        assertEquals(MoneyMinorUnits.ZERO, cleared.txPacking)
        assertEquals(MoneyMinorUnits.ZERO, cleared.itemShippingTotal)
        assertEquals(MoneyMinorUnits.ZERO, cleared.itemPackingTotal)
        assertEquals(MoneyMinorUnits.ZERO, cleared.grandTotal)
    }

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
