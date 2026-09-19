package com.dailymoneyregister.core.security

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialHardeningTest {

    private val salt =
        "00112233445566778899aabbccddeeff"

    @Test
    fun newPinHashUsesVersionedCurrentWorkFactor() {
        val hash =
            PinHasher.hashPin(
                "123456",
                salt
            )

        assertTrue(
            hash.startsWith(
                "pbkdf2-sha256:600000:"
            )
        )

        assertTrue(
            PinHasher.verifyPin(
                "123456",
                salt,
                hash
            )
        )

        assertFalse(
            PinHasher.needsUpgrade(hash)
        )
    }

    @Test
    fun legacyPinStillVerifiesAndIsMarkedForUpgrade() {
        val legacyHash =
            legacyHash(
                secret = "123456",
                saltHex = salt,
                iterations = 10_000
            )

        assertTrue(
            PinHasher.verifyPin(
                "123456",
                salt,
                legacyHash
            )
        )

        assertTrue(
            PinHasher.needsUpgrade(
                legacyHash
            )
        )
    }

    @Test
    fun newPasswordHashUsesVersionedCurrentWorkFactor() {
        val hash =
            PasswordHasher.hashPassword(
                "StrongAdminPass123",
                salt
            )

        assertTrue(
            hash.startsWith(
                "pbkdf2-sha256:600000:"
            )
        )

        assertTrue(
            PasswordHasher.verifyPassword(
                "StrongAdminPass123",
                salt,
                hash
            )
        )

        assertFalse(
            PasswordHasher.needsUpgrade(
                hash
            )
        )
    }

    @Test
    fun legacyShortPasswordStillLogsInAndCanBeRehashed() {
        val password =
            "abc123"

        val legacyHash =
            legacyHash(
                secret = password,
                saltHex = salt,
                iterations = 15_000
            )

        assertTrue(
            PasswordHasher.verifyPassword(
                password,
                salt,
                legacyHash
            )
        )

        assertTrue(
            PasswordHasher.needsUpgrade(
                legacyHash
            )
        )

        val newSalt =
            PasswordHasher.generateSalt()

        val upgradedHash =
            PasswordHasher
                .rehashVerifiedPassword(
                    password,
                    newSalt
                )

        assertTrue(
            PasswordHasher.verifyPassword(
                password,
                newSalt,
                upgradedHash
            )
        )

        assertFalse(
            PasswordHasher.needsUpgrade(
                upgradedHash
            )
        )
    }

    @Test
    fun newAdminPasswordPolicyRejectsWeakCreationChoices() {

        assertTrue(
            PasswordHasher
                .validateNewPassword(
                    password = "1234567",
                    username = "owner"
                ) != null
        )

        assertTrue(
            PasswordHasher
                .validateNewPassword(
                    password = "ADMIN123",
                    username = "admin123"
                ) != null
        )

        assertNull(
            PasswordHasher
                .validateNewPassword(
                    password =
                        "Long secure passphrase",
                    username = "owner"
                )
        )
    }

    private fun legacyHash(
        secret: String,
        saltHex: String,
        iterations: Int
    ): String {

        val saltBytes =
            ByteArray(
                saltHex.length / 2
            )

        var i = 0

        while (i < saltHex.length) {
            saltBytes[i / 2] =
                (
                    (
                        Character.digit(
                            saltHex[i],
                            16
                        ) shl 4
                        ) +
                        Character.digit(
                            saltHex[i + 1],
                            16
                        )
                    ).toByte()

            i += 2
        }

        val spec =
            PBEKeySpec(
                secret.toCharArray(),
                saltBytes,
                iterations,
                256
            )

        return try {

            val bytes =
                SecretKeyFactory
                    .getInstance(
                        "PBKDF2WithHmacSHA256"
                    )
                    .generateSecret(spec)
                    .encoded

            bytes.joinToString("") { byte ->
                (byte.toInt() and 0xff)
                    .toString(16)
                    .padStart(2, '0')
            }
        } finally {
            spec.clearPassword()
        }
    }
}