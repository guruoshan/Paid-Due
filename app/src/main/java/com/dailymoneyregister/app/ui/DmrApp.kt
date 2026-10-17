package com.dailymoneyregister.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
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
import com.dailymoneyregister.feature.notifications.ui.NotificationsScreen
import com.dailymoneyregister.feature.auth.ui.LoginScreen
import com.dailymoneyregister.feature.auth.ui.LoginViewModel
import com.dailymoneyregister.feature.auth.ui.ChangePinScreen
import com.dailymoneyregister.feature.auth.ui.ChangePinViewModel
import com.dailymoneyregister.feature.onboarding.ui.LanguagePickerScreen
import com.dailymoneyregister.feature.onboarding.ui.SignUpRoleScreen
import com.dailymoneyregister.feature.onboarding.ui.WelcomeScreen
import com.dailymoneyregister.feature.customers.ui.CustomerDetailScreen
import com.dailymoneyregister.feature.customers.ui.CustomerDetailScreenActions
import com.dailymoneyregister.feature.customers.ui.CustomerDetailViewModel
import com.dailymoneyregister.feature.customers.ui.CustomersScreen
import com.dailymoneyregister.feature.customers.ui.CustomersScreenActions
import com.dailymoneyregister.feature.customers.ui.CustomersViewModel
import com.dailymoneyregister.feature.register.ui.RegisterScreen
import com.dailymoneyregister.feature.register.ui.RegisterViewModel
import com.dailymoneyregister.feature.reporting.ui.ReportingScreen
import com.dailymoneyregister.feature.reporting.ui.ReportingViewModel
import com.dailymoneyregister.feature.settings.ui.SettingsScreen
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.ui.SetupScreen
import com.dailymoneyregister.feature.setup.ui.SetupViewModel
import com.dailymoneyregister.core.cloud.RemoteTenantBootstrapRecoveryViewModel
import com.dailymoneyregister.core.cloud.sync.FinancialSyncStatus
import kotlinx.coroutines.launch

object Routes {
    const val LANGUAGE = "language"
    const val WELCOME = "welcome"
    const val SIGNUP_ROLE = "signup_role"
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val CHANGE_PIN = "change_pin"
    const val ADMIN_HUB = "admin_hub"
    const val REGISTER = "register"
    const val SETTINGS = "settings"
    const val REPORTS = "reports"
    const val CUSTOMERS = "customers"
    const val CUSTOMER_DETAIL = "customers/{customerId}"
    const val NOTIFICATIONS = "notifications"
}

