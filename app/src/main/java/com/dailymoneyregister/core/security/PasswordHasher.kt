package com.dailymoneyregister.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val HASH_ALGORITHM =
    "PBKDF2WithHmacSHA256"

private const val HASH_FORMAT =
    "pbkdf2-sha256"

private const val CURRENT_ITERATIONS =
    600_000

private const val KEY_LENGTH_BITS =
    256

private const val HASH_HEX_LENGTH =
    KEY_LENGTH_BITS / 4

private const val SALT_LENGTH_BYTES =
    16

private const val SALT_HEX_LENGTH =
    SALT_LENGTH_BYTES * 2

private const val MIN_ACCEPTED_ITERATIONS =
    10_000

private const val MAX_ACCEPTED_ITERATIONS =
    2_000_000

private data class ParsedCredentialHash(
    val iterations: Int,
    val hashHex: String,
    val isLegacy: Boolean
)

internal object CredentialHasher {

    private val random =
        SecureRandom()

    fun generateSalt(): String {
        val salt =
            ByteArray(SALT_LENGTH_BYTES)

        random.nextBytes(salt)

        return bytesToHex(salt)
    }

    fun hashCurrent(
        secret: String,
        saltHex: String
    ): String {
        val hash =
            derive(
                secret = secret,
                saltHex = saltHex,
                iterations = CURRENT_ITERATIONS
            )

        return "$HASH_FORMAT:$CURRENT_ITERATIONS:$hash"
    }

    fun verify(
        secret: String,
        saltHex: String,
        storedHash: String,
        legacyIterations: Int
    ): Boolean {

        val parsed =
            parseStoredHash(
                storedHash = storedHash,
                legacyIterations = legacyIterations
            ) ?: return false

        if (!isValidHex(saltHex, SALT_HEX_LENGTH)) {
            return false
        }

        return runCatching {

            val calculated =
                derive(
                    secret = secret,
                    saltHex = saltHex,
                    iterations = parsed.iterations
                )

            constantTimeEquals(
                calculated,
                parsed.hashHex
            )
        }.getOrDefault(false)
    }

    fun needsUpgrade(
        storedHash: String,
        legacyIterations: Int
    ): Boolean {

        val parsed =
            parseStoredHash(
                storedHash = storedHash,
                legacyIterations = legacyIterations
            ) ?: return true

        return parsed.isLegacy ||
            parsed.iterations < CURRENT_ITERATIONS
    }

    private fun parseStoredHash(
        storedHash: String,
        legacyIterations: Int
    ): ParsedCredentialHash? {

        // Session 1-5 legacy format:
        // just the 64-character PBKDF2 hash.
        if (!storedHash.contains(':')) {

            if (
                !isValidHex(
                    storedHash,
                    HASH_HEX_LENGTH
                )
            ) {
                return null
            }

            return ParsedCredentialHash(
                iterations = legacyIterations,
                hashHex = storedHash,
                isLegacy = true
            )
        }

        // Session 6+ format:
        // pbkdf2-sha256:600000:<hash>
        val parts =
            storedHash.split(':')

        if (parts.size != 3) {
            return null
        }

        if (parts[0] != HASH_FORMAT) {
            return null
        }

        val iterations =
            parts[1].toIntOrNull()
                ?: return null

        if (
            iterations !in
            MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS
        ) {
            return null
        }

        if (
            !isValidHex(
                parts[2],
                HASH_HEX_LENGTH
            )
        ) {
            return null
        }

        return ParsedCredentialHash(
            iterations = iterations,
            hashHex = parts[2],
            isLegacy = false
        )
    }

