package com.dailymoneyregister

import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialSyncOutboxV13Test {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    @Test
    fun migration12To13PreservesExistingBusiness() {
        val name = "boardlock-outbox-v12-v13-migration"

        val old = migrationHelper.createDatabase(name, 12)

        old.execSQL(
            """
            INSERT INTO businesses
                (id, name, created_at, updated_at)
            VALUES
                ('legacy-business', 'Existing Shop', 10, 10)
            """.trimIndent()
        )

        old.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            13,
            true,
            DmrDatabase.MIGRATION_12_13
        )

        migrated.query(
            "SELECT name FROM businesses WHERE id = 'legacy-business'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Existing Shop", it.getString(0))
        }

        migrated.query(
            "SELECT COUNT(*) FROM financial_sync_outbox"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }

        migrated.close()
    }

    @Test
    fun successfulCashSaleCreatesOneOutboxEntry() = withDb { db ->
        seedSale(db, "cash-one", "CASH")

        succeed(db, "cash-one")

        assertEquals(1, count(db, "cash-one"))
        assertEquals(1, total(db))
    }

    @Test
    fun pendingCashSaleDoesNotQueue() = withDb { db ->
        seedSale(db, "cash-pending", "CASH")

        assertEquals(0, count(db, "cash-pending"))
    }

    @Test
    fun failedCashSaleDoesNotQueue() = withDb { db ->
        seedSale(db, "cash-failed", "CASH")

        db.openHelper.writableDatabase.execSQL(
            """
            UPDATE operations
            SET status = 'FAILED',
                reason = 'Test failure'
            WHERE id = 'cash-failed'
            """.trimIndent()
        )

        assertEquals(0, count(db, "cash-failed"))
    }

    @Test
    fun onlineSaleDoesNotEnterCashQueue() = withDb { db ->
        seedSale(db, "online-one", "ONLINE")

        succeed(db, "online-one")

        assertEquals(0, count(db, "online-one"))
    }

    @Test
    fun mixedPaymentMustNotEnterCashOnlyQueue() = withDb { db ->
        seedSale(db, "mixed-one", "CASH")

        insertSettlement(
            db,
            "mixed-one",
            "ONLINE",
            "ONLINE_TRANSFER",
            "mixed-online"
        )

        succeed(db, "mixed-one")

        // Fail closed: an operation with multiple funding
        // categories must not be mislabeled CASH-only.
        assertEquals(0, count(db, "mixed-one"))
    }

    @Test
    fun duplicateSuccessCannotCreateSecondEntry() = withDb { db ->
        seedSale(db, "cash-duplicate", "CASH")

        succeed(db, "cash-duplicate")

        assertEquals(1, count(db, "cash-duplicate"))

        // The operation state machine prohibits a second
        // successful transition. Its outbox identity is unique.
        assertEquals(1, total(db))
    }

    @Test
    fun transactionRollbackAlsoRollsBackOutbox() = withDb { db ->
        seedSale(db, "cash-rollback", "CASH")

        try {
            runBlocking {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL(
                        """
                        UPDATE operations
                        SET status = 'SUCCEEDED',
                            result_reference_type = 'TRANSACTION',
                            result_reference_id = 'tx-cash-rollback'
                        WHERE id = 'cash-rollback'
                        """.trimIndent()
                    )
                    error("Simulated transaction interruption")
                }
            }
        } catch (_: IllegalStateException) {
            // Deliberate test rollback.
        }

        assertEquals(0, count(db, "cash-rollback"))
        assertEquals("PENDING", status(db, "cash-rollback"))
    }

    @Test
    fun obsoleteTriggerIsReplacedWithoutDeletingQueuedSales() = withDb { db ->
        seedSale(db, "g1-existing", "CASH")
        succeed(db, "g1-existing")
        assertEquals(1, count(db, "g1-existing"))

        val sql = db.openHelper.writableDatabase

        // Install an obsolete trigger only in this test database.
        sql.execSQL(
            "DROP TRIGGER financial_sync_outbox_after_cash_sale_success"
        )

        sql.execSQL(
            """
            CREATE TRIGGER financial_sync_outbox_after_cash_sale_success
            AFTER UPDATE ON operations
            BEGIN
                SELECT 1;
            END
            """.trimIndent()
        )

        // Re-run the Room open callback against the test database.
        com.dailymoneyregister.core.database.security
            .FinancialImmutabilityCallback.onOpen(sql)

        val definition = sql.query(
            """
            SELECT sql FROM sqlite_master
            WHERE type = 'trigger'
              AND name = 'financial_sync_outbox_after_cash_sale_success'
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

        assertTrue(definition.contains("AND NOT EXISTS"))
        assertTrue(definition.contains("CASH_TENDER"))

        // The already queued operation must remain untouched.
        assertEquals(1, count(db, "g1-existing"))
        assertEquals(1, total(db))

        // Reopening must be idempotent.
        com.dailymoneyregister.core.database.security
            .FinancialImmutabilityCallback.onOpen(sql)

        assertEquals(1, total(db))

        // Mixed payment must not enter the cash-only queue.
        seedSale(db, "g1-mixed", "CASH")
        insertSettlement(
            db,
            "g1-mixed",
            "ONLINE",
            "ONLINE_TRANSFER",
            "g1-mixed-online"
        )
        succeed(db, "g1-mixed")
        assertEquals(0, count(db, "g1-mixed"))

        // A new cash-only sale must still queue exactly once.
        seedSale(db, "g1-new-cash", "CASH")
        succeed(db, "g1-new-cash")

        assertEquals(1, count(db, "g1-new-cash"))
        assertEquals(2, total(db))
    }
    private fun withDb(block: (DmrDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<
            android.content.Context
        >()

        val db = DmrDatabase.createInMemory(context)

        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun seedSale(
        db: DmrDatabase,
        operationId: String,
        category: String
    ) {
        val sql = db.openHelper.writableDatabase
        val tx = "tx-$operationId"

        sql.execSQL(
            """
            INSERT OR IGNORE INTO businesses
                (id, name, created_at, updated_at)
            VALUES ('business-test', 'Test Shop', 1, 1)
            """
        )

        sql.execSQL(
            """
            INSERT OR IGNORE INTO branches (
                id, business_id, name, code,
                country_code, currency_code,
                currency_decimal_places, currency_symbol,
                timezone_id, tax_rounding_mode,
                default_tax_rate_bps, created_at, updated_at
            ) VALUES (
                'branch-test', 'business-test', 'Main', 'MAIN',
                'OM', 'OMR', 3, 'OMR',
                'Asia/Muscat', 'HALF_UP',
                0, 1, 1
            )
            """
        )

        sql.execSQL(
            """
            INSERT INTO operations (
                id, business_id, branch_id,
                operation_type, subject_type, subject_id,
                transaction_id, actor_user_id, device_id,
                occurred_at, request_fingerprint,
                command_schema_version, intent_payload, status
            ) VALUES (
                '$operationId', 'business-test', 'branch-test',
                'RECORD_SALE', 'TRANSACTION', '$tx',
                '$tx', 'cashier-test', 'device-test',
                100, 'fingerprint-test', 1, '{}', 'PENDING'
            )
            """
        )

        sql.execSQL(
            """
            INSERT INTO transactions (
                id, branch_id, type,
                subtotal_minor, tax_rate_bps,
                taxable_base_minor, tax_amount_minor,
                tx_shipping_minor, tx_packing_minor,
                grand_total_minor,
                currency_code, currency_decimal_places,
                created_by_user_id, device_id,
                created_at, display_ref
            ) VALUES (
                '$tx', 'branch-test', 'SALE',
                1000, 0, 1000, 0,
                0, 0, 1000,
                'OMR', 3,
                'cashier-test', 'device-test',
                100, '$tx'
            )
            """
        )

        val channel =
            if (category == "CASH") "CASH_TENDER"
            else "ONLINE_TRANSFER"

        insertSettlement(
            db,
            operationId,
            category,
            channel,
            "settlement-$operationId"
        )
    }

    private fun insertSettlement(
        db: DmrDatabase,
        operationId: String,
        category: String,
        channel: String,
        settlementId: String
    ) {
        val tx = "tx-$operationId"

        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO settlement_entries (
                id, operation_id, subject_type,
                subject_id, transaction_id,
                entry_purpose, category, channel,
                amount_signed_minor, currency_code,
                confirmation_type, occurred_at, actor_user_id
            ) VALUES (
                '$settlementId', '$operationId', 'TRANSACTION',
                '$tx', '$tx', 'SALE_FUNDING', '$category',
                '$channel', 1000, 'OMR',
                'CASHIER_CONFIRMED', 100, 'cashier-test'
            )
            """
        )
    }

    private fun succeed(db: DmrDatabase, operationId: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            UPDATE operations
            SET status = 'SUCCEEDED',
                result_reference_type = 'TRANSACTION',
                result_reference_id = 'tx-$operationId'
            WHERE id = '$operationId'
            """.trimIndent()
        )
    }

    private fun count(db: DmrDatabase, operationId: String): Int {
        db.openHelper.writableDatabase.query(
            """
            SELECT COUNT(*) FROM financial_sync_outbox
            WHERE operation_id = '$operationId'
            """
        ).use {
            assertTrue(it.moveToFirst())
            return it.getInt(0)
        }
    }

    private fun total(db: DmrDatabase): Int {
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM financial_sync_outbox"
        ).use {
            assertTrue(it.moveToFirst())
            return it.getInt(0)
        }
    }

    private fun status(db: DmrDatabase, operationId: String): String {
        db.openHelper.writableDatabase.query(
            """
            SELECT status FROM operations
            WHERE id = '$operationId'
            """
        ).use {
            assertTrue(it.moveToFirst())
            return it.getString(0)
        }
    }
}