@Composable
fun DmrApp(
    setupRepository: SetupRepository,
    languageManager: LanguageManager,
    debugSessionBootstrapper: DebugSessionBootstrapper,
    startupRecoveryCoordinator: StartupRecoveryCoordinator,
    trustedSessionStore: TrustedSessionStore,
    trustedSessionRestorer: TrustedSessionRestorer,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val syncProblem by FinancialSyncStatus.adminVisibleProblem.collectAsStateWithLifecycle()
    val activeSession by SessionManager.currentSession.collectAsStateWithLifecycle()

    LaunchedEffect(syncProblem, activeSession?.user?.role) {
        val problem = syncProblem ?: return@LaunchedEffect
        if (activeSession?.user?.role == UserRole.ADMIN) {
            Toast.makeText(context, problem.message, Toast.LENGTH_LONG).show()
        }
    }


    val remoteTenantRecoveryViewModel: RemoteTenantBootstrapRecoveryViewModel =
        hiltViewModel()
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

        /*
         * Cloud tenant bootstrap recovery gate.
         *
         * A new cloud-enabled Admin setup cannot enter the
         * operational app until Supabase confirms the same
         * Business / Branch / Admin / membership identities.
         *
         * Existing installations without a pending marker
         * pass through unchanged.
         */
        val cloudBootstrapError =
            remoteTenantRecoveryViewModel
                .recoverPendingBootstrap()

        if (cloudBootstrapError != null) {

            startupRecoveryError =
                cloudBootstrapError

            isCheckingStartup = false

            return@LaunchedEffect
        }
        /*
         * Owner-only product mode. Existing local cashier sessions are
         * not deleted; they are signed out of the active UI. Historical
         * operations and actor IDs remain untouched in Room.
         */
        val restoredCandidate = trustedSessionRestorer.restore()
        val restoredSession = restoredCandidate?.takeIf {
            it.user.role == UserRole.ADMIN
        }
        if (restoredCandidate != null && restoredSession == null) {
            trustedSessionStore.clear()
            SessionManager.clearSession()
        }

        startDestination =
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

                val isComplete =
                    setupRepository.isSetupComplete()

                if (isComplete) {

                    val debugSessionReady =
                        debugSessionBootstrapper.bootstrapIfDebug()

                    if (debugSessionReady) {
                        Routes.REGISTER
                    } else {
                        if (languageManager.hasChosenLanguage()) {
                            Routes.WELCOME
                        } else {
                            Routes.LANGUAGE
                        }
                    }

                } else {

                    if (languageManager.hasChosenLanguage()) {
                        Routes.WELCOME
                    } else {
                        Routes.LANGUAGE
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
     * If startup recovery could not be completed, the owner is not
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

        composable(Routes.LANGUAGE) {
            LanguagePickerScreen(
                languageManager = languageManager,
                onContinue = {
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(Routes.LANGUAGE) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.WELCOME) {
            WelcomeScreen(
                onLogin = { navController.navigate(Routes.LOGIN) },
                onSignUp = { navController.navigate(Routes.SIGNUP_ROLE) }
            )
        }

        composable(Routes.SIGNUP_ROLE) {
            val scope = rememberCoroutineScope()
            var businessAlreadyExists by remember { mutableStateOf(false) }
            SignUpRoleScreen(
                onBusinessAdmin = {
                    scope.launch {
                        if (setupRepository.isSetupComplete()) {
                            businessAlreadyExists = true
                        } else {
                            navController.navigate(Routes.SETUP)
                        }
                    }
                },
                businessAlreadyExists = businessAlreadyExists,
                onGoToLogin = { navController.navigate(Routes.LOGIN) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETUP) {
            var setupAllowed by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                if (setupRepository.isSetupComplete()) {
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(0) { inclusive = true }
                    }
                } else {
                    setupAllowed = true
                }
            }

            if (setupAllowed == true) {
                val setupViewModel: SetupViewModel = hiltViewModel()

                SetupScreen(
                    viewModel = setupViewModel,
                    onSetupCompleted = {
                        navController.navigate(Routes.ADMIN_HUB) {
                            popUpTo(0) {
                                inclusive = true
                            }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
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
                        popUpTo(0) {
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
                onNavigateToCustomers = {
                    navController.navigate(Routes.CUSTOMERS)
                },
                onNavigateToReports = {
                    navController.navigate(Routes.REPORTS)
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToNotifications = {
                    navController.navigate(Routes.NOTIFICATIONS)
                },
                onLogout = {
                    trustedSessionStore.clear()
                    SessionManager.clearSession()
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(0) { inclusive = true }
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
                },
                onNavigateToCustomers = { navController.navigate(Routes.CUSTOMERS) },
                onNavigateToNotifications = { navController.navigate(Routes.NOTIFICATIONS) }
            )
        }

        composable(Routes.NOTIFICATIONS) {
            NotificationsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.REPORTS) {

            val reportingViewModel: ReportingViewModel =
                hiltViewModel()

            ReportingScreen(
                viewModel = reportingViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onOpenCustomer = { customerId ->
                    navController.navigate("customers/$customerId")
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
                onNavigateToCustomers = {
                    navController.navigate(Routes.CUSTOMERS)
                },
                onLogout = {
                    trustedSessionStore.clear()
                    SessionManager.clearSession()
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(0) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(Routes.CUSTOMERS) {

            val customersViewModel: CustomersViewModel =
                hiltViewModel()

            val customersState by
                customersViewModel.uiState.collectAsStateWithLifecycle()

            CustomersScreen(
                state = customersState,
                actions = CustomersScreenActions(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenCustomer = { customerId ->
                        navController.navigate("customers/$customerId")
                    },
                    onReload = customersViewModel::reload,
                    onQueryChange = customersViewModel::onQueryChange,
                    onSelectFilter = customersViewModel::selectFilter,
                    onAskToLinkOlderInvoices = customersViewModel::askToLinkOlderInvoices,
                    onConfirmLinkOlderInvoices = customersViewModel::confirmLinkOlderInvoices,
                    onDismissLinkConfirm = customersViewModel::dismissLinkConfirm
                )
            )
        }

        composable(
            route = Routes.CUSTOMER_DETAIL,
            arguments = listOf(
                navArgument("customerId") { type = NavType.StringType }
            )
        ) {

            val customerDetailViewModel: CustomerDetailViewModel =
                hiltViewModel()

            val customerDetailState by
                customerDetailViewModel.uiState.collectAsStateWithLifecycle()

            CustomerDetailScreen(
                state = customerDetailState,
                actions = CustomerDetailScreenActions(
                    onNavigateBack = { navController.popBackStack() },
                    onLoad = customerDetailViewModel::load,
                    onOpenReceivePayment = customerDetailViewModel::openReceivePayment,
                    onOpenInvoice = customerDetailViewModel::openInvoice,
                    onRepaymentAmountChange = customerDetailViewModel::onRepaymentAmountChange,
                    onSelectRepaymentMethod = customerDetailViewModel::selectRepaymentMethod,
                    onConfirmReceivePayment = customerDetailViewModel::confirmReceivePayment,
                    onCloseReceivePayment = customerDetailViewModel::closeReceivePayment,
                    onCloseInvoice = customerDetailViewModel::closeInvoice,
                    onSharePdf = customerDetailViewModel::sharePdf,
                    onShareWhatsApp = customerDetailViewModel::shareWhatsApp
                )
            )
        }
    }

}
