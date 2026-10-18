package com.dailymoneyregister.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dailymoneyregister.app.ui.DmrApp
import com.dailymoneyregister.core.i18n.LanguageManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import com.dailymoneyregister.core.security.DebugSessionBootstrapper
import com.dailymoneyregister.core.startup.StartupRecoveryCoordinator
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.core.security.TrustedSessionRestorer
import com.dailymoneyregister.feature.setup.data.SetupRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var supabase: SupabaseClient

    @Inject
    lateinit var financialSyncScheduler: com.dailymoneyregister.core.cloud.sync.FinancialSyncScheduler

    @Inject
    lateinit var setupRepository: SetupRepository

    @Inject
    lateinit var languageManager: LanguageManager

    @Inject
    lateinit var debugSessionBootstrapper: DebugSessionBootstrapper

    @Inject
    lateinit var startupRecoveryCoordinator: StartupRecoveryCoordinator

    @Inject
    lateinit var trustedSessionStore: TrustedSessionStore

    @Inject
    lateinit var trustedSessionRestorer: TrustedSessionRestorer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supabase.handleDeeplinks(intent)
        financialSyncScheduler.startAutoSync()

        setContent {
            val currentLanguage by languageManager.currentLanguage.collectAsState()

            val layoutDirection =
                if (currentLanguage.isRtl) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                }

            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection
            ) {
                MaterialTheme {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        DmrApp(
                            setupRepository = setupRepository,
                            languageManager = languageManager,
                            debugSessionBootstrapper = debugSessionBootstrapper,
                            startupRecoveryCoordinator = startupRecoveryCoordinator,
                            trustedSessionStore = trustedSessionStore,
                            trustedSessionRestorer = trustedSessionRestorer,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        supabase.handleDeeplinks(intent)
    }

}

