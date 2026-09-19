package com.dailymoneyregister.core.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabasePassphraseManagerTest {

    private lateinit var context: Context

    private val preferenceName =
        "dmr_database_key_test_preferences"

    private val keyAlias =
        "dmr_database_key_test_alias"

    @Before
    fun setUp() {

        context =
            ApplicationProvider
                .getApplicationContext()

        clearPreferences()
        deleteTestKey()
    }

    @After
    fun tearDown() {

        clearPreferences()
        deleteTestKey()
    }

    @Test
    fun passphrasePersistsAcrossManagerRecreation() {

        val first =
            DatabasePassphraseManager(
                context,
                preferenceName,
                keyAlias
            ).getOrCreatePassphrase()

        val second =
            DatabasePassphraseManager(
                context,
                preferenceName,
                keyAlias
            ).getOrCreatePassphrase()

        assertEquals(
            32,
            first.size
        )

        assertArrayEquals(
            first,
            second
        )
    }

    @Test
    fun preferencesNeverContainPlainDatabasePassphrase() {

        val passphrase =
            DatabasePassphraseManager(
                context,
                preferenceName,
                keyAlias
            ).getOrCreatePassphrase()

        val plainBase64 =
            Base64.encodeToString(
                passphrase,
                Base64.NO_WRAP
            )

        val storedValues =
            context
                .getSharedPreferences(
                    preferenceName,
                    Context.MODE_PRIVATE
                )
                .all
                .values
                .filterIsInstance<String>()

        assertTrue(
            "Wrapped key material should be stored",
            storedValues.isNotEmpty()
        )

        assertFalse(
            "Plain database passphrase must never be stored",
            storedValues.contains(
                plainBase64
            )
        )
    }

    @Test
    fun missingAndroidKeystoreKeyFailsClosed() {

        val manager =
            DatabasePassphraseManager(
                context,
                preferenceName,
                keyAlias
            )

        manager.getOrCreatePassphrase()

        deleteTestKey()

        var failedClosed = false

        try {

            DatabasePassphraseManager(
                context,
                preferenceName,
                keyAlias
            ).getOrCreatePassphrase()

        } catch (e: IllegalStateException) {

            failedClosed =
                e.message?.contains(
                    "wrapping key",
                    ignoreCase = true
                ) == true
        }

        assertTrue(
            "Stored encrypted material must not silently regenerate " +
                "a different wrapping key",
            failedClosed
        )
    }

    private fun clearPreferences() {

        val committed =
            context
                .getSharedPreferences(
                    preferenceName,
                    Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit()

        assertTrue(
            "Test preferences should clear",
            committed
        )
    }

    private fun deleteTestKey() {

        val keyStore =
            KeyStore.getInstance(
                "AndroidKeyStore"
            ).apply {
                load(null)
            }

        if (
            keyStore.containsAlias(
                keyAlias
            )
        ) {
            keyStore.deleteEntry(
                keyAlias
            )
        }
    }
}