package com.dailymoneyregister.feature.admin.di

import com.dailymoneyregister.feature.admin.data.EmployeeDirectoryCloudSource
import com.dailymoneyregister.feature.admin.data.RemoteEmployeeDirectory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EmployeeDirectoryModule {
    @Binds
    @Singleton
    abstract fun bindEmployeeDirectory(
        implementation: RemoteEmployeeDirectory
    ): EmployeeDirectoryCloudSource
}
