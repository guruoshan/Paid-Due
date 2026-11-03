package com.dailymoneyregister.feature.auth.data

import com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRepositoryTest {

    @Test
    fun remoteBusinessRestorationResultSealedTypes() {
        val success = RemoteBusinessRestorationResult.Success(
            businessId = "b-muscatera",
            branchId = "br-main",
            userId = "u-admin"
        )

        assertEquals("b-muscatera", success.businessId)
        assertEquals("br-main", success.branchId)
        assertEquals("u-admin", success.userId)

        val failed = RemoteBusinessRestorationResult.Failed("Cloud connection timeout")
        assertEquals("Cloud connection timeout", failed.message)
    }
}
