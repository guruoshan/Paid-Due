package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RegisterDraftActionsTest {
    @Test
    fun registerUiStateStartsWithRecentTransactionsCollapsed() {
        assertFalse(RegisterUiState().isRecentTransactionsExpanded)
    }

    @Test
    fun clearDraftStateContainsNoUnsavedFinancialValues() {
        val cleared = clearedRegisterDraft(null)

        assertEquals(MoneyMinorUnits.ZERO, cleared.manualAmount)
        assertEquals(emptyList<DraftItem>(), cleared.items)
        assertFalse(cleared.isTaxEnabled)
        assertEquals(MoneyMinorUnits.ZERO, cleared.txShipping)
        assertEquals(MoneyMinorUnits.ZERO, cleared.txPacking)
        assertEquals(MoneyMinorUnits.ZERO, cleared.grandTotal)
    }
}
