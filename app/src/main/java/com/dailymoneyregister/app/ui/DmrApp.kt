package com.dailymoneyregister.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.R
import com.dailymoneyregister.core.security.DebugSessionBootstrapper
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.core.security.TrustedSessionRestorer
import com.dailymoneyregister.core.startup.StartupRecoveryCoordinator
import com.dailymoneyregister.feature.admin.ui.CashierManagementScreen
import com.dailymoneyregister.feature.admin.ui.CashierManagementViewModel
import com.dailymoneyregister.feature.auth.ui.LoginScreen
import com.dailymoneyregister.feature.auth.ui.LoginViewModel
import com.dailymoneyregister.feature.auth.ui.ChangePinScreen
import com.dailymoneyregister.feature.auth.ui.ChangePinViewModel
import com.dailymoneyregister.feature.register.ui.RegisterScreen
import com.dailymoneyregister.feature.register.ui.RegisterViewModel
import com.dailymoneyregister.feature.settings.ui.SettingsScreen
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.ui.SetupScreen
import com.dailymoneyregister.feature.setup.ui.SetupViewModel

object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val CHANGE_PIN = "change_pin"
    const val ADMIN_HUB = "admin_hub"
    const val CASHIER_HUB = "cashier_hub"
    const val REGISTER = "register"
    const val MANAGE_CASHIERS = "manage_cashiers"
    const val SETTINGS = "settings"
}

@Composable
fun DmrApp(
    setupRepository: SetupRepository,
    languageManager: LanguageManager,
    debugSessionBootstrapper: DebugSessionBootstrapper,
    startupRecoveryCoordinator: StartupRecoveryCoordinator,
    trustedSessionStore: TrustedSessionStore,
    trustedSessionRestorer: TrustedSessionRestorer
) {
    val navController = rememberNavController()

    var isCheckingStartup by remember { mutableStateOf(true) }
    var startupRecoveryError by remember { mutableStateOf<String?>(null) }
    var recoveryAttempt by remember { mutableIntStateOf(0) }
    val startupRecoveryFailedMessage = stringResource(R.string.startup_recovery_failed)

    var startDestination by remember {
        mutableStateOf(Routes.SETUP)
    }

    /*
     * Financial startup gate.
     *
     * Navigation is not created until pending financial operations
     * have been safely reconciled.
     */
    LaunchedEffect(recoveryAttempt) {

        isCheckingStartup = true
        startupRecoveryError = null

        val recoveryResult =
            startupRecoveryCoordinator.recoverBeforeFinancialUse()

        if (recoveryResult.isFailure) {

            startupRecoveryError =
                recoveryResult.exceptionOrNull()?.message
                    ?: startupRecoveryFailedMessage

            isCheckingStartup = false

            return@LaunchedEffect
        }

        val isComplete =
            setupRepository.isSetupComplete()

        startDestination =
            if (!isComplete) {

                Routes.SETUP

            } else {

                val restoredSession =
                    trustedSessionRestorer.restore()

                if (restoredSession != null) {
                    when {
                        restoredSession.user.role == UserRole.ADMIN ->
                            Routes.ADMIN_HUB

                        restoredSession.requiresPinChange ->
                            Routes.CHANGE_PIN

                        else ->
                            Routes.REGISTER
                    }
                } else {
                    val debugSessionReady =
                        debugSessionBootstrapper.bootstrapIfDebug()

                    if (debugSessionReady) {
                        Routes.REGISTER
                    } else {
                        Routes.LOGIN
                    }
                }
            }

        isCheckingStartup = false
    }

    /*
     * Nothing financial is reachable while recovery is running.
     */
    if (isCheckingStartup) {
        return
    }

    /*
     * Fail closed.
     *
     * If startup recovery could not be completed, the cashier is not
     * allowed into the register. The user can retry the recovery pass.
     */
    startupRecoveryError?.let { errorMessage ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.startup_recovery_required)
            )

            Text(
                text = errorMessage,
                modifier = Modifier.padding(top = 12.dp)
            )

            Button(
                onClick = {
                    recoveryAttempt++
                },
                modifier = Modifier.padding(top = 20.dp)
            ) {
                Text(stringResource(R.string.retry))
            }
        }

        return
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {

        composable(Routes.SETUP) {

            val setupViewModel: SetupViewModel =
                hiltViewModel()

            SetupScreen(
                viewModel = setupViewModel,
                onSetupCompleted = {
                    navController.navigate(Routes.ADMIN_HUB) {
                        popUpTo(Routes.SETUP) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(Routes.LOGIN) {

            val loginViewModel: LoginViewModel =
                hiltViewModel()

            LoginScreen(
                viewModel = loginViewModel,
                onLoginSuccess = { session ->
                    val destination =
                        if (session.user.role == UserRole.ADMIN) {
                            Routes.ADMIN_HUB
                        } else if (session.requiresPinChange) {
                            Routes.CHANGE_PIN
                        } else {
                            Routes.REGISTER
                        }

                    navController.navigate(destination) {
                        popUpTo(Routes.LOGIN) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(Routes.CHANGE_PIN) {
            val changePinViewModel: ChangePinViewModel = hiltViewModel()
            ChangePinScreen(
                viewModel = changePinViewModel,
                onSuccess = {
                    navController.navigate(Routes.REGISTER) {
                        popUpTo(Routes.CHANGE_PIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.ADMIN_HUB) {

            AdminHubScreen(
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                },
                onNavigateToCashierManagement = {
                    navController.navigate(Routes.MANAGE_CASHIERS)
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onLogout = {
                    trustedSessionStore.clear()
                    SessionManager.clearSession()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.ADMIN_HUB) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(Routes.REGISTER) {

            val registerViewModel: RegisterViewModel =
                hiltViewModel()

            RegisterScreen(
                viewModel = registerViewModel,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.MANAGE_CASHIERS) {

            val cashierViewModel: CashierManagementViewModel =
                hiltViewModel()

            CashierManagementScreen(
                viewModel = cashierViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {

            val settingsViewModel:
                com.dailymoneyregister.feature.settings.ui.SettingsViewModel =
                hiltViewModel()

            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLogout = {
                    trustedSessionStore.clear()
                    SessionManager.clearSession()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) {
                            inclusive = true
                        }
                    }
                }
            )
        }
    }
}
