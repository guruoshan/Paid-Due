package com.dailymoneyregister.core.di

import android.content.Context
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.security.LoginAttemptThrottler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideLoginAttemptThrottler(@ApplicationContext context: Context): LoginAttemptThrottler {
        return LoginAttemptThrottler(context = context)
    }

    @Provides
    @Singleton
    fun provideLanguageManager(@ApplicationContext context: Context): LanguageManager {
        return LanguageManager(context)
    }
}
