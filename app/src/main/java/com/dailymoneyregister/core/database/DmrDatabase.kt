package com.dailymoneyregister.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
import com.dailymoneyregister.core.database.dao.CloudStaffIdentityDao
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CashShiftDao
import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.dao.DocumentMediaDao
import com.dailymoneyregister.core.database.dao.LedgerDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.FinancialSyncOutboxDao
import com.dailymoneyregister.core.database.dao.ProductDao
import com.dailymoneyregister.core.database.dao.ReportingReadDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity
import com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity
import com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CustomerIdAliasEntity
import com.dailymoneyregister.core.database.entity.DocumentEntity
import com.dailymoneyregister.core.database.entity.DocumentMediaLinkEntity
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.database.entity.MerchantQrConfigEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.HumanIdentityEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity

import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.ExpenseEventEntity
import com.dailymoneyregister.core.database.entity.ExpensePayeeEntity
import com.dailymoneyregister.core.database.entity.ExpenseRecurrenceEntity
import com.dailymoneyregister.core.database.dao.ExpenseDao

@Database(
    entities = [
        BusinessEntity::class,
        CloudStaffIdentityEntity::class,
        BranchEntity::class,
        UserAccountEntity::class,
        HumanIdentityEntity::class,
        UserBranchMembershipEntity::class,
        CustomerEntity::class,
        CustomerIdAliasEntity::class,
        ProductEntity::class,
        CouponWalletEntity::class,
        CouponAuthorizedUserEntity::class,
        CouponAuthorizedUserEventEntity::class,
        CashShiftEntity::class,
        CashShiftEventEntity::class,
        ExpensePayeeEntity::class,
        ExpenseEntity::class,
        ExpenseEventEntity::class,
        ExpenseRecurrenceEntity::class,
        MediaAssetEntity::class,
        DocumentMediaLinkEntity::class,
        MerchantQrConfigEntity::class,
        OperationEntity::class,
        FinancialSyncOutboxEntity::class,
        TransactionEntity::class,
        TransactionItemEntity::class,
        SettlementEntryEntity::class,
        CreditLedgerEventEntity::class,
        CouponLedgerEventEntity::class,
        DocumentEntity::class,
        AmountCorrectionEventEntity::class,
        PaymentMethodChangeEventEntity::class,
        VoidEventEntity::class,
        RefundEventEntity::class,
        CreditRepaymentEntity::class,
        CreditRepaymentAllocationEntity::class
    ],
    version = 15,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class DmrDatabase : RoomDatabase() {

    abstract fun businessBranchDao(): BusinessBranchDao
    abstract fun cloudStaffIdentityDao(): CloudStaffIdentityDao
    abstract fun userDao(): UserDao
    abstract fun operationDao(): OperationDao
    abstract fun financialSyncOutboxDao(): FinancialSyncOutboxDao
    abstract fun transactionDao(): TransactionDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun customerDao(): CustomerDao
    abstract fun cashShiftDao(): CashShiftDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun documentMediaDao(): DocumentMediaDao
    abstract fun productDao(): ProductDao
    abstract fun reportingReadDao(): ReportingReadDao

    companion object {
        internal const val DB_NAME = "daily_money_register.db"


        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FinancialImmutabilityCallback.installGuards(db)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS human_identities (id TEXT NOT NULL PRIMARY KEY, canonical_phone TEXT, display_name TEXT NOT NULL, is_active INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_human_identities_canonical_phone ON human_identities(canonical_phone)")
                db.execSQL("ALTER TABLE user_accounts ADD COLUMN identity_id TEXT")
                db.execSQL("ALTER TABLE user_accounts ADD COLUMN must_change_pin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("INSERT INTO human_identities(id, canonical_phone, display_name, is_active, created_at, updated_at) SELECT id, NULL, display_name, is_active, created_at, updated_at FROM user_accounts")
                db.execSQL("UPDATE user_accounts SET identity_id = id WHERE identity_id IS NULL")
                FinancialImmutabilityCallback.installGuards(db)
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query(
                    """
                    SELECT 1
                    FROM settlement_entries AS s
                    LEFT JOIN transactions AS t
                        ON t.id = s.transaction_id
                    WHERE s.transaction_id IS NOT NULL
                      AND t.id IS NULL
                    LIMIT 1
                    """.trimIndent()
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        throw IllegalStateException(
                            "MIGRATION_3_4 blocked: settlement_entries contains an orphan transaction_id"
                        )
                    }
                }

                db.execSQL(
                    "DROP TRIGGER IF EXISTS immutable_settlement_entries_no_update"
                )
                db.execSQL(
                    "DROP TRIGGER IF EXISTS immutable_settlement_entries_no_delete"
                )
                db.execSQL(
                    "DROP TRIGGER IF EXISTS immutable_settlement_entries_no_replace"
                )

                db.execSQL(
                    """
                    CREATE TABLE settlement_entries_new (
                        id TEXT NOT NULL,
                        operation_id TEXT NOT NULL,
                        subject_type TEXT NOT NULL,
                        subject_id TEXT NOT NULL,
                        transaction_id TEXT,
                        entry_purpose TEXT NOT NULL,
                        category TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        amount_signed_minor INTEGER NOT NULL,
                        currency_code TEXT NOT NULL,
                        confirmation_type TEXT NOT NULL,
                        qr_profile_id_snapshot TEXT,
                        occurred_at INTEGER NOT NULL,
                        actor_user_id TEXT NOT NULL,
                        provider_ref TEXT,
                        PRIMARY KEY(id),
                        FOREIGN KEY(operation_id) REFERENCES operations(id)
                            ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(transaction_id) REFERENCES transactions(id)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO settlement_entries_new (
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
                    """.trimIndent()
                )

                db.execSQL(
                    "DROP TABLE settlement_entries"
                )

                db.execSQL(
                    "ALTER TABLE settlement_entries_new RENAME TO settlement_entries"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_entries_operation_id " +
                        "ON settlement_entries(operation_id)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_entries_transaction_id " +
                        "ON settlement_entries(transaction_id)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_entries_subject_type_subject_id " +
                        "ON settlement_entries(subject_type, subject_id)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_entries_category " +
                        "ON settlement_entries(category)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_entries_entry_purpose " +
                        "ON settlement_entries(entry_purpose)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_entries_occurred_at " +
                        "ON settlement_entries(occurred_at)"
                )

                FinancialImmutabilityCallback.installGuards(db)
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query(
                    """
                    SELECT transaction_id
                    FROM void_events
                    GROUP BY transaction_id
                    HAVING COUNT(*) > 1
                    LIMIT 1
                    """.trimIndent()
                ).use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "MIGRATION_4_5 blocked: duplicate void_events.transaction_id"
                    }
                }

                db.query(
                    """
                    SELECT operation_id
                    FROM void_events
                    GROUP BY operation_id
                    HAVING COUNT(*) > 1
                    LIMIT 1
                    """.trimIndent()
                ).use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "MIGRATION_4_5 blocked: duplicate void_events.operation_id"
                    }
                }

                db.execSQL(
                    "DROP INDEX IF EXISTS index_void_events_transaction_id"
                )
                db.execSQL(
                    "DROP INDEX IF EXISTS index_void_events_operation_id"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_void_events_transaction_id " +
                        "ON void_events(transaction_id)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_void_events_operation_id " +
                        "ON void_events(operation_id)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                /*
                 * Multiple partial refunds for one transaction are valid,
                 * therefore refund_events.transaction_id remains NON-UNIQUE.
                 *
                 * One durable Refund operation may materialize exactly one
                 * RefundEvent, therefore operation_id must be UNIQUE.
                 *
                 * Refuse migration before modifying the schema when legacy
                 * v5 data already violates that invariant.
                 */
                db.query(
                    """
                    SELECT operation_id
                    FROM refund_events
                    GROUP BY operation_id
                    HAVING COUNT(*) > 1
                    LIMIT 1
                    """.trimIndent()
                ).use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "MIGRATION_5_6 blocked: duplicate refund_events.operation_id"
                    }
                }

                db.execSQL(
                    "DROP INDEX IF EXISTS index_refund_events_operation_id"
                )

                db.execSQL(
                    "CREATE UNIQUE INDEX index_refund_events_operation_id " +
                        "ON refund_events(operation_id)"
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                /*
                 * Multiple chronological payment-method changes for one
                 * transaction are valid.
                 *
                 * One durable operation may produce exactly one
                 * PaymentMethodChangeEvent.
                 */
                db.query(
                    """
                    SELECT operation_id
                    FROM payment_method_change_events
                    GROUP BY operation_id
                    HAVING COUNT(*) > 1
                    LIMIT 1
                    """.trimIndent()
                ).use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "MIGRATION_6_7 blocked: duplicate payment_method_change_events.operation_id"
                    }
                }

                db.execSQL(
                    "DROP INDEX IF EXISTS index_payment_method_change_events_operation_id"
                )

                db.execSQL(
                    "CREATE UNIQUE INDEX " +
                        "index_payment_method_change_events_operation_id " +
                        "ON payment_method_change_events(operation_id)"
                )
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                /*
                 * Room v7 stores only PaymentCategory for payment-method
                 * changes. ONLINE is not enough to reconstruct whether the
                 * authoritative channel was MANUAL_QR or SOFTPOS. Never invent
                 * historical payment identity during migration.
                 */
                db.query(
                    """
                    SELECT id
                    FROM payment_method_change_events
                    LIMIT 1
                    """.trimIndent()
                ).use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "MIGRATION_7_8 blocked: legacy payment_method_change_events lack channel identity"
                    }
                }

                db.execSQL(
                    "ALTER TABLE payment_method_change_events " +
                        "ADD COLUMN previous_channel TEXT"
                )

                db.execSQL(
                    "ALTER TABLE payment_method_change_events " +
                        "ADD COLUMN new_channel TEXT"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                /*
                 * Links a document to the customer it was issued to.
                 * Nullable, so every existing document stays valid.
                 * No data is copied, moved or rewritten.
                 */
                db.execSQL(
                    "ALTER TABLE documents ADD COLUMN customer_id TEXT " +
                        "REFERENCES customers(id) ON UPDATE NO ACTION ON DELETE RESTRICT"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_customer_id " +
                        "ON documents(customer_id)"
                )
            }
        }

        /**

         * BOARDLOCK cashier shift attribution foundation.

         *

         * Historical operations remain NULL. Historical shift identity

         * is never reconstructed or guessed.

         */

        val MIGRATION_9_10 = object : Migration(9, 10) {

            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL(

                    "ALTER TABLE operations ADD COLUMN cash_shift_id TEXT"

                )


                db.execSQL(

                    "CREATE INDEX IF NOT EXISTS index_operations_cash_shift_id " +

                        "ON operations(cash_shift_id)"

                )

            }

        }



        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expense_payees` (
                        `id` TEXT NOT NULL,
                        `business_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalized_name` TEXT NOT NULL,
                        `canonical_phone` TEXT,
                        `account_reference` TEXT,
                        `payee_type` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `created_by_user_id` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_payees_business_id_normalized_name` ON `expense_payees` (`business_id`, `normalized_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_payees_business_id_canonical_phone` ON `expense_payees` (`business_id`, `canonical_phone`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_payees_business_id_account_reference` ON `expense_payees` (`business_id`, `account_reference`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expenses` (
                        `id` TEXT NOT NULL,
                        `operation_id` TEXT NOT NULL,
                        `business_id` TEXT NOT NULL,
                        `branch_id` TEXT NOT NULL,
                        `cash_shift_id` TEXT,
                        `actor_user_id` TEXT NOT NULL,
                        `payee_id` TEXT,
                        `payee_name_snapshot` TEXT NOT NULL,
                        `payee_phone_snapshot` TEXT,
                        `payee_account_snapshot` TEXT,
                        `category` TEXT NOT NULL,
                        `amount_minor` INTEGER NOT NULL,
                        `payment_category` TEXT NOT NULL,
                        `currency_code` TEXT NOT NULL,
                        `reference` TEXT,
                        `note` TEXT,
                        `recurrence_id` TEXT,
                        `occurred_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_business_id_branch_id_occurred_at` ON `expenses` (`business_id`, `branch_id`, `occurred_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_payee_id` ON `expenses` (`payee_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_cash_shift_id` ON `expenses` (`cash_shift_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expenses_operation_id` ON `expenses` (`operation_id`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expense_events` (
                        `id` TEXT NOT NULL,
                        `expense_id` TEXT NOT NULL,
                        `operation_id` TEXT NOT NULL,
                        `event_type` TEXT NOT NULL,
                        `actor_user_id` TEXT NOT NULL,
                        `previous_amount_minor` INTEGER,
                        `new_amount_minor` INTEGER,
                        `previous_payment_category` TEXT,
                        `new_payment_category` TEXT,
                        `previous_category` TEXT,
                        `new_category` TEXT,
                        `previous_payee_id` TEXT,
                        `new_payee_id` TEXT,
                        `reason` TEXT,
                        `occurred_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_events_expense_id_occurred_at` ON `expense_events` (`expense_id`, `occurred_at`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expense_events_operation_id` ON `expense_events` (`operation_id`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expense_recurrences` (
                        `id` TEXT NOT NULL,
                        `business_id` TEXT NOT NULL,
                        `branch_id` TEXT NOT NULL,
                        `payee_id` TEXT,
                        `category` TEXT NOT NULL,
                        `default_amount_minor` INTEGER,
                        `default_payment_category` TEXT,
                        `frequency` TEXT NOT NULL,
                        `next_due_at` INTEGER NOT NULL,
                        `is_active` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `created_by_user_id` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_recurrences_business_id_branch_id` ON `expense_recurrences` (`business_id`, `branch_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_recurrences_payee_id` ON `expense_recurrences` (`payee_id`)")
            }
        }
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `document_media_links` (
                        `document_id` TEXT NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`document_id`, `media_id`),
                        FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(`media_id`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_media_links_document_id` " +
                        "ON `document_media_links` (`document_id`)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_media_links_media_id` " +
                        "ON `document_media_links` (`media_id`)"
                )
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `financial_sync_outbox` (
                        `operation_id` TEXT NOT NULL,
                        `business_id` TEXT NOT NULL,
                        `branch_id` TEXT NOT NULL,
                        `transaction_id` TEXT NOT NULL,
                        `actor_user_id` TEXT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `request_fingerprint` TEXT NOT NULL,
                        `command_schema_version` INTEGER NOT NULL,
                        `occurred_at` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `attempt_count` INTEGER NOT NULL,
                        PRIMARY KEY(`operation_id`),
                        FOREIGN KEY(`operation_id`)
                            REFERENCES `operations`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_financial_sync_outbox_status` " +
                        "ON `financial_sync_outbox` (`status`)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_financial_sync_outbox_occurred_at` " +
                        "ON `financial_sync_outbox` (`occurred_at`)"
                )
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS customer_id_aliases (
                        business_id TEXT NOT NULL,
                        alias_customer_id TEXT NOT NULL,
                        local_customer_id TEXT NOT NULL,
                        canonical_customer_id TEXT NOT NULL,
                        canonical_phone TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(business_id, alias_customer_id)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_id_aliases_business_id_local_customer_id ON customer_id_aliases (business_id, local_customer_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_id_aliases_business_id_canonical_customer_id ON customer_id_aliases (business_id, canonical_customer_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_id_aliases_business_id_canonical_phone ON customer_id_aliases (business_id, canonical_phone)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cloud_staff_identities (
                        membership_id TEXT NOT NULL PRIMARY KEY,
                        local_user_id TEXT NOT NULL,
                        business_id TEXT NOT NULL,
                        branch_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        permissions_json TEXT NOT NULL,
                        is_active INTEGER NOT NULL,
                        is_removed INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_staff_identities_business_id_local_user_id ON cloud_staff_identities (business_id, local_user_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_staff_identities_business_id_branch_id ON cloud_staff_identities (business_id, branch_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_staff_identities_business_id_branch_id_is_active_is_removed ON cloud_staff_identities (business_id, branch_id, is_active, is_removed)")
            }
        }

        @Volatile

        private var INSTANCE: DmrDatabase? = null

        fun getInstance(context: Context): DmrDatabase {
            return INSTANCE ?: synchronized(this) {
                System.loadLibrary("sqlcipher")

                val passphrase =
                    com.dailymoneyregister.core.security.DatabasePassphraseManager(
                        context
                    ).getOrCreatePassphrase()

                val roomPassphrase =
                    passphrase.copyOf()

                passphrase.fill(0)

                val instance =
                    try {
                        Room.databaseBuilder(
                            context.applicationContext,
                            DmrDatabase::class.java,
                            DB_NAME
                        )
                            .openHelperFactory(
                                net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                                    roomPassphrase
                                )
                            )
                            .addMigrations(MIGRATION_1_2)
                            .addMigrations(MIGRATION_2_3)
                            .addMigrations(MIGRATION_3_4)
                            .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                            .addCallback(FinancialImmutabilityCallback)
                            .build()
                    } catch (e: Exception) {
                        roomPassphrase.fill(0)
                        throw e
                    }

                INSTANCE = instance
                instance
            }
        }

        fun createInMemory(context: Context): DmrDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                DmrDatabase::class.java
            )
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                .addCallback(FinancialImmutabilityCallback)
                .allowMainThreadQueries()
                .build()
        }
    }
}


