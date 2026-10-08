package com.dailymoneyregister.core.cloud

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BusinessRestorerModule {
    @Binds
    @Singleton
    abstract fun bindBusinessRestorer(
        implementation: RemoteBusinessRestorer
    ): BusinessRestorer
}
