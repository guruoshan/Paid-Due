package com.dailymoneyregister

import android.content.Context
import androidx.room.Room
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Database construction allowed from instrumentation tests.
 *
 * Disk-backed tests must use an explicit test-only name. The production name is
 * rejected before Room can open or create anything, while ordinary tests use
 * the in-memory builder.
 */
internal object AndroidTestDatabaseFactory {
    private const val TEST_PASSPHRASE = "dmr-android-test-only-passphrase"

    fun requireTestDatabaseName(databaseName: String): String {
        val normalizedName = databaseName.trim()
        require(normalizedName.isNotEmpty()) { "Test database name must not be blank" }
        require(normalizedName != DmrDatabase.DB_NAME) {
            "Instrumentation tests must never open ${DmrDatabase.DB_NAME}"
        }
        require(normalizedName != "daily_money_register.db") {
            "Instrumentation tests must never open the working database"
        }
        return normalizedName
    }

    fun createInMemory(context: Context): DmrDatabase =
        DmrDatabase.createInMemory(context)

    fun createEncryptedDisk(
        context: Context,
        databaseName: String
    ): DmrDatabase {
        val safeName = requireTestDatabaseName(databaseName)
        System.loadLibrary("sqlcipher")

        return Room.databaseBuilder(
            context.applicationContext,
            DmrDatabase::class.java,
            safeName
        )
            .openHelperFactory(
                SupportOpenHelperFactory(TEST_PASSPHRASE.toByteArray(Charsets.UTF_8))
            )
            .addMigrations(
                DmrDatabase.MIGRATION_1_2,
                DmrDatabase.MIGRATION_2_3,
                DmrDatabase.MIGRATION_3_4,
                DmrDatabase.MIGRATION_4_5,
                DmrDatabase.MIGRATION_5_6,
                DmrDatabase.MIGRATION_6_7,
                DmrDatabase.MIGRATION_7_8,
                DmrDatabase.MIGRATION_8_9,
                DmrDatabase.MIGRATION_9_10,
                DmrDatabase.MIGRATION_10_11,
                DmrDatabase.MIGRATION_11_12
            )
            .addCallback(FinancialImmutabilityCallback)
            .allowMainThreadQueries()
            .build()
    }
}
