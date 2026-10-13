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
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

/**
 * Shared Supabase client for Auth and PostgREST.
 * Supabase owns cloud identity and shared records; Room is retained for the
 * device-side offline projection and synchronization outbox.
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
            httpEngine = OkHttp.create()
            install(Postgrest)
            install(Realtime)
            install(Auth) {
                scheme = "paiddue"
                host = "invite"
            }
        }
    }
}