    private fun derive(
        secret: String,
        saltHex: String,
        iterations: Int
    ): String {

        require(
            isValidHex(
                saltHex,
                SALT_HEX_LENGTH
            )
        ) {
            "Invalid credential salt"
        }

        require(
            iterations in
                MIN_ACCEPTED_ITERATIONS..
                MAX_ACCEPTED_ITERATIONS
        ) {
            "Invalid credential work factor"
        }

        val salt =
            hexToBytes(saltHex)

        val spec =
            PBEKeySpec(
                secret.toCharArray(),
                salt,
                iterations,
                KEY_LENGTH_BITS
            )

        return try {

            val factory =
                SecretKeyFactory
                    .getInstance(
                        HASH_ALGORITHM
                    )

            bytesToHex(
                factory
                    .generateSecret(spec)
                    .encoded
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun isValidHex(
        value: String,
        requiredLength: Int
    ): Boolean {

        if (value.length != requiredLength) {
            return false
        }

        return value.all {
            Character.digit(it, 16) >= 0
        }
    }

    private fun hexToBytes(
        hex: String
    ): ByteArray {

        val result =
            ByteArray(hex.length / 2)

        var i = 0

        while (i < hex.length) {

            val high =
                Character.digit(
                    hex[i],
                    16
                )

            val low =
                Character.digit(
                    hex[i + 1],
                    16
                )

            require(
                high >= 0 &&
                    low >= 0
            ) {
                "Invalid hexadecimal credential data"
            }

            result[i / 2] =
                (
                    (high shl 4) +
                        low
                    ).toByte()

            i += 2
        }

        return result
    }

    private fun bytesToHex(
        bytes: ByteArray
    ): String {

        val result =
            StringBuilder(
                bytes.size * 2
            )

        bytes.forEach { byte ->
            result.append(
                (byte.toInt() and 0xff)
                    .toString(16)
                    .padStart(2, '0')
            )
        }

        return result.toString()
    }

    private fun constantTimeEquals(
        first: String,
        second: String
    ): Boolean {

        if (
            first.length !=
            second.length
        ) {
            return false
        }

        var result = 0

        for (i in first.indices) {
            result =
                result or
                    (
                        first[i].code xor
                            second[i].code
                        )
        }

        return result == 0
    }
}

object PinHasher {

    private const val LEGACY_ITERATIONS =
        10_000

    fun generateSalt(): String =
        CredentialHasher.generateSalt()

    fun hashPin(
        pin: String,
        saltHex: String
    ): String {

        require(isValidPin(pin)) {
            "Cashier PIN must be exactly 6 numeric digits"
        }

        return CredentialHasher.hashCurrent(
            secret = pin,
            saltHex = saltHex
        )
    }

    fun verifyPin(
        pin: String,
        saltHex: String,
        storedHash: String
    ): Boolean {

        if (!isValidPin(pin)) {
            return false
        }

        return CredentialHasher.verify(
            secret = pin,
            saltHex = saltHex,
            storedHash = storedHash,
            legacyIterations =
                LEGACY_ITERATIONS
        )
    }

    fun needsUpgrade(
        storedHash: String
    ): Boolean {

        return CredentialHasher.needsUpgrade(
            storedHash = storedHash,
            legacyIterations =
                LEGACY_ITERATIONS
        )
    }

    fun isValidPin(
        pin: String
    ): Boolean {

        return pin.length == 6 &&
            pin.all { it.isDigit() }
    }
}



/**
 * Admin / Owner password security.
 */
object PasswordHasher {

    private const val LEGACY_ITERATIONS =
        15_000

    private const val LEGACY_MIN_LENGTH =
        6

    const val MIN_NEW_PASSWORD_LENGTH =
        8

    const val MAX_NEW_PASSWORD_LENGTH =
        128

    private const val MAX_VERIFICATION_LENGTH =
        1024

    fun generateSalt(): String =
        CredentialHasher.generateSalt()

    /**
     * Policy for NEW Admin passwords.
     *
     * Existing legacy passwords are not rejected simply because
     * they pre-date this policy.
     */
    fun validateNewPassword(
        password: String,
        username: String
    ): String? {

        if (
            password.length <
            MIN_NEW_PASSWORD_LENGTH
        ) {
            return "Admin password must be at least " +
                "$MIN_NEW_PASSWORD_LENGTH characters"
        }

        if (
            password.length >
            MAX_NEW_PASSWORD_LENGTH
        ) {
            return "Admin password must not exceed " +
                "$MAX_NEW_PASSWORD_LENGTH characters"
        }

        val cleanUsername =
            username.trim()

        if (
            cleanUsername.isNotEmpty() &&
            password.equals(
                cleanUsername,
                ignoreCase = true
            )
        ) {
            return "Admin password must not be the same as the username"
        }

        return null
    }

    /**
     * Used when creating a NEW password.
     */
    fun hashPassword(
        password: String,
        saltHex: String
    ): String {

        require(
            password.length in
                MIN_NEW_PASSWORD_LENGTH..
                MAX_NEW_PASSWORD_LENGTH
        ) {
            "Admin password must be between " +
                "$MIN_NEW_PASSWORD_LENGTH and " +
                "$MAX_NEW_PASSWORD_LENGTH characters"
        }

        return CredentialHasher.hashCurrent(
            secret = password,
            saltHex = saltHex
        )
    }

    /**
     * A successfully verified legacy password may be shorter
     * than today's creation policy. It can still be safely
     * re-hashed using the stronger work factor.
     */
    fun rehashVerifiedPassword(
        password: String,
        saltHex: String
    ): String {

        require(
            password.length in
                LEGACY_MIN_LENGTH..
                MAX_VERIFICATION_LENGTH
        ) {
            "Invalid verified legacy password length"
        }

        return CredentialHasher.hashCurrent(
            secret = password,
            saltHex = saltHex
        )
    }

    fun verifyPassword(
        password: String,
        saltHex: String,
        storedHash: String
    ): Boolean {

        if (
            password.length !in
            LEGACY_MIN_LENGTH..
                MAX_VERIFICATION_LENGTH
        ) {
            return false
        }

        return CredentialHasher.verify(
            secret = password,
            saltHex = saltHex,
            storedHash = storedHash,
            legacyIterations =
                LEGACY_ITERATIONS
        )
    }

    fun needsUpgrade(
        storedHash: String
    ): Boolean {

        return CredentialHasher.needsUpgrade(
            storedHash = storedHash,
            legacyIterations =
                LEGACY_ITERATIONS
        )
    }
}