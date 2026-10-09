package com.dailymoneyregister.app

import android.app.Application
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.security.DatabaseEncryptionMigrator
import com.dailymoneyregister.core.security.DatabasePassphraseManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DmrApplication : Application() {

    override fun onCreate() {
        installCrashRecorder()

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

    /**
     * Records the last uncaught crash to app-private storage, then hands the
     * crash to the previous handler so normal Android behaviour is unchanged.
     * Writes nothing but the timestamp and stack trace (no financial data).
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                filesDir.resolve("last_crash.txt").writeText(
                    "${System.currentTimeMillis()}\n" +
                        "${thread.name}\n" +
                        error.stackTraceToString().take(8000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
