package com.dailymoneyregister.core.database.security

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialSyncEncryptedMigrationG2Test {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    @Test
    fun encryptedVersion12CashSaleSurvivesVersion15Migration() {
        val context: Context =
            InstrumentationRegistry.getInstrumentation().targetContext

        // Isolated Android-test database. Never the production DB name.
        val name = "boardlock_g2_encrypted_upgrade_test.db"
        val passphrase = ByteArray(32) { (it + 17).toByte() }

        var migrated: DmrDatabase? = null

        // Test-only database cleanup.
        context.deleteDatabase(name)

        try {
            // STEP 1: Create the exact exported v12 Room schema.
            val old = migrationHelper.createDatabase(name, 12)

            old.execSQL(
                """
                INSERT INTO businesses
                    (id, name, created_at, updated_at)
                VALUES
                    ('g2-business', 'G2 Shop', 10, 10)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO branches (
                    id, business_id, name, code,
                    country_code, currency_code,
                    currency_decimal_places, currency_symbol,
                    timezone_id, tax_rounding_mode,
                    default_tax_rate_bps, created_at, updated_at
                ) VALUES (
                    'g2-branch', 'g2-business', 'Main', 'MAIN',
                    'OM', 'OMR', 3, 'OMR',
                    'Asia/Muscat', 'HALF_UP', 0, 10, 10
                )
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO operations (
                    id, business_id, branch_id,
                    operation_type, subject_type, subject_id,
                    transaction_id, actor_user_id, device_id,
                    occurred_at, request_fingerprint,
                    command_schema_version, intent_payload,
                    status, result_reference_type, result_reference_id
                ) VALUES (
                    'g2-operation', 'g2-business', 'g2-branch',
                    'RECORD_SALE', 'TRANSACTION', 'g2-transaction',
                    'g2-transaction', 'g2-cashier', 'g2-device',
                    100, 'g2-fingerprint', 1, '{}',
                    'SUCCEEDED', 'TRANSACTION', 'g2-transaction'
                )
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO transactions (
                    id, branch_id, type,
                    subtotal_minor, tax_rate_bps,
                    taxable_base_minor, tax_amount_minor,
                    tx_shipping_minor, tx_packing_minor,
                    grand_total_minor, currency_code,
                    currency_decimal_places,
                    created_by_user_id, device_id,
                    created_at, display_ref
                ) VALUES (
                    'g2-transaction', 'g2-branch', 'SALE',
                    4250, 0, 4250, 0,
                    0, 0, 4250, 'OMR', 3,
                    'g2-cashier', 'g2-device',
                    100, 'G2-001'
                )
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO settlement_entries (
                    id, operation_id, subject_type,
                    subject_id, transaction_id,
                    entry_purpose, category, channel,
                    amount_signed_minor, currency_code,
                    confirmation_type, occurred_at, actor_user_id
                ) VALUES (
                    'g2-settlement', 'g2-operation', 'TRANSACTION',
                    'g2-transaction', 'g2-transaction',
                    'SALE_FUNDING', 'CASH', 'CASH_TENDER',
                    4250, 'OMR',
                    'CASHIER_CONFIRMED', 100, 'g2-cashier'
                )
                """.trimIndent()
            )

            old.close()

            // STEP 2: Encrypt the already-populated v12 file.
            val encryptionResult =
                DatabaseEncryptionMigrator(context, name)
                    .prepareDatabase(passphrase.copyOf())

            assertEquals(
                DatabaseEncryptionPreparationResult.MIGRATED,
                encryptionResult
            )

            // SQLCipher must hide the ordinary SQLite file header.
            val header = ByteArray(16)

            context.getDatabasePath(name).inputStream().use {
                val bytesRead = it.read(header)
                assertEquals(16, bytesRead)
            }

            assertTrue(
                "Database must not have the plaintext SQLite header",
                !header.contentEquals(
                    "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                )
            )

            // STEP 3: Open the encrypted v12 database through
            // the current Room v15 schema with the full migration chain and callback.
            System.loadLibrary("sqlcipher")

            migrated = Room.databaseBuilder(
                context,
                DmrDatabase::class.java,
                name
            )
                .openHelperFactory(
                    SupportOpenHelperFactory(passphrase.copyOf())
                )
                .addMigrations(DmrDatabase.MIGRATION_12_13, DmrDatabase.MIGRATION_13_14, DmrDatabase.MIGRATION_14_15)
                .addCallback(FinancialImmutabilityCallback)
                .allowMainThreadQueries()
                .build()

            val sql = migrated.openHelper.writableDatabase

            sql.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(15, it.getInt(0))
            }

            // STEP 4: Financial evidence must be unchanged.
            sql.query(
                """
                SELECT grand_total_minor, currency_code, display_ref
                FROM transactions WHERE id = 'g2-transaction'
                """
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(4250L, it.getLong(0))
                assertEquals("OMR", it.getString(1))
                assertEquals("G2-001", it.getString(2))
            }

            sql.query(
                """
                SELECT category, channel, amount_signed_minor
                FROM settlement_entries WHERE id = 'g2-settlement'
                """
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("CASH", it.getString(0))
                assertEquals("CASH_TENDER", it.getString(1))
                assertEquals(4250L, it.getLong(2))
            }

            sql.query(
                """
                SELECT status, request_fingerprint
                FROM operations WHERE id = 'g2-operation'
                """
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("SUCCEEDED", it.getString(0))
                assertEquals("g2-fingerprint", it.getString(1))
            }

            // Historical sales are not silently backfilled.
            sql.query(
                "SELECT COUNT(*) FROM financial_sync_outbox"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }

            // The new outbox table exists with no historical duplicates.
            sql.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'financial_sync_outbox'
                """
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("financial_sync_outbox", it.getString(0))
            }


        } finally {
            migrated?.close()
            context.deleteDatabase(name)
            passphrase.fill(0)
        }
    }
}