package com.dailymoneyregister.feature.invite.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dailymoneyregister.core.security.PinHasher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class PendingInviteCredential(
    val credentialHash: String,
    val credentialSalt: String,
    val canonicalPhone: String
)

/**
 * Crash/retry bridge for invite activation.
 *
 * The backend activation is atomic, but local Room materialization happens
 * after the backend transaction commits. We therefore retain the exact
 * generated verifier + salt so an exact retry can recover safely.
 *
 * The verifier is encrypted with Android Keystore before SharedPreferences.
 * Raw invite tokens and plaintext PINs are never persisted.
 */
@Singleton
class InviteActivationRecoveryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun load(
        tokenHash: String
    ): PendingInviteCredential? {

        if (!TOKEN_HASH.matches(tokenHash)) {
            return null
        }

        val encrypted =
            prefs.getString(
                encryptedKey(tokenHash),
                null
            ) ?: return null

        val iv =
            prefs.getString(
                ivKey(tokenHash),
                null
            ) ?: return null

        return runCatching {

            val cipher =
                Cipher.getInstance(TRANSFORMATION)

            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(
                    GCM_TAG_BITS,
                    Base64.decode(iv, Base64.NO_WRAP)
                )
            )

            val plain =
                cipher.doFinal(
                    Base64.decode(
                        encrypted,
                        Base64.NO_WRAP
                    )
                )
                .toString(Charsets.UTF_8)

            val parts =
                plain.split(
                    SEPARATOR,
                    limit = 3
                )

            check(parts.size == 3)

            val hash =
                parts[0]

            val salt =
                parts[1]

            val canonicalPhone =
                parts[2]

            check(
                CREDENTIAL_HASH.matches(
                    hash
                )
            )

            check(
                CREDENTIAL_SALT.matches(
                    salt
                )
            )

            check(
                CANONICAL_PHONE.matches(
                    canonicalPhone
                )
            )

            PendingInviteCredential(
                credentialHash = hash,
                credentialSalt = salt,
                canonicalPhone = canonicalPhone
            )
        }.getOrElse {
            clear(tokenHash)
            null
        }
    }

    fun getOrCreate(
        tokenHash: String,
        pin: String,
        canonicalPhone: String
    ): PendingInviteCredential {

        require(
            TOKEN_HASH.matches(
                tokenHash
            )
        ) {
            "Invalid invite token hash"
        }

        require(
            PinHasher.isValidPin(
                pin
            )
        ) {
            "Cashier PIN must be exactly 6 numeric digits"
        }

        require(
            CANONICAL_PHONE.matches(
                canonicalPhone
            )
        ) {
            "Invalid Cashier phone"
        }

        val existing =
            load(tokenHash)

        if (existing != null) {

            check(
                existing.canonicalPhone ==
                    canonicalPhone
            ) {
                "Activation retry phone does not match original activation"
            }

            check(
                PinHasher.verifyPin(
                    pin = pin,
                    saltHex =
                        existing.credentialSalt,
                    storedHash =
                        existing.credentialHash
                )
            ) {
                "Activation retry PIN does not match the original activation attempt"
            }

            return existing
        }

        val salt =
            PinHasher.generateSalt()

        val hash =
            PinHasher.hashPin(
                pin = pin,
                saltHex = salt
            )

        val plain =
            "$hash$SEPARATOR$salt$SEPARATOR$canonicalPhone"
                .toByteArray(Charsets.UTF_8)

        val cipher =
            Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey()
        )

        val ciphertext =
            cipher.doFinal(plain)

        val editor =
            prefs.edit()
                .putString(
                    encryptedKey(tokenHash),
                    Base64.encodeToString(
                        ciphertext,
                        Base64.NO_WRAP
                    )
                )
                .putString(
                    ivKey(tokenHash),
                    Base64.encodeToString(
                        cipher.iv,
                        Base64.NO_WRAP
                    )
                )

        check(editor.commit()) {
            "Could not persist invite activation recovery evidence"
        }

        return PendingInviteCredential(
            credentialHash = hash,
            credentialSalt = salt,
            canonicalPhone = canonicalPhone
        )
    }

    fun clear(
        tokenHash: String
    ) {
        if (!TOKEN_HASH.matches(tokenHash)) {
            return
        }

        check(
            prefs.edit()
                .remove(encryptedKey(tokenHash))
                .remove(ivKey(tokenHash))
                .commit()
        ) {
            "Could not clear invite activation recovery evidence"
        }
    }

    private fun getOrCreateKey(): SecretKey {

        val keyStore =
            KeyStore.getInstance("AndroidKeyStore")
                .apply {
                    load(null)
                }

        val existing =
            keyStore.getKey(
                KEY_ALIAS,
                null
            ) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }

    private fun encryptedKey(
        tokenHash: String
    ): String =
        "enc_$tokenHash"

    private fun ivKey(
        tokenHash: String
    ): String =
        "iv_$tokenHash"

    private companion object {
        const val PREFS_NAME =
            "dmr_invite_activation_recovery"

        const val KEY_ALIAS =
            "dmr_invite_activation_recovery_key_v1"

        const val TRANSFORMATION =
            "AES/GCM/NoPadding"

        const val GCM_TAG_BITS =
            128

        const val SEPARATOR =
            "\n"

        val TOKEN_HASH =
            Regex("^[0-9a-f]{64}$")

        val CREDENTIAL_HASH =
            Regex(
                "^pbkdf2-sha256:600000:[0-9a-f]{64}$"
            )

        val CREDENTIAL_SALT =
            Regex("^[0-9a-f]{32}$")

        val CANONICAL_PHONE =
            Regex("""^\+[1-9][0-9]{7,14}$""")
    }
}