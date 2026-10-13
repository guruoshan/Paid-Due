package com.dailymoneyregister.feature.admin.di

import com.dailymoneyregister.feature.admin.data.EmployeeDirectoryCloudSource
import com.dailymoneyregister.feature.admin.data.RemoteEmployeeDirectory
import com.dailymoneyregister.core.cloud.sync.DefaultStaffIdentitySync
import com.dailymoneyregister.core.cloud.sync.StaffIdentitySync
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

    @Binds
    @Singleton
    abstract fun bindStaffIdentitySync(
        implementation: DefaultStaffIdentitySync
    ): StaffIdentitySync
}
