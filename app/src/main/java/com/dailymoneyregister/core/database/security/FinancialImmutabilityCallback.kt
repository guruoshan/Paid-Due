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
        "cash_shift_events"
    )

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        installGuards(db)
    }

    internal fun installGuards(db: SupportSQLiteDatabase) {
        immutableTables.forEach { table ->

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
    }
}