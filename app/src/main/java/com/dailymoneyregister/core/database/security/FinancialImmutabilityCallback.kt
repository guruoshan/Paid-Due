package com.dailymoneyregister.core.database.security

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database-level protection for immutable financial truth.
 *
 * Once an authoritative financial record is written, it cannot be
 * updated, deleted, or replaced. Corrections/reversals are represented
 * by new append-only financial records.
 */
object FinancialImmutabilityCallback : RoomDatabase.Callback() {

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
        "cash_shift_events",
        "expenses",
        "expense_events"
    )

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA recursive_triggers = ON")
        installGuards(db)
    }

    internal fun installGuards(db: SupportSQLiteDatabase) {
        immutableTables
            .filter { table -> tableExists(db, table) }
            .forEach { table ->

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS immutable_${table}_no_update
                BEFORE UPDATE ON $table
                BEGIN
                    SELECT RAISE(
                        ABORT,
                        'IMMUTABLE_FINANCIAL_RECORD: $table cannot be updated'
                    );
                END
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS immutable_${table}_no_delete
                BEFORE DELETE ON $table
                BEGIN
                    SELECT RAISE(
                        ABORT,
                        'IMMUTABLE_FINANCIAL_RECORD: $table cannot be deleted'
                    );
                END
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS immutable_${table}_no_replace
                BEFORE INSERT ON $table
                WHEN EXISTS (
                    SELECT 1
                    FROM $table
                    WHERE id = NEW.id
                )
                BEGIN
                    SELECT RAISE(
                        ABORT,
                        'IMMUTABLE_FINANCIAL_RECORD: duplicate financial id'
                    );
                END
                """.trimIndent()
            )
        }

        // Delivery metadata is not a second financial ledger.
        // This trigger runs inside the successful operation UPDATE.
        // A failed transaction rolls back this insertion too.
        if (tableExists(db, "financial_sync_outbox")) {
            db.beginTransaction()
            try {
                // Replaces only derived synchronization trigger metadata.
                // Existing outbox and financial rows are not modified.
                db.execSQL(
                    "DROP TRIGGER IF EXISTS financial_sync_outbox_after_cash_sale_success"
                )
                db.execSQL(
                    "DROP TRIGGER IF EXISTS financial_sync_outbox_after_operation_success"
                )
                db.execSQL(
                    """
                    CREATE TRIGGER financial_sync_outbox_after_operation_success
                    AFTER UPDATE ON operations
                    WHEN OLD.status != 'SUCCEEDED'
                      AND NEW.status = 'SUCCEEDED'
                      AND NEW.branch_id IS NOT NULL
                      AND NEW.operation_type IN (
                          'RECORD_SALE', 'RECORD_CREDIT_REPAYMENT', 'RECORD_COUPON_PURCHASE',
                          'RECORD_AMOUNT_CORRECTION', 'RECORD_PAYMENT_METHOD_CHANGE',
                          'RECORD_VOID', 'RECORD_REFUND', 'RECORD_EXPENSE',
                          'RECORD_EXPENSE_CORRECTION', 'RECORD_EXPENSE_VOID',
                          'OPEN_CASH_SHIFT', 'COUNT_CASH_SHIFT', 'CLOSE_CASH_SHIFT'
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM financial_sync_outbox WHERE operation_id = NEW.id
                      )
                    BEGIN
                        INSERT INTO financial_sync_outbox (
                            operation_id,
                            business_id,
                            branch_id,
                            transaction_id,
                            actor_user_id,
                            device_id,
                            request_fingerprint,
                            command_schema_version,
                            occurred_at,
                            status,
                            attempt_count
                        )
                        VALUES (
                            NEW.id,
                            NEW.business_id,
                            NEW.branch_id,
                            COALESCE(NEW.transaction_id, NEW.result_reference_id, NEW.id),
                            NEW.actor_user_id,
                            NEW.device_id,
                            NEW.request_fingerprint,
                            NEW.command_schema_version,
                            NEW.occurred_at,
                            CASE
                                WHEN EXISTS (
                                    SELECT 1 FROM user_accounts u
                                    WHERE u.id = NEW.actor_user_id AND u.role = 'ADMIN'
                                ) AND NEW.operation_type IN (
                                    'RECORD_SALE', 'RECORD_CREDIT_REPAYMENT',
                                    'RECORD_COUPON_PURCHASE', 'RECORD_AMOUNT_CORRECTION',
                                    'RECORD_PAYMENT_METHOD_CHANGE', 'RECORD_VOID', 'RECORD_REFUND',
                                    'RECORD_EXPENSE', 'RECORD_EXPENSE_CORRECTION', 'RECORD_EXPENSE_VOID',
                                    'OPEN_CASH_SHIFT', 'COUNT_CASH_SHIFT', 'CLOSE_CASH_SHIFT'
                                ) THEN 'QUEUED'
                                WHEN EXISTS (
                                    SELECT 1 FROM user_accounts u
                                    WHERE u.id = NEW.actor_user_id AND u.role = 'CASHIER'
                                ) AND NEW.operation_type IN (
                                    'RECORD_SALE', 'RECORD_CREDIT_REPAYMENT',
                                    'RECORD_COUPON_PURCHASE', 'RECORD_REFUND', 'RECORD_VOID',
                                    'RECORD_AMOUNT_CORRECTION', 'RECORD_PAYMENT_METHOD_CHANGE',
                                    'OPEN_CASH_SHIFT', 'COUNT_CASH_SHIFT', 'CLOSE_CASH_SHIFT'
                                ) THEN 'QUEUED'
                                WHEN EXISTS (
                                    SELECT 1 FROM user_accounts u
                                    WHERE u.id = NEW.actor_user_id AND u.role = 'CASHIER'
                                ) AND NEW.operation_type IN (
                                    'RECORD_EXPENSE', 'RECORD_EXPENSE_CORRECTION', 'RECORD_EXPENSE_VOID'
                                ) THEN 'NOT_SYNCABLE_YET'
                                ELSE 'NOT_APPLICABLE'
                            END,
                            0
                        );
                    END
                    """.trimIndent()
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS operations_no_identity_update
            BEFORE UPDATE ON operations
            WHEN NOT (
                OLD.id IS NEW.id AND
                OLD.business_id IS NEW.business_id AND
                OLD.branch_id IS NEW.branch_id AND
                OLD.operation_type IS NEW.operation_type AND
                OLD.subject_type IS NEW.subject_type AND
                OLD.subject_id IS NEW.subject_id AND
                OLD.transaction_id IS NEW.transaction_id AND
                OLD.actor_user_id IS NEW.actor_user_id AND
                OLD.device_id IS NEW.device_id AND
                OLD.occurred_at IS NEW.occurred_at AND
                OLD.request_fingerprint IS NEW.request_fingerprint AND
                OLD.command_schema_version IS NEW.command_schema_version AND
                OLD.intent_payload IS NEW.intent_payload
            )
            BEGIN
                SELECT RAISE(ABORT, 'IMMUTABLE_OPERATION_IDENTITY: operations identity cannot be updated');
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS operations_no_delete
            BEFORE DELETE ON operations
            BEGIN
                SELECT RAISE(ABORT, 'IMMUTABLE_OPERATION: operations cannot be deleted');
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS operations_valid_transition
            BEFORE UPDATE ON operations
            WHEN NOT (
                (OLD.status = 'PENDING' AND NEW.status IN ('SUCCEEDED', 'FAILED', 'REQUIRES_RECONCILIATION')) OR
                (OLD.status = 'REQUIRES_RECONCILIATION' AND NEW.status IN ('SUCCEEDED', 'FAILED'))
            )
            BEGIN
                SELECT RAISE(ABORT, 'IMMUTABLE_OPERATION: invalid operation status transition');
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS operations_state_fields_require_transition
            BEFORE UPDATE ON operations
            WHEN OLD.status IS NEW.status AND NOT (
                OLD.result_reference_type IS NEW.result_reference_type AND
                OLD.result_reference_id IS NEW.result_reference_id AND
                OLD.reason IS NEW.reason
            )
            BEGIN
                SELECT RAISE(ABORT, 'IMMUTABLE_OPERATION: state fields require a lifecycle transition');
            END
            """.trimIndent()
        )
    }

    /**
     * Legacy migration steps call this installer before newer financial
     * tables exist. SQLite cannot create a trigger for an absent table, so
     * install only the guards applicable to the schema at that step. Room's
     * final onOpen callback installs the complete current set.
     */
    private fun tableExists(
        db: SupportSQLiteDatabase,
        table: String
    ): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() }
}

