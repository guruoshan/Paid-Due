package com.dailymoneyregister.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns the local SQLCipher database passphrase.
 *
 * Security model:
 * - The database passphrase is random and is never hard-coded.
 * - The passphrase itself is never stored in SharedPreferences.
 * - Only AES-GCM encrypted key material is persisted.
 * - The AES wrapping key stays inside AndroidKeyStore.
 * - Corrupt or incomplete key material fails closed.
 *
 * This class does NOT open or migrate the database.
 */
class DatabasePassphraseManager(
    context: Context,
    private val preferenceName: String = DEFAULT_PREFERENCE_NAME,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(
            preferenceName,
            Context.MODE_PRIVATE
        )

    private val secureRandom =
        SecureRandom()

    fun getOrCreatePassphrase(): ByteArray =
        synchronized(PROCESS_LOCK) {

            val hasWrapped =
                preferences.contains(KEY_WRAPPED_PASSPHRASE)

            val hasIv =
                preferences.contains(KEY_IV)

            val hasVersion =
                preferences.contains(KEY_MATERIAL_VERSION)

            val hasAnyStoredMaterial =
                hasWrapped || hasIv || hasVersion

            if (hasAnyStoredMaterial) {

                if (
                    !hasWrapped ||
                    !hasIv ||
                    !hasVersion
                ) {
                    throw IllegalStateException(
                        "Database encryption key material is incomplete"
                    )
                }

                val version =
                    preferences.getInt(
                        KEY_MATERIAL_VERSION,
                        -1
                    )

                if (version != MATERIAL_VERSION) {
                    throw IllegalStateException(
                        "Unsupported database key material version: $version"
                    )
                }

                return@synchronized decryptStoredPassphrase()
            }

            createAndPersistPassphrase()
        }

    private fun createAndPersistPassphrase(): ByteArray {

        val wrappingKey =
            getOrCreateWrappingKey()

        val passphrase =
            ByteArray(PASSPHRASE_LENGTH_BYTES)

        secureRandom.nextBytes(passphrase)

        try {

            val cipher =
                Cipher.getInstance(
                    CIPHER_TRANSFORMATION
                )

            cipher.init(
                Cipher.ENCRYPT_MODE,
                wrappingKey
            )

            val wrapped =
                cipher.doFinal(passphrase)

            val iv =
                cipher.iv
                    ?: throw IllegalStateException(
                        "Database key encryption did not provide an IV"
                    )

            if (iv.isEmpty()) {
                throw IllegalStateException(
                    "Database key encryption produced an empty IV"
                )
            }

            val committed =
                preferences
                    .edit()
                    .putInt(
                        KEY_MATERIAL_VERSION,
                        MATERIAL_VERSION
                    )
                    .putString(
                        KEY_WRAPPED_PASSPHRASE,
                        Base64.encodeToString(
                            wrapped,
                            Base64.NO_WRAP
                        )
                    )
                    .putString(
                        KEY_IV,
                        Base64.encodeToString(
                            iv,
                            Base64.NO_WRAP
                        )
                    )
                    .commit()

            if (!committed) {
                throw IllegalStateException(
                    "Database encryption key material could not be persisted"
                )
            }

            return passphrase.copyOf()

        } finally {
            passphrase.fill(0)
        }
    }

    private fun decryptStoredPassphrase(): ByteArray {

        val wrappedText =
            preferences.getString(
                KEY_WRAPPED_PASSPHRASE,
                null
            ) ?: throw IllegalStateException(
                "Database wrapped passphrase is missing"
            )

        val ivText =
            preferences.getString(
                KEY_IV,
                null
            ) ?: throw IllegalStateException(
                "Database encryption IV is missing"
            )

        val wrapped =
            decodeBase64(
                wrappedText,
                "wrapped database passphrase"
            )

        val iv =
            decodeBase64(
                ivText,
                "database encryption IV"
            )

        if (iv.isEmpty()) {
            throw IllegalStateException(
                "Database encryption IV is invalid"
            )
        }

        val wrappingKey =
            getExistingWrappingKey()

        val cipher =
            Cipher.getInstance(
                CIPHER_TRANSFORMATION
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey,
            GCMParameterSpec(
                GCM_TAG_LENGTH_BITS,
                iv
            )
        )

        val passphrase =
            try {
                cipher.doFinal(wrapped)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Database passphrase could not be decrypted",
                    e
                )
            }

        if (
            passphrase.size !=
            PASSPHRASE_LENGTH_BYTES
        ) {
            passphrase.fill(0)

            throw IllegalStateException(
                "Database passphrase has an invalid length"
            )
        }

        return passphrase
    }

    private fun getExistingWrappingKey(): SecretKey {

        val keyStore =
            loadKeyStore()

        val key =
            keyStore.getKey(
                keyAlias,
                null
            )

        return key as? SecretKey
            ?: throw IllegalStateException(
                "Database wrapping key is missing from AndroidKeyStore"
            )
    }

    private fun getOrCreateWrappingKey(): SecretKey {

        val keyStore =
            loadKeyStore()

        val existing =
            keyStore.getKey(
                keyAlias,
                null
            )

        if (existing != null) {

            return existing as? SecretKey
                ?: throw IllegalStateException(
                    "Database wrapping key has an unexpected type"
                )
        }

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )

        val specification =
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(AES_KEY_SIZE_BITS)
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()

        keyGenerator.init(specification)

        return keyGenerator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(
            ANDROID_KEYSTORE_PROVIDER
        ).apply {
            load(null)
        }

    private fun decodeBase64(
        value: String,
        description: String
    ): ByteArray {

        return try {

            Base64.decode(
                value,
                Base64.NO_WRAP
            )

        } catch (e: IllegalArgumentException) {

            throw IllegalStateException(
                "Invalid $description",
                e
            )
        }
    }

    companion object {

        private val PROCESS_LOCK =
            Any()

        private const val DEFAULT_PREFERENCE_NAME =
            "dmr_database_key_material_v1"

        private const val DEFAULT_KEY_ALIAS =
            "dmr_database_wrapping_key_v1"

        private const val KEY_MATERIAL_VERSION =
            "material_version"

        private const val KEY_WRAPPED_PASSPHRASE =
            "wrapped_passphrase"

        private const val KEY_IV =
            "wrapping_iv"

        private const val MATERIAL_VERSION =
            1

        private const val PASSPHRASE_LENGTH_BYTES =
            32

        private const val AES_KEY_SIZE_BITS =
            256

        private const val GCM_TAG_LENGTH_BITS =
            128

        private const val ANDROID_KEYSTORE_PROVIDER =
            "AndroidKeyStore"

        private const val CIPHER_TRANSFORMATION =
            "AES/GCM/NoPadding"
    }
}