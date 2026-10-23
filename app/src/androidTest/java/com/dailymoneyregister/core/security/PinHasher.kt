package com.dailymoneyregister.core.security

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

