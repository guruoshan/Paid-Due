package com.dailymoneyregister.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Device-local biometric gate. The biometric prompt must authenticate a Cipher
 * backed by this Android Keystore key; a plain success callback is not sufficient.
 */
object SessionBiometricKey {
    private const val ALIAS = "paiddue_session_biometric"

    fun createCipher(): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return try {
            val key = (keyStore.getKey(ALIAS, null) as? SecretKey) ?: generateKey()
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            keyStore.deleteEntry(ALIAS)
            throw invalidated
        }
    }

    /** Call only after successful password/PIN verification, never after biometric failure. */
    fun recoverAfterCredentialUnlock() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = runCatching { keyStore.getKey(ALIAS, null) as? SecretKey }.getOrNull()
        if (existing == null) generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }
}
