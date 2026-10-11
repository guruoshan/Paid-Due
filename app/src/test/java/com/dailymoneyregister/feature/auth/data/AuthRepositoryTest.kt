package com.dailymoneyregister.feature.auth.data

import com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult
import com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome
import com.dailymoneyregister.core.cloud.sync.classifyCashierCloudHttpFailure
import com.dailymoneyregister.core.security.PinHasher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {

    @Test
    fun cashierCloudAuthOutcomesDistinguishServerRejectionFromUnavailableServer() {
        assertEquals(CashierCloudAuthOutcome.REJECTED, classifyCashierCloudHttpFailure(401))
        assertEquals(CashierCloudAuthOutcome.REJECTED, classifyCashierCloudHttpFailure(403))
        assertEquals(CashierCloudAuthOutcome.UNAVAILABLE, classifyCashierCloudHttpFailure(429))
        assertEquals(CashierCloudAuthOutcome.UNAVAILABLE, classifyCashierCloudHttpFailure(503))
    }

    @Test
    fun pinHasherValidatesFormatAndHash() {
        val pin = "123456"
        assertTrue(PinHasher.isValidPin(pin))
        assertFalse(PinHasher.isValidPin("12345"))
        assertFalse(PinHasher.isValidPin("abcdef"))

        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hashPin(pin, salt)

        assertTrue(PinHasher.verifyPin(pin, salt, hash))
        assertFalse(PinHasher.verifyPin("654321", salt, hash))
    }

    @Test
    fun remoteBusinessRestorationResultSealedTypes() {
        val success = RemoteBusinessRestorationResult.Success(
            businessId = "b-muscatera",
            branchId = "br-main",
            userId = "u-cashier"
        )

        assertEquals("b-muscatera", success.businessId)
        assertEquals("br-main", success.branchId)
        assertEquals("u-cashier", success.userId)

        val failed = RemoteBusinessRestorationResult.Failed("Cloud connection timeout")
        assertEquals("Cloud connection timeout", failed.message)
    }

    @Test
    fun cashierRestorationInvalidPinFailsImmediately() {
        val invalidPin = "1234"
        assertFalse(PinHasher.isValidPin(invalidPin))
    }

    @Test
    fun cashierRestorationPinFormattingEnforcesExactSixDigits() {
        assertTrue(PinHasher.isValidPin("000000"))
        assertTrue(PinHasher.isValidPin("987654"))
        assertFalse(PinHasher.isValidPin("12345"))
        assertFalse(PinHasher.isValidPin("1234567"))
        assertFalse(PinHasher.isValidPin("12a456"))
    }
}
