package com.dailymoneyregister

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/** Keeps instrumentation-only preferences and files away from working debug-app data. */
internal fun isolatedAndroidTestContext(base: Context): Context =
    object : ContextWrapper(base) {
        private val testFiles by lazy {
            File(base.cacheDir, "boardlock_android_test_files").apply { mkdirs() }
        }

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("boardlock_android_test_$name", mode)

        override fun getFilesDir(): File = testFiles

        override fun getCacheDir(): File =
            File(testFiles, "cache").apply { mkdirs() }
    }

