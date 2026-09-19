package com.dailymoneyregister.app

import android.app.Application
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
    }
}