package com.dailymoneyregister.feature.register.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegisterPresentationStateTest {
    @Test
    fun `history back returns to register`() {
        assertEquals(
            RegisterDestination.Register,
            RegisterDestination.History.backDestination()
        )
    }

    @Test
    fun `details opened from register returns to register`() {
        assertEquals(
            RegisterDestination.Register,
            RegisterDestination.TransactionDetails("tx-1", DetailsOrigin.REGISTER).backDestination()
        )
        assertEquals(
            RegisterDestination.Register,
            RegisterDestination.ExpenseDetails("expense-1", DetailsOrigin.REGISTER).backDestination()
        )
    }

    @Test
    fun `details opened from history returns to history`() {
        assertEquals(
            RegisterDestination.History,
            RegisterDestination.TransactionDetails("tx-1", DetailsOrigin.HISTORY).backDestination()
        )
        assertEquals(
            RegisterDestination.History,
            RegisterDestination.ExpenseDetails("expense-1", DetailsOrigin.HISTORY).backDestination()
        )
    }

    @Test
    fun `one destination value represents every navigation state`() {
        val destination: RegisterDestination = RegisterDestination.History
        val replaced = RegisterDestination.TransactionDetails(
            transactionId = "tx-1",
            origin = destination.detailsOrigin()
        )

        assertEquals(DetailsOrigin.HISTORY, replaced.origin)
    }

    @Test
    fun `child overlay closes back to itemized workflow only when requested`() {
        assertEquals(
            RegisterOverlay.ItemizedBill,
            RegisterOverlay.AddItem(returnToItemizedBill = true).afterChildClosed()
        )
        assertNull(RegisterOverlay.AddItem(returnToItemizedBill = false).afterChildClosed())
    }
}
