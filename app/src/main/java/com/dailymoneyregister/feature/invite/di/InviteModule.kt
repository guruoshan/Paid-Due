package com.dailymoneyregister.feature.invite.di

import com.dailymoneyregister.feature.invite.data.InviteStore
import com.dailymoneyregister.feature.invite.data.RemoteInviteStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class InviteModule {
    // Supabase is the shared authoritative employee-invite store.
    @Binds
    @Singleton
    abstract fun bindInviteStore(store: RemoteInviteStore): InviteStore

    companion object {
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
