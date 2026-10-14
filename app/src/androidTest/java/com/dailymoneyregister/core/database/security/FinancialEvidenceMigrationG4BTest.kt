package com.dailymoneyregister.core.database.security

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialEvidenceMigrationG4BTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    private val tables = listOf(
        "businesses",
        "branches",
        "customers",
        "user_accounts",
        "transactions",
        "operations",
        "settlement_entries",
        "credit_ledger_events",
        "credit_repayments",
        "credit_repayment_allocations",
        "coupon_wallets",
        "coupon_ledger_events",
        "coupon_authorized_users",
        "coupon_authorized_user_events",
        "refund_events"
    )

    @Test
    fun populatedCreditCouponRefundEvidenceSurvivesEncrypted12To13() {
        val context: Context =
            InstrumentationRegistry.getInstrumentation().targetContext

        val name = "boardlock_g4b_evidence_v12_to_v13.db"
        require(name != "daily_money_register.db")

        val key = ByteArray(32) { (it + 91).toByte() }
        var room: DmrDatabase? = null

        context.deleteDatabase(name)

        try {
            val old = helper.createDatabase(name, 12)

            // Independent, linked financial records.
            // These are migration fixtures, not simulated
            // execution of the production financial commands.
            old.execSQL(
                """
                INSERT INTO businesses
                (id,name,created_at,updated_at)
                VALUES ('g4-business','G4 Shop',100,100)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO branches
                (id,business_id,name,code,country_code,
                 currency_code,currency_decimal_places,
                 currency_symbol,timezone_id,tax_rounding_mode,
                 default_tax_rate_bps,created_at,updated_at)
                VALUES
                ('g4-branch','g4-business','Main','G4',
                 'OM','OMR',3,'OMR','Asia/Muscat',
                 'HALF_UP',0,100,100)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO customers
                (id,business_id,canonical_phone,name,
                 created_at,updated_at)
                VALUES
                ('g4-customer','g4-business',
                 '+96890000001','Migration Customer',100,100)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO user_accounts
                (id,business_id,display_name,username,role,
                 auth_type,credential_hash,language_code,
                 is_active,must_change_pin,created_at,updated_at)
                VALUES
                ('g4-user','g4-business','Cashier','g4_cashier',
                 'CASHIER','PIN','hash','en',1,0,100,100)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO transactions
                (id,branch_id,type,customer_id,subtotal_minor,
                 tax_rate_bps,taxable_base_minor,tax_amount_minor,
                 tx_shipping_minor,tx_packing_minor,
                 grand_total_minor,currency_code,
                 currency_decimal_places,created_by_user_id,
                 device_id,created_at,display_ref)
                VALUES
                ('g4-sale','g4-branch','SALE','g4-customer',
                 10000,0,10000,0,0,0,10000,'OMR',3,
                 'g4-user','g4-device',200,'G4-SALE')
                """.trimIndent()
            )

            for (op in listOf(
                "g4-credit-op",
                "g4-repayment-op",
                "g4-coupon-op",
                "g4-refund-op"
            )) {
                val type = when (op) {
                    "g4-credit-op" -> "RECORD_SALE"
                    "g4-repayment-op" -> "RECORD_CREDIT_REPAYMENT"
                    "g4-coupon-op" -> "SELL_COUPON"
                    else -> "REFUND"
                }

                old.execSQL(
                    """
                    INSERT INTO operations
                    (id,business_id,branch_id,operation_type,
                     subject_type,subject_id,transaction_id,
                     actor_user_id,device_id,occurred_at,
                     request_fingerprint,command_schema_version,
                     intent_payload,status)
                    VALUES (?,?,? ,?,?,?,? ,?,?,? ,?,?,?,?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        op,
                        "g4-business",
                        "g4-branch",
                        type,
                        "TRANSACTION",
                        "g4-sale",
                        "g4-sale",
                        "g4-user",
                        "g4-device",
                        200L,
                        "fingerprint-$op",
                        1,
                        "{}",
                        "SUCCEEDED"
                    )
                )
            }

            old.execSQL(
                """
                INSERT INTO settlement_entries
                (id,operation_id,subject_type,subject_id,
                 transaction_id,entry_purpose,category,
                 channel,amount_signed_minor,currency_code,
                 confirmation_type,occurred_at,actor_user_id)
                VALUES
                ('g4-settlement','g4-credit-op','TRANSACTION',
                 'g4-sale','g4-sale','SALE_FUNDING',
                 'CREDIT','CREDIT_LEDGER',10000,'OMR',
                 'CASHIER_CONFIRMED',200,'g4-user')
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO credit_repayments
                (id,operation_id,customer_id,amount_minor,
                 currency_code,payment_category,
                 occurred_at,actor_user_id)
                VALUES
                ('g4-repayment','g4-repayment-op',
                 'g4-customer',2000,'OMR','CASH',300,'g4-user')
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO credit_repayment_allocations
                (id,repayment_id,original_transaction_id,
                 allocated_amount_minor,occurred_at)
                VALUES
                ('g4-allocation','g4-repayment','g4-sale',2000,300)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO credit_ledger_events
                (id,operation_id,customer_id,type,
                 amount_signed_minor,currency_code,
                 transaction_id,repayment_id,occurred_at)
                VALUES
                ('g4-credit-created','g4-credit-op',
                 'g4-customer','CREDIT_CREATED',10000,'OMR',
                 'g4-sale',NULL,200)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO credit_ledger_events
                (id,operation_id,customer_id,type,
                 amount_signed_minor,currency_code,
                 transaction_id,repayment_id,occurred_at)
                VALUES
                ('g4-credit-paid','g4-repayment-op',
                 'g4-customer','REPAYMENT',-2000,'OMR',
                 NULL,'g4-repayment',300)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO coupon_wallets
                (id,customer_id,currency_code,is_active,created_at)
                VALUES
                ('g4-wallet','g4-customer','OMR',1,200)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO coupon_authorized_users
                (id,wallet_id,is_purchaser,is_active,created_at)
                VALUES ('g4-authorized','g4-wallet',1,1,200)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO coupon_authorized_user_events
                (id,authorized_user_id,operation_id,event_type,
                 name_value,occurred_at,actor_user_id)
                VALUES
                ('g4-authorized-event','g4-authorized',
                 'g4-coupon-op','CREATED','Customer',200,'g4-user')
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO coupon_ledger_events
                (id,operation_id,wallet_id,type,amount_signed_minor,
                 currency_code,transaction_id,authorized_user_id,
                 authorized_user_name_snapshot,occurred_at)
                VALUES
                ('g4-coupon-event','g4-coupon-op',
                 'g4-wallet','SELL',3000,'OMR',
                 NULL,'g4-authorized','Customer',200)
                """.trimIndent()
            )

            old.execSQL(
                """
                INSERT INTO refund_events
                (id,transaction_id,operation_id,refund_amount_minor,
                 reason,actor_user_id,occurred_at)
                VALUES
                ('g4-refund','g4-sale','g4-refund-op',
                 1000,'Customer return','g4-user',400)
                """.trimIndent()
            )

            verifyForeignKeys(old)

            val before = snapshots(old)

            // Required presence guards: an empty table snapshot
            // cannot accidentally satisfy a migration test.
            for (table in tables) {
                assertTrue(
                    "Missing fixture data in $table",
                    before.getValue(table).isNotEmpty()
                )
            }

            old.close()

            // Encrypt the actual populated Room v12 database.
            System.loadLibrary("sqlcipher")

            assertEquals(
                DatabaseEncryptionPreparationResult.MIGRATED,
                DatabaseEncryptionMigrator(context, name)
                    .prepareDatabase(key.copyOf())
            )

            // Verify that it is not readable as plaintext.
            val header = ByteArray(16)
            context.getDatabasePath(name).inputStream().use {
                assertEquals(16, it.read(header))
            }

            assertTrue(
                !header.contentEquals(
                    "SQLite format 3\u0000".toByteArray(
                        Charsets.US_ASCII
                    )
                )
            )

            // Open the encrypted database through both migrations to Room v14.
            room = Room.databaseBuilder(
                context,
                DmrDatabase::class.java,
                name
            )
                .openHelperFactory(
                    SupportOpenHelperFactory(key.copyOf())
                )
                .addMigrations(DmrDatabase.MIGRATION_12_13, DmrDatabase.MIGRATION_13_14)
                .addCallback(FinancialImmutabilityCallback)
                .allowMainThreadQueries()
                .build()

            val current = room.openHelper.writableDatabase

            current.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(14, it.getInt(0))
            }

            verifyForeignKeys(current)

            val after = snapshots(current)

            for (table in tables) {
                assertEquals(
                    "Financial evidence changed in $table",
                    before.getValue(table),
                    after.getValue(table)
                )
            }

            current.query(
                "SELECT COUNT(*) FROM financial_sync_outbox"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(
                    "Existing financial history must not be backfilled",
                    0,
                    it.getInt(0)
                )
            }

            // One additional reopen must not rewrite evidence.
            room.close()
            room = null

            room = Room.databaseBuilder(
                context,
                DmrDatabase::class.java,
                name
            )
                .openHelperFactory(
                    SupportOpenHelperFactory(key.copyOf())
                )
                .addMigrations(DmrDatabase.MIGRATION_12_13, DmrDatabase.MIGRATION_13_14)
                .addCallback(FinancialImmutabilityCallback)
                .allowMainThreadQueries()
                .build()

            val reopened = room.openHelper.writableDatabase

            assertEquals(before, snapshots(reopened))
            verifyForeignKeys(reopened)

            reopened.query(
                "SELECT COUNT(*) FROM financial_sync_outbox"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            room?.close()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    private fun verifyForeignKeys(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use {
            assertTrue(
                "Foreign-key violations detected",
                !it.moveToFirst()
            )
        }
    }

    private fun snapshots(
        db: SupportSQLiteDatabase
    ): Map<String, List<String>> {
        val answer = linkedMapOf<String, List<String>>()

        for (table in tables) {
            val rows = mutableListOf<String>()

            db.query(
                "SELECT * FROM `$table` ORDER BY id"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val values = mutableListOf<String>()
                    for (column in 0 until cursor.columnCount) {
                        val encoded = when (cursor.getType(column)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_INTEGER ->
                                "INTEGER:" + cursor.getLong(column)
                            android.database.Cursor.FIELD_TYPE_FLOAT ->
                                "FLOAT:" + cursor.getDouble(column)
                            android.database.Cursor.FIELD_TYPE_BLOB ->
                                "BLOB:" + android.util.Base64.encodeToString(
                                    cursor.getBlob(column),
                                    android.util.Base64.NO_WRAP
                                )
                            else -> "TEXT:" + cursor.getString(column)
                        }
                        values += cursor.getColumnName(column) +
                            "=" + encoded.length + ":" + encoded
                    }
                    rows += values.joinToString("|")
                }
            }
            answer[table] = rows
        }
        return answer
    }
}