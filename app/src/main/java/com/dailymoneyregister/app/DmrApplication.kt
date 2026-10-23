package com.dailymoneyregister.app

import android.app.Application
import android.content.Context
import java.security.KeyStore
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.security.DatabaseEncryptionMigrator
import com.dailymoneyregister.core.security.DatabasePassphraseManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DmrApplication : Application() {

    override fun onCreate() {
        /*
         * BOARDLOCK:
         * Database encryption preparation must finish before Hilt
         * or any repository can obtain the production Room database.
         */
        System.loadLibrary("sqlcipher")

        val passphrase =
            DatabasePassphraseManager(this)
                .getOrCreatePassphrase()

        try {
            DatabaseEncryptionMigrator(
                this,
                DmrDatabase.DB_NAME
            ).prepareDatabase(
                passphrase
            )
        } finally {
            passphrase.fill(0)
        }

        super.onCreate()
        retireCashierCredentials()
    }

    private fun retireCashierCredentials() {
        getSharedPreferences("paid_due_cashier_sync_credentials", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        getSharedPreferences("dmr_invite_activation_recovery", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry("paid_due_cashier_sync_aes_v1")
                deleteEntry("dmr_invite_activation_recovery_key_v1")
            }
        }
    }
}