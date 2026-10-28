package com.dailymoneyregister.core.security

import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuthTest {

    @Test
    fun testCashierPinHashingAndVerification() {
        val pin = "123456"
        val salt = PinHasher.generateSalt()

        val hash =
            PinHasher.hashPin(
                pin,
                salt
            )

        assertNotEquals(pin, hash)
        assertTrue(hash.startsWith("pbkdf2-sha256:600000:"))

        assertTrue(
            PinHasher.verifyPin(
                pin,
                salt,
                hash
            )
        )

        assertFalse(
            PinHasher.verifyPin(
                "654321",
                salt,
                hash
            )
        )

        assertFalse(
            PinHasher.verifyPin(
                "000000",
                salt,
                hash
            )
        )

        assertFalse(
            PinHasher.isValidPin(
                "12345"
            )
        )

        assertFalse(
            PinHasher.isValidPin(
                "1234567"
            )
        )

        assertFalse(
            PinHasher.isValidPin(
                "12345a"
            )
        )
    }

    @Test
    fun testMalformedPinCredentialFailsClosed() {
        assertFalse(
            PinHasher.verifyPin(
                "123456",
                "bad-salt",
                "bad-hash"
            )
        )
    }

    @Test
    fun testAdminPasswordHashingAndVerification() {
        val password =
            "StrongAdminPass123"

        val salt =
            PasswordHasher.generateSalt()

        val hash =
            PasswordHasher.hashPassword(
                password,
                salt
            )

        assertNotEquals(
            password,
            hash
        )

        assertTrue(
            PasswordHasher.verifyPassword(
                password,
                salt,
                hash
            )
        )

        assertFalse(
            PasswordHasher.verifyPassword(
                "WrongPassword",
                salt,
                hash
            )
        )
    }

    @Test
    fun testShortOrMalformedPasswordFailsClosed() {
        val salt =
            PasswordHasher.generateSalt()

        val hash =
            PasswordHasher.hashPassword(
                "StrongAdminPass123",
                salt
            )

        assertFalse(
            PasswordHasher.verifyPassword(
                "x",
                salt,
                hash
            )
        )

        assertFalse(
            PasswordHasher.verifyPassword(
                "StrongAdminPass123",
                "bad-salt",
                hash
            )
        )

        assertFalse(
            PasswordHasher.verifyPassword(
                "StrongAdminPass123",
                salt,
                "bad-hash"
            )
        )
    }

    @Test
    fun testPinThrottlingAndLockoutInMemory() {
        val throttler =
            PinThrottler(
                maxAttempts = 5,
                lockoutDurationMillis = 30_000L
            )

        val username =
            "cashier_ahmed"

        val t0 =
            100_000L

        for (i in 1..4) {
            throttler.recordFailure(
                username,
                t0
            )

            assertFalse(
                throttler.isLockedOut(
                    username,
                    t0
                )
            )
        }

        val lockoutSeconds =
            throttler.recordFailure(
                username,
                t0
            )

        assertEquals(
            30L,
            lockoutSeconds
        )

        assertTrue(
            throttler.isLockedOut(
                username,
                t0
            )
        )

        assertEquals(
            30L,
            throttler
                .getRemainingLockoutSeconds(
                    username,
                    t0
                )
        )

        assertTrue(
            throttler.isLockedOut(
                username,
                t0 + 15_000L
            )
        )

        assertEquals(
            15L,
            throttler
                .getRemainingLockoutSeconds(
                    username,
                    t0 + 15_000L
                )
        )

        assertFalse(
            throttler.isLockedOut(
                username,
                t0 + 31_000L
            )
        )

        assertEquals(
            0L,
            throttler
                .getRemainingLockoutSeconds(
                    username,
                    t0 + 31_000L
                )
        )
    }

    @Test
    fun testPermissionCheckerAdminVsCashier() {

        val adminUser =
            UserAccountEntity(
                id = "admin-1",
                businessId = "biz-1",
                displayName = "Admin Boss",
                username = "admin",
                role = UserRole.ADMIN,
                authType = AuthType.PASSWORD,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = 0L,
                updatedAt = 0L
            )

        val cashierUser =
            UserAccountEntity(
                id = "cashier-1",
                businessId = "biz-1",
                displayName = "Cashier Ahmed",
                username = "ahmed",
                role = UserRole.CASHIER,
                authType = AuthType.PIN,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = 0L,
                updatedAt = 0L
            )

        val membershipRestricted =
            UserBranchMembershipEntity(
                id = "mem-1",
                userId = "cashier-1",
                branchId = "branch-1",
                role = UserRole.CASHIER,
                canCreateSale = true,
                canAcceptCash = true,
                canAcceptOnline = true,
                canCreateCreditSale = false,
                canUseCoupon = false,
                canIssueInvoice = true,
                canIssueReceipt = true,
                canRefund = false,
                canVoid = false,
                canCorrectAmount = false,
                canChangePaymentMethod = false,
                canViewBroadHistory = false,
                canManageProducts = false,
                canViewReports = false,
                createdAt = 0L
            )

        assertTrue(
            PermissionChecker.isPermitted(
                adminUser,
                null,
                PermissionAction.REFUND
            )
        )

        PermissionChecker.assertPermitted(
            adminUser,
            null,
            PermissionAction.REFUND
        )

        assertFalse(
            PermissionChecker.isPermitted(
                cashierUser,
                membershipRestricted,
                PermissionAction.CREATE_SALE
            )
        )

        assertFalse(
            PermissionChecker.isPermitted(
                cashierUser,
                membershipRestricted,
                PermissionAction.REFUND
            )
        )

        assertThrows(
            PermissionDeniedException::class.java
        ) {
            PermissionChecker.assertPermitted(
                cashierUser,
                membershipRestricted,
                PermissionAction.REFUND
            )
        }
    }
}