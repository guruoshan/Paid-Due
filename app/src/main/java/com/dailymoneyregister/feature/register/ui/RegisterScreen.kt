package com.dailymoneyregister.feature.register.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.core.model.currency.CurrencyRegistry
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.feature.invoice.ui.InvoiceViewModel
import kotlin.math.roundToInt

@Composable
fun RegisterScreen(
    viewModel: RegisterViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
    invoiceViewModel: InvoiceViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentTxs by viewModel.recentTransactions.collectAsStateWithLifecycle()
    val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val windowWidthDp = windowDpSize.width.value.roundToInt()
    val windowHeightDp = windowDpSize.height.value.roundToInt()
    val sizing = remember(windowWidthDp, windowHeightDp) {
        RegisterResponsiveSizing.forScreen(windowWidthDp, windowHeightDp)
    }

    val activeBusiness = activeSession?.business
    val activeBranch = activeSession?.branch
    val activeUser = activeSession?.user
    val activeMembership = activeSession?.membership
    val currencyCode = activeBranch?.currencyCode ?: "OMR"
    val currencySymbol = activeBranch?.currencySymbol ?: currencyCode
    val merchantCountryCode = activeBranch?.countryCode ?: "OM"
    val decimalPlaces = activeBranch?.currencyDecimalPlaces ?: 3
    val currency = CurrencyRegistry.findByCode(currencyCode).copy(
        decimalPlaces = decimalPlaces,
        symbol = currencySymbol
    )
    fun permitted(action: PermissionAction): Boolean =
        activeUser?.let { PermissionChecker.isPermitted(it, activeMembership, action) } == true

    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            Toast.makeText(context, it.resolve(context), Toast.LENGTH_SHORT).show()
            viewModel.clearInfoMessage()
        }
    }

    LaunchedEffect(state.destination) {
        if (state.destination == RegisterDestination.Register) {
            historyViewModel.stopHistory()
        }
    }

    RegisterMainContentHost(
        state = state,
        recentTxs = recentTxs,
        activeBusiness = activeBusiness,
        activeBranch = activeBranch,
        activeUser = activeUser,
        currencyCode = currencyCode,
        currencySymbol = currencySymbol,
        merchantCountryCode = merchantCountryCode,
        decimalPlaces = decimalPlaces,
        currency = currency,
        sizing = sizing,
        canCorrectAmount = permitted(PermissionAction.CORRECT_AMOUNT),
        canRefund = permitted(PermissionAction.REFUND),
        canVoid = permitted(PermissionAction.VOID),
        canChangePaymentMethod = permitted(PermissionAction.CHANGE_PAYMENT_METHOD),
        viewModel = viewModel,
        invoiceViewModel = invoiceViewModel,
        historyState = historyState,
        historyViewModel = historyViewModel,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToCustomers = onNavigateToCustomers,
        onNavigateToNotifications = onNavigateToNotifications
    )
}

