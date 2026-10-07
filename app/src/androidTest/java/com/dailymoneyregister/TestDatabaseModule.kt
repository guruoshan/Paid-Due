package com.dailymoneyregister

import android.content.Context
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CashShiftDao
import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.dao.DocumentMediaDao
import com.dailymoneyregister.core.database.dao.LedgerDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.ProductDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class]
)
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DmrDatabase =
        AndroidTestDatabaseFactory.createInMemory(context)

    @Provides
    @Singleton
    fun provideBusinessBranchDao(db: DmrDatabase): BusinessBranchDao = db.businessBranchDao()

    @Provides
    @Singleton
    fun provideUserDao(db: DmrDatabase): UserDao = db.userDao()

    @Provides
    @Singleton
    fun provideOperationDao(db: DmrDatabase): OperationDao = db.operationDao()

    @Provides
    @Singleton
    fun provideTransactionDao(db: DmrDatabase): TransactionDao = db.transactionDao()

    @Provides
    @Singleton
    fun provideLedgerDao(db: DmrDatabase): LedgerDao = db.ledgerDao()

    @Provides
    @Singleton
    fun provideCustomerDao(db: DmrDatabase): CustomerDao = db.customerDao()

    @Provides
    @Singleton
    fun provideCashShiftDao(db: DmrDatabase): CashShiftDao = db.cashShiftDao()

    @Provides
    @Singleton
    fun provideDocumentMediaDao(db: DmrDatabase): DocumentMediaDao = db.documentMediaDao()

    @Provides
    @Singleton
    fun provideProductDao(db: DmrDatabase): ProductDao = db.productDao()
}
