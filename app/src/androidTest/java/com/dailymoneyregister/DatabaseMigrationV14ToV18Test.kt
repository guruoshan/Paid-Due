package com.dailymoneyregister

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the real v14 -> v18 upgrade path.
 *
 * The v15 -> v16 migration rebuilds cash_shifts, whose child cash_shift_events
 * rows must survive exactly once regardless of SQLite FK/cascade behavior.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationV14ToV18Test {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    @Test
    fun v14ToV18PreservesCashShiftHistoryAndIntegrity() {
        val databaseName = "dmr-migration-v14-to-v18-cash-shift-test"
        val source = migrationHelper.createDatabase(databaseName, 14)

        source.execSQL(
            "INSERT INTO businesses (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("v14-business", "Migration Business", 100L, 100L)
        )
        source.execSQL(
            """
            INSERT INTO branches (
                id, business_id, name, code, country_code, currency_code,
                currency_decimal_places, currency_symbol, timezone_id,
                tax_rounding_mode, default_tax_rate_bps, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v14-branch", "v14-business", "Migration Branch", "MIG",
                "OM", "OMR", 3, "OMR", "Asia/Muscat", "HALF_UP", 0, 100L, 100L
            )
        )
        source.execSQL(
            """
            INSERT INTO user_accounts (
                id, identity_id, business_id, display_name, username, role,
                auth_type, credential_hash, credential_salt, language_code,
                is_active, must_change_pin, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v14-user", null, "v14-business", "Legacy Cashier", "legacy_cashier",
                "CASHIER", "PIN", "fixture-hash", "fixture-salt", "en",
                1, 0, 100L, 100L
            )
        )
        source.execSQL(
            """
            INSERT INTO operations (
                id, business_id, branch_id, operation_type, subject_type,
                subject_id, transaction_id, cash_shift_id, actor_user_id,
                device_id, occurred_at, request_fingerprint,
                command_schema_version, intent_payload, status,
                result_reference_type, result_reference_id, reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v14-operation", "v14-business", "v14-branch", "OPEN_CASH_SHIFT",
                "CASH_SHIFT", "v14-shift", null, "v14-shift", "v14-user",
                "v14-device", 200L, "v14-fingerprint", 1, "{}", "SUCCEEDED",
                "CASH_SHIFT", "v14-shift", null
            )
        )
        source.execSQL(
            "INSERT INTO cash_shifts (id, branch_id, user_id, created_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("v14-shift", "v14-branch", "v14-user", 200L)
        )
        source.execSQL(
            """
            INSERT INTO cash_shift_events (
                id, shift_id, operation_id, type, amount_minor, occurred_at, actor_user_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v14-shift-event", "v14-shift", "v14-operation",
                "OPEN", 50000L, 200L, "v14-user"
            )
        )

        // A production v14 database has these guards. Recreate that state so
        // the migration must deliberately restore the immutable event history.
        FinancialImmutabilityCallback.installGuards(source)
        source.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            18,
            true,
            DmrDatabase.MIGRATION_14_15,
            DmrDatabase.MIGRATION_15_16,
            DmrDatabase.MIGRATION_16_17,
            DmrDatabase.MIGRATION_17_18
        )

        migrated.query(
            "SELECT id, branch_id, user_id, created_at FROM cash_shifts WHERE id = 'v14-shift'"
        ).use { cursor ->
            assertTrue("Original cash shift must survive", cursor.moveToFirst())
            assertEquals("v14-shift", cursor.getString(0))
            assertEquals("v14-branch", cursor.getString(1))
            assertEquals("v14-user", cursor.getString(2))
            assertEquals(200L, cursor.getLong(3))
            assertFalse("There must be only one cash shift row", cursor.moveToNext())
        }

        migrated.query(
            """
            SELECT id, shift_id, operation_id, type, amount_minor, occurred_at, actor_user_id
            FROM cash_shift_events WHERE id = 'v14-shift-event'
            """.trimIndent()
        ).use { cursor ->
            assertTrue("Original cash-shift event must survive", cursor.moveToFirst())
            assertEquals("v14-shift-event", cursor.getString(0))
            assertEquals("v14-shift", cursor.getString(1))
            assertEquals("v14-operation", cursor.getString(2))
            assertEquals("OPEN", cursor.getString(3))
            assertEquals(50000L, cursor.getLong(4))
            assertEquals(200L, cursor.getLong(5))
            assertEquals("v14-user", cursor.getString(6))
            assertFalse("Event must exist exactly once", cursor.moveToNext())
        }

        migrated.query(
            "SELECT COUNT(*), COUNT(DISTINCT id) FROM cash_shift_events"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
        }

        migrated.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("No foreign-key violations may remain after upgrade", cursor.moveToFirst())
        }

        assertTriggerExists(migrated, "immutable_cash_shift_events_no_update")
        assertTriggerExists(migrated, "immutable_cash_shift_events_no_delete")
        assertTriggerExists(migrated, "immutable_cash_shift_events_no_replace")

        // The rebuilt table must no longer require a local user_accounts row,
        // while preserving its branch relationship.
        var branchForeignKey = false
        var userForeignKey = false
        migrated.query("PRAGMA foreign_key_list(cash_shifts)").use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.getString(2)) {
                    "branches" -> branchForeignKey = true
                    "user_accounts" -> userForeignKey = true
                }
            }
        }
        assertTrue("cash_shifts must retain its branch foreign key", branchForeignKey)
        assertFalse("cash_shifts must remove its local-user foreign key", userForeignKey)

        migrated.close()
    }

    private fun assertTriggerExists(db: SupportSQLiteDatabase, name: String) {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
            arrayOf<Any?>(name)
        ).use { cursor ->
            assertTrue("Expected immutability trigger $name", cursor.moveToFirst())
        }
    }
}
