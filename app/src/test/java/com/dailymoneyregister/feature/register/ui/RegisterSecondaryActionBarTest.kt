package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.feature.register.ui.components.RegisterSecondaryActionOrder
import com.dailymoneyregister.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RegisterSecondaryActionBarTest {
    @Test
    fun secondaryActionsKeepApprovedColumnOrder() {
        assertEquals(listOf(R.string.history, R.string.customer, R.string.clear, R.string.share), RegisterSecondaryActionOrder.labels)
    }
}
