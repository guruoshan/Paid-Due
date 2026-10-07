package com.dailymoneyregister.core.cloud

import com.dailymoneyregister.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Singleton

/**
 * Paid&Due cloud foundation.
 *
 * This client does not make Supabase the financial source of truth yet.
 * No Room, financial writer, reporting, credit, refund, coupon, or
 * transaction synchronization is performed by this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        check(BuildConfig.PAID_DUE_SUPABASE_URL.startsWith("https://")) {
            "Paid&Due Supabase URL is not configured."
        }

        check(
            BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY
                .startsWith("sb_publishable_")
        ) {
            "Paid&Due Supabase publishable key is not configured."
        }

        return createSupabaseClient(
            supabaseUrl = BuildConfig.PAID_DUE_SUPABASE_URL,
            supabaseKey = BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Postgrest)
            install(Auth)
        }
    }
}
