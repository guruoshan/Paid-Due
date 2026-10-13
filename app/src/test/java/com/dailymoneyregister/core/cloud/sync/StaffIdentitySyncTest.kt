package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.CloudStaffIdentityDao
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.feature.admin.data.CloudCashierDirectoryEntry
import com.dailymoneyregister.feature.admin.data.EmployeeDirectoryCloudSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffIdentitySyncTest {
    @Test
    fun synchronizesScopedIdentityAndTombstoneWithoutCredentialFields() = runTest {
        val source = mockk<EmployeeDirectoryCloudSource>()
        val database = mockk<DmrDatabase>()
        val dao = mockk<CloudStaffIdentityDao>()
        val cashierMetadataSynchronizer = mockk<CashierMetadataSynchronizer>(relaxed = true)
        val captured = slot<List<CloudStaffIdentityEntity>>()
        every { database.cloudStaffIdentityDao() } returns dao
        coEvery { source.listCashiers() } returns listOf(
            CloudCashierDirectoryEntry(
                userId = "remote-cashier-user",
                membershipId = "remote-membership",
                branchId = "branch-a",
                displayName = "Cashier",
                canonicalPhone = "+96890000000",
                isActive = false,
                permissions = mapOf("CREATE_SALE" to false),
                isRemoved = true
            ),
            CloudCashierDirectoryEntry(
                userId = "other-branch-user",
                membershipId = "other-membership",
                branchId = "branch-b",
                displayName = "Other",
                canonicalPhone = "+96891111111",
                isActive = true,
                permissions = emptyMap(),
                isRemoved = false
            )
        )
        coEvery { dao.upsertAll(capture(captured)) } returns Unit

        DefaultStaffIdentitySync(source, database, cashierMetadataSynchronizer).synchronize("business-a", "branch-a")

        coVerify(exactly = 1) { dao.upsertAll(any()) }
        assertEquals(1, captured.captured.size)
        val identity = captured.captured.single()
        assertEquals("remote-cashier-user", identity.localUserId)
        assertEquals("remote-membership", identity.membershipId)
        assertEquals("business-a", identity.businessId)
        assertEquals("branch-a", identity.branchId)
        assertEquals("CASHIER", identity.role)
        assertFalse(identity.isActive)
        assertTrue(identity.isRemoved)
        assertTrue(identity.permissionsJson.contains("CREATE_SALE"))
        assertFalse(identity.permissionsJson.contains("CUSTOMER_DIRECTORY_ACCESS"))
        assertFalse(identity.permissionsJson.contains("credential"))
        assertFalse(identity.permissionsJson.contains("pin"))
    }
}
