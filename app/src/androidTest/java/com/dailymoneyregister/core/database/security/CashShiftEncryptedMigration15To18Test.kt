package com.dailymoneyregister.core.database.security

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CashShiftEncryptedMigration15To18Test {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    @Test
    fun encryptedPopulatedVersion15PreservesCashShiftEventsOutboxAndAuditSchema() {
        val context: Context =
            InstrumentationRegistry.getInstrumentation().targetContext
        val name = "boardlock_cash_shift_v15_to_v18_encrypted.db"
        val passphrase = ByteArray(32) { (it + 41).toByte() }
        var migrated: com.dailymoneyregister.core.database.DmrDatabase? = null
        context.deleteDatabase(name)

        try {
            // Use the authentic Room-exported v15 schema as the source database.
            val old = migrationHelper.createDatabase(name, 15)
            old.execSQL(
                """
                INSERT INTO businesses (id, name, created_at, updated_at)
                VALUES ('shift-business', 'Shift Shop', 10, 10)
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO branches (
                    id, business_id, name, code, country_code, currency_code,
                    currency_decimal_places, currency_symbol, timezone_id,
                    tax_rounding_mode, default_tax_rate_bps, created_at, updated_at
                ) VALUES (
                    'shift-branch', 'shift-business', 'Main', 'MAIN', 'OM', 'OMR',
                    3, 'OMR', 'Asia/Muscat', 'HALF_UP', 0, 10, 10
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO user_accounts (
                    id, business_id, display_name, username, role, auth_type,
                    credential_hash, language_code, is_active, must_change_pin,
                    created_at, updated_at
                ) VALUES (
                    'shift-cashier', 'shift-business', 'Cashier', 'shift_cashier',
                    'CASHIER', 'PIN', 'migration-hash', 'en', 1, 0, 10, 10
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO cloud_staff_identities (
                    membership_id, local_user_id, business_id, branch_id, role,
                    permissions_json, is_active, is_removed, updated_at
                ) VALUES (
                    'shift-membership', 'shift-cashier', 'shift-business', 'shift-branch',
                    'CASHIER', '{}', 1, 0, 100
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO operations (
                    id, business_id, branch_id, operation_type, subject_type,
                    subject_id, actor_user_id, device_id, occurred_at,
                    request_fingerprint, command_schema_version, intent_payload, status
                ) VALUES (
                    'shift-open-op', 'shift-business', 'shift-branch',
                    'OPEN_CASH_SHIFT', 'CASH_SHIFT', 'shift-1', 'shift-cashier',
                    'shift-device', 100, 'shift-open-fingerprint', 1, '{}', 'SUCCEEDED'
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO cash_shifts (id, branch_id, user_id, created_at)
                VALUES ('shift-1', 'shift-branch', 'shift-cashier', 100)
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO cash_shift_events (
                    id, shift_id, operation_id, type, amount_minor, occurred_at, actor_user_id
                ) VALUES (
                    'shift-event-1', 'shift-1', 'shift-open-op', 'OPENED',
                    2500, 100, 'shift-cashier'
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO financial_sync_outbox (
                    operation_id, business_id, branch_id, transaction_id, actor_user_id,
                    device_id, request_fingerprint, command_schema_version, occurred_at,
                    status, attempt_count
                ) VALUES (
                    'shift-open-op', 'shift-business', 'shift-branch', 'shift-1',
                    'shift-cashier', 'shift-device', 'shift-open-fingerprint', 1, 100,
                    'QUEUED', 2
                )
                """.trimIndent()
            )
            old.close()

            // Convert the populated v15 fixture to SQLCipher before Room migrates it.
            System.loadLibrary("sqlcipher")
            assertEquals(
                DatabaseEncryptionPreparationResult.MIGRATED,
                DatabaseEncryptionMigrator(context, name).prepareDatabase(passphrase.copyOf())
            )
            val encryptedHeader = ByteArray(16)
            context.getDatabasePath(name).inputStream().use { input ->
                assertEquals(16, input.read(encryptedHeader))
            }
            assertFalse(
                "The v15 test database must be encrypted before migration",
                encryptedHeader.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
            )

            // Intentionally omit FinancialImmutabilityCallback here: this proves the
            // migration itself reinstalls the guards, rather than the open callback.
            migrated = Room.databaseBuilder(
                context,
                DmrDatabase::class.java,
                name
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                .addMigrations(DmrDatabase.MIGRATION_15_16, DmrDatabase.MIGRATION_16_17, DmrDatabase.MIGRATION_17_18)
                .allowMainThreadQueries()
                .build()

            val sql = migrated.openHelper.writableDatabase
            sql.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(18, it.getInt(0))
            }
            assertEquals(
                "",
                scalarString(sql, "SELECT display_name FROM cloud_staff_identities WHERE local_user_id='shift-cashier'")
            )
            assertEquals(
                1L,
                scalarLong(sql, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='inbound_envelope_audit_events'")
            )
            assertEquals(0L, scalarLong(sql, "SELECT COUNT(*) FROM inbound_envelope_audit_events"))
            assertTrue(triggerExists(sql, "immutable_inbound_envelope_audit_events_no_update"))
            assertTrue(triggerExists(sql, "immutable_inbound_envelope_audit_events_no_delete"))
            assertTrue(triggerExists(sql, "immutable_inbound_envelope_audit_events_no_replace"))

            assertEquals(1L, scalarLong(sql, "SELECT COUNT(*) FROM cash_shifts"))
            assertEquals(1L, scalarLong(sql, "SELECT COUNT(*) FROM cash_shift_events"))
            assertEquals(1L, scalarLong(sql, "SELECT COUNT(*) FROM financial_sync_outbox"))
            assertEquals("shift-1", scalarString(sql, "SELECT id FROM cash_shifts"))
            assertEquals("shift-event-1", scalarString(sql, "SELECT id FROM cash_shift_events"))
            assertEquals("QUEUED", scalarString(sql, "SELECT status FROM financial_sync_outbox"))
            assertEquals(2L, scalarLong(sql, "SELECT attempt_count FROM financial_sync_outbox"))

            // Both Room indexes survive the parent-table rebuild.
            assertEquals(1L, scalarLong(
                sql,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_cash_shifts_branch_id'"
            ))
            assertEquals(1L, scalarLong(
                sql,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_cash_shifts_user_id'"
            ))

            // The cloud identity no longer needs a user_accounts row.
            sql.query("PRAGMA foreign_key_list(cash_shifts)").use { cursor ->
                var userAccountsForeignKeyFound = false
                var branchForeignKeyFound = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(2) == "user_accounts") userAccountsForeignKeyFound = true
                    if (cursor.getString(2) == "branches" && cursor.getString(3) == "branch_id") {
                        branchForeignKeyFound = true
                    }
                }
                assertFalse("cash_shifts must not reference user_accounts", userAccountsForeignKeyFound)
                assertTrue("cash_shifts must retain its branch foreign key", branchForeignKeyFound)
            }

            sql.query("PRAGMA foreign_key_check").use {
                assertFalse("Migration must leave no foreign-key violations", it.moveToFirst())
            }

            // Prove the migrated cash-shift owner can be cloud-only: remove the
            // local login account while retaining the cloud identity and shift.
            // This would fail on v15 because cash_shifts.user_id referenced user_accounts.
            sql.execSQL("DELETE FROM user_accounts WHERE id='shift-cashier'")
            assertEquals(0L, scalarLong(
                sql,
                "SELECT COUNT(*) FROM user_accounts WHERE id='shift-cashier'"
            ))
            assertEquals(1L, scalarLong(
                sql,
                "SELECT COUNT(*) FROM cash_shifts WHERE id='shift-1' AND user_id='shift-cashier'"
            ))
            assertEquals(1L, scalarLong(
                sql,
                "SELECT COUNT(*) FROM cloud_staff_identities WHERE membership_id='shift-membership' AND local_user_id='shift-cashier'"
            ))
            sql.query("PRAGMA foreign_key_check").use {
                assertFalse("Cloud-only shift owner must not introduce foreign-key violations", it.moveToFirst())
            }

            assertTrue(triggerExists(sql, "immutable_cash_shifts_no_update"))
            assertTrue(triggerExists(sql, "immutable_cash_shifts_no_delete"))
            assertTrue(triggerExists(sql, "immutable_cash_shift_events_no_update"))
            assertTrue(triggerExists(sql, "immutable_cash_shift_events_no_delete"))

            assertMutationRejected(sql, "UPDATE cash_shifts SET created_at = 999 WHERE id = 'shift-1'")
            assertMutationRejected(sql, "DELETE FROM cash_shifts WHERE id = 'shift-1'")
            assertMutationRejected(sql, "UPDATE cash_shift_events SET amount_minor = 999 WHERE id = 'shift-event-1'")
            assertMutationRejected(sql, "DELETE FROM cash_shift_events WHERE id = 'shift-event-1'")

            // Rejected mutations must not damage authoritative or synchronization evidence.
            assertEquals(100L, scalarLong(sql, "SELECT created_at FROM cash_shifts WHERE id='shift-1'"))
            assertEquals(2500L, scalarLong(sql, "SELECT amount_minor FROM cash_shift_events WHERE id='shift-event-1'"))
            assertEquals(1L, scalarLong(sql, "SELECT COUNT(*) FROM financial_sync_outbox"))
        } finally {
            migrated?.close()
            passphrase.fill(0)
            context.deleteDatabase(name)
        }
    }

    private fun scalarLong(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        query: String
    ): Long = db.query(query).use { cursor ->
        check(cursor.moveToFirst()) { "No row returned for: $query" }
        cursor.getLong(0)
    }

    private fun scalarString(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        query: String
    ): String = db.query(query).use { cursor ->
        check(cursor.moveToFirst()) { "No row returned for: $query" }
        cursor.getString(0)
    }

    private fun triggerExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        triggerName: String
    ): Boolean = scalarLong(
        db,
        "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='$triggerName'"
    ) == 1L

    private fun assertMutationRejected(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String
    ) {
        val rejected = runCatching { db.execSQL(sql) }.isFailure
        assertTrue("Expected immutable-record trigger to reject: $sql", rejected)
    }
}
