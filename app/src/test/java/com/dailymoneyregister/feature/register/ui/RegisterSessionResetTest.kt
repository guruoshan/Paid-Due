package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.DraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterSessionResetTest {

    @Test
    fun financialSessionChangeClearsUnsavedRegisterStateAndUsesNewBranchDefaults() {
        val current = RegisterUiState(
            calculatorState = CalculatorState(
                expression = "10 + 5",
                currentInput = "5",
                evaluatedAmount = MoneyMinorUnits(15000L)
            ),
            draftState = DraftState(
                manualAmount = MoneyMinorUnits(15000L),
                manualDescription = "Branch A unsaved sale",
                taxRateBps = 500,
                isTaxEnabled = true,
                txShipping = MoneyMinorUnits(1000L),
                txPacking = MoneyMinorUnits(500L)
            ),
            manualDescription = "Branch A unsaved sale",
            isTaxDialogOpen = true,
            isShippingPackingDialogOpen = true,
            isRecentTransactionsExpanded = true
        )

        val reset = resetRegisterUiStateForSession(
            current,
            session(
                branch(
                    id = "branch-b",
                    defaultTaxRateBps = 725,
                    taxRoundingMode = TaxRoundingMode.FLOOR
                )
            )
        )

        assertEquals(CalculatorState(), reset.calculatorState)

        assertEquals(MoneyMinorUnits.ZERO, reset.draftState.manualAmount)
        assertTrue(reset.draftState.items.isEmpty())
        assertNull(reset.draftState.manualDescription)
        assertEquals(MoneyMinorUnits.ZERO, reset.draftState.txShipping)
        assertEquals(MoneyMinorUnits.ZERO, reset.draftState.txPacking)
        assertEquals(MoneyMinorUnits.ZERO, reset.draftState.grandTotal)

        assertEquals(0, reset.draftState.taxRateBps)
        assertEquals(TaxRoundingMode.FLOOR, reset.draftState.taxRoundingMode)
        assertFalse(reset.draftState.isTaxEnabled)

        assertEquals("", reset.manualDescription)
        assertFalse(reset.isTaxDialogOpen)
        assertFalse(reset.isShippingPackingDialogOpen)
        assertFalse(reset.isItemizedBillDialogOpen)
        assertFalse(reset.isAddItemDialogOpen)

        assertNull(reset.inFlightSaleCommand)
        assertNull(reset.lastCompletedTransaction)
        assertFalse(reset.isExecuting)
        assertNull(reset.errorMessage)
        assertNull(reset.infoMessage)

        assertTrue(reset.isRecentTransactionsExpanded)
    }

    private fun session(branch: BranchEntity): ActiveSession {
        val business = BusinessEntity(
            id = "business-1",
            name = "Business",
            createdAt = 1L,
            updatedAt = 1L
        )

        val user = UserAccountEntity(
            id = "user-1",
            businessId = business.id,
            displayName = "Cashier",
            username = "cashier",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 1L,
            updatedAt = 1L
        )

        return ActiveSession(
            business = business,
            branch = branch,
            user = user,
            membership = null
        )
    }

    private fun branch(
        id: String,
        defaultTaxRateBps: Int,
        taxRoundingMode: TaxRoundingMode
    ) = BranchEntity(
        id = id,
        businessId = "business-1",
        name = id,
        code = id.uppercase(),
        countryCode = "OM",
        currencyCode = "INR",
        currencyDecimalPlaces = 2,
        currencySymbol = "INR",
        timezoneId = "Asia/Kolkata",
        taxRoundingMode = taxRoundingMode,
        defaultTaxRateBps = defaultTaxRateBps,
        createdAt = 1L,
        updatedAt = 1L
    )
}
