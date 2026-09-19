package com.dailymoneyregister.core.di

import android.content.Context
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.security.PinThrottler
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
    fun providePinThrottler(@ApplicationContext context: Context): PinThrottler {
        return PinThrottler(context = context)
    }

    @Provides
    @Singleton
    fun provideLanguageManager(@ApplicationContext context: Context): LanguageManager {
        return LanguageManager(context)
    }
}
