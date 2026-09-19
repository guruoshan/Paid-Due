package com.dailymoneyregister.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CashShiftDao
import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.dao.DocumentMediaDao
import com.dailymoneyregister.core.database.dao.LedgerDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.ProductDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.dao.UserDao
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
import com.dailymoneyregister.core.database.entity.DocumentEntity
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.database.entity.MerchantQrConfigEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
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

@Database(
    entities = [
        BusinessEntity::class,
        BranchEntity::class,
        UserAccountEntity::class,
        HumanIdentityEntity::class,
        UserBranchMembershipEntity::class,
        CustomerEntity::class,
        ProductEntity::class,
        CouponWalletEntity::class,
        CouponAuthorizedUserEntity::class,
        CouponAuthorizedUserEventEntity::class,
        CashShiftEntity::class,
        CashShiftEventEntity::class,
        MediaAssetEntity::class,
        MerchantQrConfigEntity::class,
        OperationEntity::class,
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
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class DmrDatabase : RoomDatabase() {

    abstract fun businessBranchDao(): BusinessBranchDao
    abstract fun userDao(): UserDao
    abstract fun operationDao(): OperationDao
    abstract fun transactionDao(): TransactionDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun customerDao(): CustomerDao
    abstract fun cashShiftDao(): CashShiftDao
    abstract fun documentMediaDao(): DocumentMediaDao
    abstract fun productDao(): ProductDao

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
                .addCallback(FinancialImmutabilityCallback)
                .allowMainThreadQueries()
                .build()
        }
    }
}


