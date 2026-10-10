package com.dailymoneyregister.feature.admin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CashierDirectoryMergeTest {
    @Test fun `cloud directory rejects duplicate stable user ids before presentation`() {
        val rows = listOf(
            CloudCashierDirectoryEntry("user-1", "membership-1", "branch-1", "A", "+9681", true),
            CloudCashierDirectoryEntry("user-1", "membership-2", "branch-2", "A", "+9681", true)
        )
        assertEquals(1, rows.distinctBy { it.userId }.size)
    }
}
