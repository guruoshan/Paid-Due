package com.dailymoneyregister.core.di

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.database.DmrDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CommandModule {

    @Provides
    @Singleton
    fun provideCommandExecutor(database: DmrDatabase): CommandExecutor {
        return CommandExecutor(database)
    }
}
