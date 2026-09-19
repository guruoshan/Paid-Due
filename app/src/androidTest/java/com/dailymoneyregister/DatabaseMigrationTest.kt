package com.dailymoneyregister

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    private val immutableTables = listOf(
        "transactions",
        "transaction_items",
        "settlement_entries",
        "amount_correction_events",
        "payment_method_change_events",
        "void_events",
        "refund_events",
        "credit_ledger_events",
        "coupon_ledger_events",
        "coupon_authorized_user_events",
        "cash_shift_events"
    )

    @Test
    fun testMigration1To2PreservesFinancialGuardProof() {
        val databaseName = "dmr-migration-1-2-only-test"

        migrationHelper.createDatabase(databaseName, 1).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            DmrDatabase.MIGRATION_1_2
        )

        assertImmutabilityGuards(migrated)
        migrated.close()
    }

    @Test
    fun testMigration2To3PreservesLegacyIdentityAndCredentials() {
        val databaseName = "dmr-migration-2-3-only-test"
        val source = migrationHelper.createDatabase(databaseName, 2)
        source.execSQL("INSERT INTO user_accounts (id, business_id, display_name, username, role, auth_type, credential_hash, credential_salt, language_code, is_active, created_at, updated_at) VALUES ('legacy-user', 'legacy-business', 'Legacy User', 'legacy_name', 'ADMIN', 'PASSWORD', 'legacy-hash', 'legacy-salt', 'en', 1, 10, 10)")
        source.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            DmrDatabase.MIGRATION_2_3
        )

        migrated.query("SELECT id, canonical_phone, display_name FROM human_identities WHERE id = 'legacy-user'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.getString(0) == "legacy-user")
            assertTrue(cursor.getString(2) == "Legacy User")
        }
        migrated.query("SELECT id, identity_id, username, credential_hash, credential_salt FROM user_accounts WHERE id = 'legacy-user'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0) == "legacy-user")
            assertTrue(cursor.getString(1) == "legacy-user")
            assertTrue(cursor.getString(2) == "legacy_name")
            assertTrue(cursor.getString(3) == "legacy-hash")
            assertTrue(cursor.getString(4) == "legacy-salt")
        }
        migrated.close()
    }

    @Test
    fun testMigration1To2To3PreservesCompleteUpgradeChain() {
        val databaseName = "dmr-migration-1-2-3-chain-test"

        migrationHelper
            .createDatabase(databaseName, 1)
            .close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            DmrDatabase.MIGRATION_1_2,
            DmrDatabase.MIGRATION_2_3
        )

        assertImmutabilityGuards(migrated)
        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='human_identities'").use { assertTrue(it.moveToFirst()) }
        migrated.query("PRAGMA table_info(user_accounts)").use { cursor ->
            var foundIdentity = false
            var foundMustChange = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                foundIdentity = foundIdentity || name == "identity_id"
                foundMustChange = foundMustChange || name == "must_change_pin"
            }
            assertTrue(foundIdentity)
            assertTrue(foundMustChange)
        }
        migrated.close()
    }

    @Test
    fun testProductionDatabaseBuilderDoesNotUseDestructiveFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val db = DmrDatabase.getInstance(context)
        assertNotNull(db)

        val writableDatabase = db.openHelper.writableDatabase

        assertTrue(
            "Database connection should be open",
            db.isOpen
        )

        assertImmutabilityGuards(writableDatabase)
    }

    private fun assertImmutabilityGuards(
        db: SupportSQLiteDatabase
    ) {
        immutableTables.forEach { table ->
            listOf(
                "immutable_${table}_no_update",
                "immutable_${table}_no_delete",
                "immutable_${table}_no_replace"
            ).forEach { triggerName ->

                db.query(
                    """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'trigger'
                      AND name = '$triggerName'
                    """.trimIndent()
                ).use { cursor ->
                    assertTrue(
                        "Missing immutability trigger: $triggerName",
                        cursor.moveToFirst()
                    )
                }
            }
        }
    }
}
