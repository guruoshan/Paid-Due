package com.dailymoneyregister

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import org.junit.Assert.assertEquals
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
    fun testMigration3To4PreservesSettlementIntegrity() {
        val databaseName = "dmr-migration-3-4-settlement-integrity-test"
        val source = migrationHelper.createDatabase(databaseName, 3)

        insertMigrationBusinessAndBranch(source)
        insertMigrationTransaction(
            source,
            transactionId = "migration-tx",
            displayRef = "MIG-001"
        )

        insertMigrationOperation(
            source,
            operationId = "migration-op-linked",
            transactionId = "migration-tx"
        )
        insertMigrationOperation(
            source,
            operationId = "migration-op-null",
            transactionId = null
        )

        insertMigrationSettlement(
            source,
            settlementId = "migration-settlement-linked",
            operationId = "migration-op-linked",
            subjectId = "migration-tx",
            transactionId = "migration-tx",
            amountMinor = 1234L,
            qrProfileId = "qr-profile-snapshot",
            providerRef = "provider-reference"
        )

        insertMigrationSettlement(
            source,
            settlementId = "migration-settlement-null",
            operationId = "migration-op-null",
            subjectId = "standalone-subject",
            transactionId = null,
            amountMinor = 567L,
            qrProfileId = null,
            providerRef = null
        )

        source.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            DmrDatabase.MIGRATION_3_4
        )

        migrated.query(
            """
            SELECT
                id,
                operation_id,
                subject_type,
                subject_id,
                transaction_id,
                entry_purpose,
                category,
                channel,
                amount_signed_minor,
                currency_code,
                confirmation_type,
                qr_profile_id_snapshot,
                occurred_at,
                actor_user_id,
                provider_ref
            FROM settlement_entries
            WHERE id = 'migration-settlement-linked'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("migration-settlement-linked", cursor.getString(0))
            assertEquals("migration-op-linked", cursor.getString(1))
            assertEquals("TRANSACTION", cursor.getString(2))
            assertEquals("migration-tx", cursor.getString(3))
            assertEquals("migration-tx", cursor.getString(4))
            assertEquals("SALE_FUNDING", cursor.getString(5))
            assertEquals("CASH", cursor.getString(6))
            assertEquals("CASH_TENDER", cursor.getString(7))
            assertEquals(1234L, cursor.getLong(8))
            assertEquals("OMR", cursor.getString(9))
            assertEquals("CASHIER_CONFIRMED", cursor.getString(10))
            assertEquals("qr-profile-snapshot", cursor.getString(11))
            assertEquals(1111L, cursor.getLong(12))
            assertEquals("migration-user", cursor.getString(13))
            assertEquals("provider-reference", cursor.getString(14))
        }

        migrated.query(
            """
            SELECT transaction_id, amount_signed_minor
            FROM settlement_entries
            WHERE id = 'migration-settlement-null'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(567L, cursor.getLong(1))
        }

        migrated.query(
            "SELECT COUNT(*) FROM settlement_entries"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
        }

        assertSettlementForeignKeys(migrated)
        assertSettlementIndexes(migrated)
        assertImmutabilityGuards(migrated)

        migrated.close()
    }

    @Test
    fun testMigration3To4FailsClosedForExistingOrphanSettlement() {
        val databaseName = "dmr-migration-3-4-orphan-fail-closed-test"
        val source = migrationHelper.createDatabase(databaseName, 3)

        insertMigrationOperation(
            source,
            operationId = "migration-op-orphan",
            transactionId = "missing-tx"
        )

        insertMigrationSettlement(
            source,
            settlementId = "migration-settlement-orphan",
            operationId = "migration-op-orphan",
            subjectId = "missing-tx",
            transactionId = "missing-tx",
            amountMinor = 999L,
            qrProfileId = null,
            providerRef = "orphan-provider-ref"
        )

        source.close()

        var failure: Throwable? = null

        try {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                4,
                true,
                DmrDatabase.MIGRATION_3_4
            ).close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(
            "Migration must fail when v3 contains a non-null orphan transaction_id",
            failure
        )

        assertThrowableChainContains(
            failure,
            "MIGRATION_3_4 blocked: settlement_entries contains an orphan transaction_id"
        )

        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val databaseFile =
            context.getDatabasePath(databaseName)

        val rawDatabase = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            rawDatabase.rawQuery(
                "PRAGMA user_version",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "Failed migration must leave the database at v3",
                    3,
                    cursor.getInt(0)
                )
            }

            rawDatabase.rawQuery(
                """
                SELECT
                    id,
                    operation_id,
                    transaction_id,
                    amount_signed_minor,
                    provider_ref
                FROM settlement_entries
                WHERE id = 'migration-settlement-orphan'
                """.trimIndent(),
                null
            ).use { cursor ->
                assertTrue(
                    "Original orphan settlement must remain intact after failed migration",
                    cursor.moveToFirst()
                )
                assertEquals(
                    "migration-settlement-orphan",
                    cursor.getString(0)
                )
                assertEquals(
                    "migration-op-orphan",
                    cursor.getString(1)
                )
                assertEquals(
                    "missing-tx",
                    cursor.getString(2)
                )
                assertEquals(
                    999L,
                    cursor.getLong(3)
                )
                assertEquals(
                    "orphan-provider-ref",
                    cursor.getString(4)
                )
            }
        } finally {
            rawDatabase.close()
        }
    }
    @Test
    fun testMigration1To2To3To4PreservesCompleteUpgradeChain() {
        val databaseName = "dmr-migration-1-2-3-4-chain-test"

        migrationHelper
            .createDatabase(databaseName, 1)
            .close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            DmrDatabase.MIGRATION_1_2,
            DmrDatabase.MIGRATION_2_3,
            DmrDatabase.MIGRATION_3_4
        )

        assertImmutabilityGuards(migrated)
        assertSettlementForeignKeys(migrated)
        assertSettlementIndexes(migrated)

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='human_identities'"
        ).use {
            assertTrue(it.moveToFirst())
        }

        migrated.query(
            "PRAGMA table_info(user_accounts)"
        ).use { cursor ->
            var foundIdentity = false
            var foundMustChange = false

            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                foundIdentity =
                    foundIdentity || name == "identity_id"
                foundMustChange =
                    foundMustChange || name == "must_change_pin"
            }

            assertTrue(foundIdentity)
            assertTrue(foundMustChange)
        }

        migrated.close()
    }

    @Test
    fun testMigration4To5PreservesValidVoidAndCreatesUniqueIndexes() {
        val databaseName = "dmr-migration-4-5-valid-void-test"
        val source = migrationHelper.createDatabase(databaseName, 4)

        /*
         * MigrationTestHelper creates the exported v4 Room schema but does
         * not execute the production Room callback.
         *
         * A real production v4 database has financial immutability guards,
         * so reproduce that production state before testing whether 4 -> 5
         * preserves it.
         */
        com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
            .installGuards(source)

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v45-valid-tx",
            displayRef = "V45-VALID"
        )

        insertMigrationVoidOperation(
            source,
            operationId = "v45-valid-void-op",
            transactionId = "v45-valid-tx",
            occurredAt = 2000L
        )

        insertMigrationVoidEvent(
            source,
            eventId = "v45-valid-void-event",
            transactionId = "v45-valid-tx",
            operationId = "v45-valid-void-op",
            reason = "Legacy valid Void",
            occurredAt = 2000L
        )

        source.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            DmrDatabase.MIGRATION_4_5
        )

        migrated.query(
            """
            SELECT
                id,
                transaction_id,
                operation_id,
                reason,
                actor_user_id,
                occurred_at
            FROM void_events
            WHERE id = 'v45-valid-void-event'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v45-valid-void-event", cursor.getString(0))
            assertEquals("v45-valid-tx", cursor.getString(1))
            assertEquals("v45-valid-void-op", cursor.getString(2))
            assertEquals("Legacy valid Void", cursor.getString(3))
            assertEquals("migration-user", cursor.getString(4))
            assertEquals(2000L, cursor.getLong(5))
        }

        assertVoidUniquenessIndexes(migrated)
        assertImmutabilityGuards(migrated)

        migrated.close()
    }

    @Test
    fun testMigration4To5FailsClosedForDuplicateVoidTransaction() {
        val databaseName = "dmr-migration-4-5-duplicate-transaction-test"
        val source = migrationHelper.createDatabase(databaseName, 4)

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v45-duplicate-tx",
            displayRef = "V45-DUP-TX"
        )

        insertMigrationVoidOperation(
            source,
            operationId = "v45-duplicate-tx-op-1",
            transactionId = "v45-duplicate-tx",
            occurredAt = 2100L
        )

        insertMigrationVoidOperation(
            source,
            operationId = "v45-duplicate-tx-op-2",
            transactionId = "v45-duplicate-tx",
            occurredAt = 2200L
        )

        insertMigrationVoidEvent(
            source,
            eventId = "v45-duplicate-tx-event-1",
            transactionId = "v45-duplicate-tx",
            operationId = "v45-duplicate-tx-op-1",
            reason = "First legacy Void",
            occurredAt = 2100L
        )

        insertMigrationVoidEvent(
            source,
            eventId = "v45-duplicate-tx-event-2",
            transactionId = "v45-duplicate-tx",
            operationId = "v45-duplicate-tx-op-2",
            reason = "Second legacy Void",
            occurredAt = 2200L
        )

        source.close()

        var failure: Throwable? = null

        try {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                5,
                true,
                DmrDatabase.MIGRATION_4_5
            ).close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(
            "Migration must fail closed when v4 contains duplicate Void transaction_id",
            failure
        )

        assertThrowableChainContains(
            failure,
            "MIGRATION_4_5 blocked: duplicate void_events.transaction_id"
        )

        assertFailedV45MigrationPreservedSource(
            databaseName = databaseName,
            expectedVoidCount = 2L
        )
    }

    @Test
    fun testMigration4To5FailsClosedForDuplicateVoidOperation() {
        val databaseName = "dmr-migration-4-5-duplicate-operation-test"
        val source = migrationHelper.createDatabase(databaseName, 4)

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v45-op-tx-a",
            displayRef = "V45-OP-A"
        )

        insertMigrationTransaction(
            source,
            transactionId = "v45-op-tx-b",
            displayRef = "V45-OP-B"
        )

        /*
         * Deliberately adversarial v4 state:
         * one operation_id referenced by two Void rows.
         *
         * v4 allowed this because operation_id was not UNIQUE.
         */
        insertMigrationVoidOperation(
            source,
            operationId = "v45-shared-void-op",
            transactionId = "v45-op-tx-a",
            occurredAt = 2300L
        )

        insertMigrationVoidEvent(
            source,
            eventId = "v45-op-event-a",
            transactionId = "v45-op-tx-a",
            operationId = "v45-shared-void-op",
            reason = "Legacy Void A",
            occurredAt = 2300L
        )

        insertMigrationVoidEvent(
            source,
            eventId = "v45-op-event-b",
            transactionId = "v45-op-tx-b",
            operationId = "v45-shared-void-op",
            reason = "Legacy Void B",
            occurredAt = 2400L
        )

        source.close()

        var failure: Throwable? = null

        try {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                5,
                true,
                DmrDatabase.MIGRATION_4_5
            ).close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(
            "Migration must fail closed when v4 contains duplicate Void operation_id",
            failure
        )

        assertThrowableChainContains(
            failure,
            "MIGRATION_4_5 blocked: duplicate void_events.operation_id"
        )

        assertFailedV45MigrationPreservedSource(
            databaseName = databaseName,
            expectedVoidCount = 2L
        )
    }

    @Test
    fun testMigration1To2To3To4To5PreservesCompleteUpgradeChain() {
        val databaseName = "dmr-migration-1-2-3-4-5-chain-test"

        migrationHelper
            .createDatabase(databaseName, 1)
            .close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            DmrDatabase.MIGRATION_1_2,
            DmrDatabase.MIGRATION_2_3,
            DmrDatabase.MIGRATION_3_4,
            DmrDatabase.MIGRATION_4_5
        )

        assertImmutabilityGuards(migrated)
        assertSettlementForeignKeys(migrated)
        assertSettlementIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)

        migrated.close()
    }
    @Test
    fun testMigration5To6PreservesMultipleRefundsAndCreatesUniqueOperationIndex() {
        val databaseName = "dmr-migration-5-6-valid-refunds-test"
        val source = migrationHelper.createDatabase(databaseName, 5)

        com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
            .installGuards(source)

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v56-refund-tx",
            displayRef = "V56-REFUND"
        )

        insertMigrationRefundOperation(
            source,
            operationId = "v56-refund-op-1",
            transactionId = "v56-refund-tx",
            occurredAt = 3100L
        )

        insertMigrationRefundOperation(
            source,
            operationId = "v56-refund-op-2",
            transactionId = "v56-refund-tx",
            occurredAt = 3200L
        )

        insertMigrationRefundEvent(
            source,
            eventId = "v56-refund-event-1",
            transactionId = "v56-refund-tx",
            operationId = "v56-refund-op-1",
            refundAmountMinor = 300L,
            reason = "First partial refund",
            occurredAt = 3100L
        )

        insertMigrationRefundEvent(
            source,
            eventId = "v56-refund-event-2",
            transactionId = "v56-refund-tx",
            operationId = "v56-refund-op-2",
            refundAmountMinor = 200L,
            reason = "Second partial refund",
            occurredAt = 3200L
        )

        source.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            DmrDatabase.MIGRATION_5_6
        )

        migrated.query(
            """
            SELECT operation_id, refund_amount_minor
            FROM refund_events
            WHERE transaction_id = 'v56-refund-tx'
            ORDER BY occurred_at ASC
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v56-refund-op-1", cursor.getString(0))
            assertEquals(300L, cursor.getLong(1))

            assertTrue(cursor.moveToNext())
            assertEquals("v56-refund-op-2", cursor.getString(0))
            assertEquals(200L, cursor.getLong(1))

            assertTrue(
                "Exactly two valid partial Refund rows must survive migration",
                !cursor.moveToNext()
            )
        }

        assertRefundUniquenessIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)
        assertImmutabilityGuards(migrated)

        migrated.close()
    }

    @Test
    fun testMigration5To6FailsClosedForDuplicateRefundOperation() {
        val databaseName =
            "dmr-migration-5-6-duplicate-refund-operation-test"

        val source =
            migrationHelper.createDatabase(databaseName, 5)

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v56-duplicate-refund-tx",
            displayRef = "V56-DUP-REFUND"
        )

        insertMigrationRefundOperation(
            source,
            operationId = "v56-shared-refund-op",
            transactionId = "v56-duplicate-refund-tx",
            occurredAt = 3300L
        )

        /*
         * Deliberately adversarial v5 state:
         * two RefundEvent rows reference the same operation_id.
         */
        insertMigrationRefundEvent(
            source,
            eventId = "v56-duplicate-refund-event-1",
            transactionId = "v56-duplicate-refund-tx",
            operationId = "v56-shared-refund-op",
            refundAmountMinor = 300L,
            reason = "Duplicate refund A",
            occurredAt = 3300L
        )

        insertMigrationRefundEvent(
            source,
            eventId = "v56-duplicate-refund-event-2",
            transactionId = "v56-duplicate-refund-tx",
            operationId = "v56-shared-refund-op",
            refundAmountMinor = 200L,
            reason = "Duplicate refund B",
            occurredAt = 3400L
        )

        source.close()

        var failure: Throwable? = null

        try {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                6,
                true,
                DmrDatabase.MIGRATION_5_6
            ).close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(
            "Migration must fail closed when v5 contains duplicate Refund operation_id",
            failure
        )

        assertThrowableChainContains(
            failure,
            "MIGRATION_5_6 blocked: duplicate refund_events.operation_id"
        )

        assertFailedV56MigrationPreservedSource(
            databaseName = databaseName,
            expectedRefundCount = 2L
        )
    }

    @Test
    fun testMigration1To2To3To4To5To6PreservesCompleteUpgradeChain() {
        val databaseName =
            "dmr-migration-1-2-3-4-5-6-chain-test"

        migrationHelper
            .createDatabase(databaseName, 1)
            .close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            DmrDatabase.MIGRATION_1_2,
            DmrDatabase.MIGRATION_2_3,
            DmrDatabase.MIGRATION_3_4,
            DmrDatabase.MIGRATION_4_5,
            DmrDatabase.MIGRATION_5_6
        )

        assertImmutabilityGuards(migrated)
        assertSettlementForeignKeys(migrated)
        assertSettlementIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)
        assertRefundUniquenessIndexes(migrated)

        migrated.close()
    }
    @Test
    fun testMigration6To7PreservesPaymentMethodChangeChainAndCreatesUniqueOperationIndex() {
        val databaseName =
            "dmr-migration-6-7-valid-payment-change-test"

        val source =
            migrationHelper.createDatabase(
                databaseName,
                6
            )

        com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
            .installGuards(source)

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v67-payment-change-tx",
            displayRef = "V67-PAYMENT"
        )

        insertMigrationPaymentChangeOperation(
            source,
            operationId = "v67-payment-op-1",
            transactionId = "v67-payment-change-tx",
            occurredAt = 4100L
        )

        insertMigrationPaymentChangeOperation(
            source,
            operationId = "v67-payment-op-2",
            transactionId = "v67-payment-change-tx",
            occurredAt = 4200L
        )

        insertMigrationPaymentChangeEvent(
            source,
            eventId = "v67-payment-event-1",
            transactionId = "v67-payment-change-tx",
            operationId = "v67-payment-op-1",
            previousMethod = "CASH",
            newMethod = "ONLINE",
            occurredAt = 4100L
        )

        insertMigrationPaymentChangeEvent(
            source,
            eventId = "v67-payment-event-2",
            transactionId = "v67-payment-change-tx",
            operationId = "v67-payment-op-2",
            previousMethod = "ONLINE",
            newMethod = "CASH",
            occurredAt = 4200L
        )

        source.close()

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                7,
                true,
                DmrDatabase.MIGRATION_6_7
            )

        migrated.query(
            """
            SELECT operation_id, previous_method, new_method
            FROM payment_method_change_events
            WHERE transaction_id = 'v67-payment-change-tx'
            ORDER BY occurred_at ASC
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "v67-payment-op-1",
                cursor.getString(0)
            )
            assertEquals(
                "CASH",
                cursor.getString(1)
            )
            assertEquals(
                "ONLINE",
                cursor.getString(2)
            )

            assertTrue(cursor.moveToNext())
            assertEquals(
                "v67-payment-op-2",
                cursor.getString(0)
            )
            assertEquals(
                "ONLINE",
                cursor.getString(1)
            )
            assertEquals(
                "CASH",
                cursor.getString(2)
            )

            assertTrue(!cursor.moveToNext())
        }

        assertPaymentMethodChangeUniquenessIndexes(
            migrated
        )

        assertRefundUniquenessIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)
        assertImmutabilityGuards(migrated)

        migrated.close()
    }

    @Test
    fun testMigration6To7FailsClosedForDuplicatePaymentChangeOperation() {
        val databaseName =
            "dmr-migration-6-7-duplicate-payment-operation-test"

        val source =
            migrationHelper.createDatabase(
                databaseName,
                6
            )

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v67-duplicate-payment-tx",
            displayRef = "V67-DUP-PAYMENT"
        )

        insertMigrationPaymentChangeOperation(
            source,
            operationId = "v67-shared-payment-op",
            transactionId = "v67-duplicate-payment-tx",
            occurredAt = 4300L
        )

        insertMigrationPaymentChangeEvent(
            source,
            eventId = "v67-duplicate-payment-event-1",
            transactionId = "v67-duplicate-payment-tx",
            operationId = "v67-shared-payment-op",
            previousMethod = "CASH",
            newMethod = "ONLINE",
            occurredAt = 4300L
        )

        insertMigrationPaymentChangeEvent(
            source,
            eventId = "v67-duplicate-payment-event-2",
            transactionId = "v67-duplicate-payment-tx",
            operationId = "v67-shared-payment-op",
            previousMethod = "ONLINE",
            newMethod = "CASH",
            occurredAt = 4400L
        )

        source.close()

        var failure: Throwable? = null

        try {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                7,
                true,
                DmrDatabase.MIGRATION_6_7
            ).close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(
            "Migration must fail closed for duplicate payment-change operation_id",
            failure
        )

        assertThrowableChainContains(
            failure,
            "MIGRATION_6_7 blocked: duplicate payment_method_change_events.operation_id"
        )

        assertFailedV67MigrationPreservedSource(
            databaseName = databaseName,
            expectedEventCount = 2L
        )
    }

    @Test
    fun testMigration1To2To3To4To5To6To7PreservesCompleteUpgradeChain() {
        val databaseName =
            "dmr-migration-1-2-3-4-5-6-7-chain-test"

        migrationHelper
            .createDatabase(
                databaseName,
                1
            )
            .close()

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                7,
                true,
                DmrDatabase.MIGRATION_1_2,
                DmrDatabase.MIGRATION_2_3,
                DmrDatabase.MIGRATION_3_4,
                DmrDatabase.MIGRATION_4_5,
                DmrDatabase.MIGRATION_5_6,
                DmrDatabase.MIGRATION_6_7
            )

        assertImmutabilityGuards(migrated)
        assertSettlementForeignKeys(migrated)
        assertSettlementIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)
        assertRefundUniquenessIndexes(migrated)
        assertPaymentMethodChangeUniquenessIndexes(
            migrated
        )

        migrated.close()
    }
    @Test
    fun testMigration7To8AddsPaymentChannelIdentityWhenNoLegacyPaymentChanges() {
        val databaseName =
            "dmr-migration-7-8-payment-channel-identity-test"

        val source =
            migrationHelper.createDatabase(
                databaseName,
                7
            )

        com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
            .installGuards(source)

        source.close()

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                8,
                true,
                DmrDatabase.MIGRATION_7_8
            )

        assertPaymentMethodChangeChannelColumns(migrated)
        assertPaymentMethodChangeUniquenessIndexes(migrated)
        assertRefundUniquenessIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)
        assertImmutabilityGuards(migrated)

        migrated.close()
    }

    @Test
    fun testMigration7To8FailsClosedForLegacyPaymentChangeWithoutChannelIdentity() {
        val databaseName =
            "dmr-migration-7-8-legacy-payment-change-test"

        val source =
            migrationHelper.createDatabase(
                databaseName,
                7
            )

        insertMigrationBusinessAndBranch(source)

        insertMigrationTransaction(
            source,
            transactionId = "v78-payment-change-tx",
            displayRef = "V78-PAYMENT"
        )

        insertMigrationPaymentChangeOperation(
            source,
            operationId = "v78-payment-op",
            transactionId = "v78-payment-change-tx",
            occurredAt = 5100L
        )

        insertMigrationPaymentChangeEvent(
            source,
            eventId = "v78-payment-event",
            transactionId = "v78-payment-change-tx",
            operationId = "v78-payment-op",
            previousMethod = "CASH",
            newMethod = "ONLINE",
            occurredAt = 5100L
        )

        source.close()

        var failure: Throwable? = null

        try {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                8,
                true,
                DmrDatabase.MIGRATION_7_8
            ).close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(
            "Migration must fail closed rather than invent legacy payment channel identity",
            failure
        )

        assertThrowableChainContains(
            failure,
            "MIGRATION_7_8 blocked: legacy payment_method_change_events lack channel identity"
        )

        assertFailedV78MigrationPreservedSource(databaseName)
    }

    @Test
    fun testMigration1To2To3To4To5To6To7To8PreservesCompleteUpgradeChain() {
        val databaseName =
            "dmr-migration-1-2-3-4-5-6-7-8-chain-test"

        migrationHelper
            .createDatabase(
                databaseName,
                1
            )
            .close()

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                8,
                true,
                DmrDatabase.MIGRATION_1_2,
                DmrDatabase.MIGRATION_2_3,
                DmrDatabase.MIGRATION_3_4,
                DmrDatabase.MIGRATION_4_5,
                DmrDatabase.MIGRATION_5_6,
                DmrDatabase.MIGRATION_6_7,
                DmrDatabase.MIGRATION_7_8
            )

        assertImmutabilityGuards(migrated)
        assertSettlementForeignKeys(migrated)
        assertSettlementIndexes(migrated)
        assertVoidUniquenessIndexes(migrated)
        assertRefundUniquenessIndexes(migrated)
        assertPaymentMethodChangeUniquenessIndexes(migrated)
        assertPaymentMethodChangeChannelColumns(migrated)

        migrated.close()
    }

    @Test
    fun testMigration8To9AddsCustomerLinkAndKeepsExistingDocuments() {
        val databaseName = "dmr-migration-8-9-customer-link-test"
        val source = migrationHelper.createDatabase(databaseName, 8)

        insertMigrationBusinessAndBranch(source)
        insertMigrationOperation(source, "migration-op-doc", null)
        source.execSQL(
            """
            INSERT INTO documents (
                id, document_type, subject_type, subject_id, transaction_id,
                operation_id, snapshot_json, display_number, created_at,
                created_by_user_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "migration-doc-old", "INVOICE", "DOCUMENT", "migration-doc-old", null,
                "migration-op-doc", "{\"kept\":true}", "INV-MIG-2026-000001", 1200L,
                "migration-user"
            )
        )
        source.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            DmrDatabase.MIGRATION_8_9
        )

        // Existing document survives untouched, with no customer link.
        migrated.query(
            "SELECT snapshot_json, display_number, customer_id FROM documents WHERE id = 'migration-doc-old'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{\"kept\":true}", cursor.getString(0))
            assertEquals("INV-MIG-2026-000001", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }

        // The new column points at customers(id) and is indexed.
        var foreignKeyFound = false
        migrated.query("PRAGMA foreign_key_list(documents)").use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(cursor.getColumnIndexOrThrow("table"))
                val from = cursor.getString(cursor.getColumnIndexOrThrow("from"))
                if (table == "customers" && from == "customer_id") foreignKeyFound = true
            }
        }
        assertTrue("documents.customer_id must reference customers(id)", foreignKeyFound)

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_documents_customer_id'"
        ).use { cursor ->
            assertTrue("Missing index_documents_customer_id", cursor.moveToFirst())
        }

        migrated.close()
    }

    @Test
    fun testMigration1ThroughNineChainPreservesUpgradePath() {
        val databaseName = "dmr-migration-1-to-9-chain-test"

        migrationHelper.createDatabase(databaseName, 1).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            DmrDatabase.MIGRATION_1_2,
            DmrDatabase.MIGRATION_2_3,
            DmrDatabase.MIGRATION_3_4,
            DmrDatabase.MIGRATION_4_5,
            DmrDatabase.MIGRATION_5_6,
            DmrDatabase.MIGRATION_6_7,
            DmrDatabase.MIGRATION_7_8,
            DmrDatabase.MIGRATION_8_9
        )

        assertImmutabilityGuards(migrated)
        migrated.close()
    }

    @Test
    fun testEncryptedDiskDatabaseUsesTestOnlyNameAndFinancialGuards() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = AndroidTestDatabaseFactory.requireTestDatabaseName(
            "dmr-production-builder-configuration-test.db"
        )

        val db = AndroidTestDatabaseFactory.createEncryptedDisk(
            context = context,
            databaseName = databaseName
        )

        try {
            val writableDatabase = db.openHelper.writableDatabase

            assertTrue(
                "Database connection should be open",
                db.isOpen
            )

            assertImmutabilityGuards(writableDatabase)
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun insertMigrationPaymentChangeOperation(
        db: SupportSQLiteDatabase,
        operationId: String,
        transactionId: String,
        occurredAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO operations (
                id,
                business_id,
                branch_id,
                operation_type,
                subject_type,
                subject_id,
                transaction_id,
                actor_user_id,
                device_id,
                occurred_at,
                request_fingerprint,
                command_schema_version,
                intent_payload,
                status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                operationId,
                "migration-business",
                "migration-branch",
                "RECORD_PAYMENT_METHOD_CHANGE",
                "TRANSACTION",
                transactionId,
                transactionId,
                "migration-user",
                "migration-device",
                occurredAt,
                "fingerprint-$operationId",
                1,
                "fixture-$operationId",
                "SUCCEEDED"
            )
        )
    }

    private fun insertMigrationPaymentChangeEvent(
        db: SupportSQLiteDatabase,
        eventId: String,
        transactionId: String,
        operationId: String,
        previousMethod: String,
        newMethod: String,
        occurredAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO payment_method_change_events (
                id,
                transaction_id,
                operation_id,
                previous_method,
                new_method,
                reason,
                actor_user_id,
                occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                eventId,
                transactionId,
                operationId,
                previousMethod,
                newMethod,
                "Migration fixture",
                "migration-user",
                occurredAt
            )
        )
    }

    private fun assertPaymentMethodChangeUniquenessIndexes(
        db: SupportSQLiteDatabase
    ) {
        val indexes =
            mutableMapOf<String, Int>()

        db.query(
            "PRAGMA index_list(payment_method_change_events)"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                indexes[cursor.getString(1)] =
                    cursor.getInt(2)
            }
        }

        assertEquals(
            "Payment-change transaction_id must remain NON-UNIQUE",
            0,
            indexes[
                "index_payment_method_change_events_transaction_id"
            ]
        )

        assertEquals(
            "Payment-change operation_id must be UNIQUE",
            1,
            indexes[
                "index_payment_method_change_events_operation_id"
            ]
        )

        assertEquals(
            "Payment-change occurred_at must remain NON-UNIQUE",
            0,
            indexes[
                "index_payment_method_change_events_occurred_at"
            ]
        )

        db.query(
            """
            PRAGMA index_info(
                index_payment_method_change_events_operation_id
            )
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "operation_id",
                cursor.getString(2)
            )
        }
    }

    private fun assertPaymentMethodChangeChannelColumns(
        db: SupportSQLiteDatabase
    ) {
        val columns =
            mutableMapOf<String, Int>()

        db.query(
            "PRAGMA table_info(payment_method_change_events)"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                columns[cursor.getString(1)] =
                    cursor.getInt(3)
            }
        }

        assertEquals(
            "previous_channel must exist and remain nullable for non-tender categories",
            0,
            columns["previous_channel"]
        )

        assertEquals(
            "new_channel must exist and remain nullable for non-tender categories",
            0,
            columns["new_channel"]
        )
    }

    private fun assertFailedV78MigrationPreservedSource(
        databaseName: String
    ) {
        val context =
            ApplicationProvider
                .getApplicationContext<Context>()

        val databaseFile =
            context.getDatabasePath(databaseName)

        val rawDatabase =
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

        try {
            rawDatabase.rawQuery(
                "PRAGMA user_version",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())

                assertEquals(
                    "Failed v7 -> v8 migration must leave database at v7",
                    7,
                    cursor.getInt(0)
                )
            }

            rawDatabase.rawQuery(
                "SELECT COUNT(*) FROM payment_method_change_events",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }

            rawDatabase.rawQuery(
                "PRAGMA table_info(payment_method_change_events)",
                null
            ).use { cursor ->
                val names =
                    mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    names += cursor.getString(1)
                }

                assertTrue("previous_channel" !in names)
                assertTrue("new_channel" !in names)
            }
        } finally {
            rawDatabase.close()
        }
    }

    private fun assertFailedV67MigrationPreservedSource(
        databaseName: String,
        expectedEventCount: Long
    ) {
        val context =
            ApplicationProvider
                .getApplicationContext<Context>()

        val databaseFile =
            context.getDatabasePath(databaseName)

        val rawDatabase =
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

        try {
            rawDatabase.rawQuery(
                "PRAGMA user_version",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())

                assertEquals(
                    "Failed v6 -> v7 migration must leave database at v6",
                    6,
                    cursor.getInt(0)
                )
            }

            rawDatabase.rawQuery(
                """
                SELECT
                    COUNT(*),
                    COUNT(DISTINCT operation_id)
                FROM payment_method_change_events
                """.trimIndent(),
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())

                assertEquals(
                    expectedEventCount,
                    cursor.getLong(0)
                )

                assertEquals(
                    1L,
                    cursor.getLong(1)
                )
            }
        } finally {
            rawDatabase.close()
        }
    }
    private fun insertMigrationRefundOperation(
        db: SupportSQLiteDatabase,
        operationId: String,
        transactionId: String,
        occurredAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO operations (
                id,
                business_id,
                branch_id,
                operation_type,
                subject_type,
                subject_id,
                transaction_id,
                actor_user_id,
                device_id,
                occurred_at,
                request_fingerprint,
                command_schema_version,
                intent_payload,
                status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                operationId,
                "migration-business",
                "migration-branch",
                "RECORD_REFUND",
                "TRANSACTION",
                transactionId,
                transactionId,
                "migration-user",
                "migration-device",
                occurredAt,
                "fingerprint-$operationId",
                1,
                "fixture-$operationId",
                "SUCCEEDED"
            )
        )
    }

    private fun insertMigrationRefundEvent(
        db: SupportSQLiteDatabase,
        eventId: String,
        transactionId: String,
        operationId: String,
        refundAmountMinor: Long,
        reason: String?,
        occurredAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO refund_events (
                id,
                transaction_id,
                operation_id,
                refund_amount_minor,
                reason,
                actor_user_id,
                occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                eventId,
                transactionId,
                operationId,
                refundAmountMinor,
                reason,
                "migration-user",
                occurredAt
            )
        )
    }

    private fun assertRefundUniquenessIndexes(
        db: SupportSQLiteDatabase
    ) {
        val indexes = mutableMapOf<String, Int>()

        db.query(
            "PRAGMA index_list(refund_events)"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                indexes[cursor.getString(1)] =
                    cursor.getInt(2)
            }
        }

        assertEquals(
            "Refund transaction_id must remain NON-UNIQUE",
            0,
            indexes["index_refund_events_transaction_id"]
        )

        assertEquals(
            "Refund operation_id must be UNIQUE",
            1,
            indexes["index_refund_events_operation_id"]
        )

        assertEquals(
            "Refund occurred_at must remain NON-UNIQUE",
            0,
            indexes["index_refund_events_occurred_at"]
        )

        db.query(
            "PRAGMA index_info(index_refund_events_transaction_id)"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "transaction_id",
                cursor.getString(2)
            )
        }

        db.query(
            "PRAGMA index_info(index_refund_events_operation_id)"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "operation_id",
                cursor.getString(2)
            )
        }
    }

    private fun assertFailedV56MigrationPreservedSource(
        databaseName: String,
        expectedRefundCount: Long
    ) {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val databaseFile =
            context.getDatabasePath(databaseName)

        val rawDatabase =
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

        try {
            rawDatabase.rawQuery(
                "PRAGMA user_version",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())

                assertEquals(
                    "Failed v5 -> v6 migration must leave database at v5",
                    5,
                    cursor.getInt(0)
                )
            }

            rawDatabase.rawQuery(
                """
                SELECT
                    COUNT(*),
                    COUNT(DISTINCT operation_id),
                    COALESCE(SUM(refund_amount_minor), 0)
                FROM refund_events
                """.trimIndent(),
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())

                assertEquals(
                    expectedRefundCount,
                    cursor.getLong(0)
                )

                assertEquals(
                    1L,
                    cursor.getLong(1)
                )

                assertEquals(
                    500L,
                    cursor.getLong(2)
                )
            }
        } finally {
            rawDatabase.close()
        }
    }
    private fun insertMigrationVoidOperation(
        db: SupportSQLiteDatabase,
        operationId: String,
        transactionId: String,
        occurredAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO operations (
                id,
                business_id,
                branch_id,
                operation_type,
                subject_type,
                subject_id,
                transaction_id,
                actor_user_id,
                device_id,
                occurred_at,
                request_fingerprint,
                command_schema_version,
                intent_payload,
                status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                operationId,
                "migration-business",
                "migration-branch",
                "RECORD_VOID",
                "TRANSACTION",
                transactionId,
                transactionId,
                "migration-user",
                "migration-device",
                occurredAt,
                "fingerprint-$operationId",
                1,
                "fixture-$operationId",
                "SUCCEEDED"
            )
        )
    }

    private fun insertMigrationVoidEvent(
        db: SupportSQLiteDatabase,
        eventId: String,
        transactionId: String,
        operationId: String,
        reason: String?,
        occurredAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO void_events (
                id,
                transaction_id,
                operation_id,
                reason,
                actor_user_id,
                occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                eventId,
                transactionId,
                operationId,
                reason,
                "migration-user",
                occurredAt
            )
        )
    }

    private fun assertVoidUniquenessIndexes(
        db: SupportSQLiteDatabase
    ) {
        val indexes = mutableMapOf<String, Int>()

        db.query(
            "PRAGMA index_list(void_events)"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                indexes[cursor.getString(1)] =
                    cursor.getInt(2)
            }
        }

        assertEquals(
            "transaction_id Void index must be UNIQUE",
            1,
            indexes["index_void_events_transaction_id"]
        )

        assertEquals(
            "operation_id Void index must be UNIQUE",
            1,
            indexes["index_void_events_operation_id"]
        )

        assertEquals(
            "occurred_at Void index must remain non-unique",
            0,
            indexes["index_void_events_occurred_at"]
        )

        assertTrue(
            "Temporary _unique transaction index name must not remain",
            "index_void_events_transaction_id_unique" !in indexes
        )

        assertTrue(
            "Temporary _unique operation index name must not remain",
            "index_void_events_operation_id_unique" !in indexes
        )

        db.query(
            "PRAGMA index_info(index_void_events_transaction_id)"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("transaction_id", cursor.getString(2))
        }

        db.query(
            "PRAGMA index_info(index_void_events_operation_id)"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("operation_id", cursor.getString(2))
        }
    }

    private fun assertFailedV45MigrationPreservedSource(
        databaseName: String,
        expectedVoidCount: Long
    ) {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val databaseFile =
            context.getDatabasePath(databaseName)

        val rawDatabase =
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

        try {
            rawDatabase.rawQuery(
                "PRAGMA user_version",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "Failed v4 -> v5 migration must leave database at v4",
                    4,
                    cursor.getInt(0)
                )
            }

            rawDatabase.rawQuery(
                "SELECT COUNT(*) FROM void_events",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "Failed migration must preserve original Void rows",
                    expectedVoidCount,
                    cursor.getLong(0)
                )
            }
        } finally {
            rawDatabase.close()
        }
    }
    private fun insertMigrationBusinessAndBranch(
        db: SupportSQLiteDatabase
    ) {
        db.execSQL(
            """
            INSERT INTO businesses (
                id,
                name,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "migration-business",
                "Migration Business",
                100L,
                100L
            )
        )

        db.execSQL(
            """
            INSERT INTO branches (
                id,
                business_id,
                name,
                code,
                country_code,
                currency_code,
                currency_decimal_places,
                currency_symbol,
                timezone_id,
                tax_rounding_mode,
                default_tax_rate_bps,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "migration-branch",
                "migration-business",
                "Migration Branch",
                "MIG",
                "OM",
                "OMR",
                3,
                "OMR",
                "Asia/Muscat",
                "HALF_UP",
                0,
                100L,
                100L
            )
        )
    }

    private fun insertMigrationTransaction(
        db: SupportSQLiteDatabase,
        transactionId: String,
        displayRef: String
    ) {
        db.execSQL(
            """
            INSERT INTO transactions (
                id,
                branch_id,
                type,
                subtotal_minor,
                tax_rate_bps,
                taxable_base_minor,
                tax_amount_minor,
                tx_shipping_minor,
                tx_packing_minor,
                grand_total_minor,
                currency_code,
                currency_decimal_places,
                created_by_user_id,
                device_id,
                created_at,
                display_ref
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                transactionId,
                "migration-branch",
                "SALE",
                1234L,
                0,
                1234L,
                0L,
                0L,
                0L,
                1234L,
                "OMR",
                3,
                "migration-user",
                "migration-device",
                1000L,
                displayRef
            )
        )
    }

    private fun insertMigrationOperation(
        db: SupportSQLiteDatabase,
        operationId: String,
        transactionId: String?
    ) {
        db.execSQL(
            """
            INSERT INTO operations (
                id,
                business_id,
                branch_id,
                operation_type,
                subject_type,
                subject_id,
                transaction_id,
                actor_user_id,
                device_id,
                occurred_at,
                request_fingerprint,
                command_schema_version,
                intent_payload,
                status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                operationId,
                "migration-business",
                "migration-branch",
                "RECORD_SALE",
                "TRANSACTION",
                transactionId ?: "standalone-subject",
                transactionId,
                "migration-user",
                "migration-device",
                1100L,
                "fingerprint-$operationId",
                1,
                "{}",
                "SUCCEEDED"
            )
        )
    }

    private fun insertMigrationSettlement(
        db: SupportSQLiteDatabase,
        settlementId: String,
        operationId: String,
        subjectId: String,
        transactionId: String?,
        amountMinor: Long,
        qrProfileId: String?,
        providerRef: String?
    ) {
        db.execSQL(
            """
            INSERT INTO settlement_entries (
                id,
                operation_id,
                subject_type,
                subject_id,
                transaction_id,
                entry_purpose,
                category,
                channel,
                amount_signed_minor,
                currency_code,
                confirmation_type,
                qr_profile_id_snapshot,
                occurred_at,
                actor_user_id,
                provider_ref
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                settlementId,
                operationId,
                "TRANSACTION",
                subjectId,
                transactionId,
                "SALE_FUNDING",
                "CASH",
                "CASH_TENDER",
                amountMinor,
                "OMR",
                "CASHIER_CONFIRMED",
                qrProfileId,
                1111L,
                "migration-user",
                providerRef
            )
        )
    }

    private fun assertSettlementForeignKeys(
        db: SupportSQLiteDatabase
    ) {
        var operationForeignKeyFound = false
        var transactionForeignKeyFound = false

        db.query(
            "PRAGMA foreign_key_list(settlement_entries)"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val parentTable = cursor.getString(2)
                val childColumn = cursor.getString(3)
                val parentColumn = cursor.getString(4)
                val onUpdate = cursor.getString(5)
                val onDelete = cursor.getString(6)

                if (
                    parentTable == "operations" &&
                    childColumn == "operation_id" &&
                    parentColumn == "id" &&
                    onUpdate == "NO ACTION" &&
                    onDelete == "RESTRICT"
                ) {
                    operationForeignKeyFound = true
                }

                if (
                    parentTable == "transactions" &&
                    childColumn == "transaction_id" &&
                    parentColumn == "id" &&
                    onUpdate == "NO ACTION" &&
                    onDelete == "RESTRICT"
                ) {
                    transactionForeignKeyFound = true
                }
            }
        }

        assertTrue(
            "Missing settlement_entries.operation_id -> operations.id RESTRICT FK",
            operationForeignKeyFound
        )

        assertTrue(
            "Missing settlement_entries.transaction_id -> transactions.id RESTRICT FK",
            transactionForeignKeyFound
        )
    }

    private fun assertSettlementIndexes(
        db: SupportSQLiteDatabase
    ) {
        val actualIndexes = mutableSetOf<String>()

        db.query(
            "PRAGMA index_list(settlement_entries)"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                actualIndexes += cursor.getString(1)
            }
        }

        val expectedIndexes = setOf(
            "index_settlement_entries_operation_id",
            "index_settlement_entries_transaction_id",
            "index_settlement_entries_subject_type_subject_id",
            "index_settlement_entries_category",
            "index_settlement_entries_entry_purpose",
            "index_settlement_entries_occurred_at"
        )

        expectedIndexes.forEach { index ->
            assertTrue(
                "Missing settlement index after migration: $index",
                index in actualIndexes
            )
        }
    }

    private fun assertThrowableChainContains(
        throwable: Throwable?,
        expectedText: String
    ) {
        var current = throwable
        var matched = false

        while (current != null) {
            if (
                current.message
                    ?.contains(expectedText) == true
            ) {
                matched = true
                break
            }

            current = current.cause
        }

        assertTrue(
            "Expected failure chain to contain: $expectedText",
            matched
        )
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

    @Test
    fun migration13To14CreatesCustomerIdentityAliasesAndIndexes() {
        val databaseName = "dmr-migration-13-14-customer-aliases-test"

        migrationHelper.createDatabase(databaseName, 13).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            DmrDatabase.MIGRATION_13_14
        )

        val columns = mutableSetOf<String>()
        migrated.query("PRAGMA table_info(customer_id_aliases)").use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.getString(1)
            }
        }

        assertEquals(
            setOf(
                "business_id",
                "alias_customer_id",
                "local_customer_id",
                "canonical_customer_id",
                "canonical_phone",
                "updated_at"
            ),
            columns
        )

        val indexes = mutableSetOf<String>()
        migrated.query("PRAGMA index_list(customer_id_aliases)").use { cursor ->
            while (cursor.moveToNext()) {
                indexes += cursor.getString(1)
            }
        }

        assertTrue("Missing business/local customer alias index",
            "index_customer_id_aliases_business_id_local_customer_id" in indexes)
        assertTrue("Missing business/canonical customer alias index",
            "index_customer_id_aliases_business_id_canonical_customer_id" in indexes)
        assertTrue("Missing business/canonical phone alias index",
            "index_customer_id_aliases_business_id_canonical_phone" in indexes)

        migrated.close()
    }

}