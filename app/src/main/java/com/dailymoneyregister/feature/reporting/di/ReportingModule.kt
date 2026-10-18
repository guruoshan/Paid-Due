package com.dailymoneyregister.feature.reporting.di

import com.dailymoneyregister.core.cloud.sync.RemoteFinancialSyncRepository
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.feature.reporting.data.ReportingReadRepository
import com.dailymoneyregister.feature.reporting.data.RoomReportingReadRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ReportingModule {

    @Provides
    fun provideReportingReadRepository(
        database: DmrDatabase,
        remoteFinancialSyncRepository: RemoteFinancialSyncRepository
    ): ReportingReadRepository =
        RoomReportingReadRepository(
            database = database,
            remoteSyncRepository = remoteFinancialSyncRepository
        )
}