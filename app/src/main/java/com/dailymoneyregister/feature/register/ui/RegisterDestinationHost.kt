package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.ui.asString
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.enums.UserRole
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun RegisterDestinationHost(
    state: RegisterUiState,
    businessName: String,
    activeBranch: com.dailymoneyregister.core.database.entity.BranchEntity?,
    currency: com.dailymoneyregister.core.model.currency.CurrencyConfig,
    decimalPlaces: Int,
    currencySymbol: String,
    canCorrectAmount: Boolean,
    canRefund: Boolean,
    canVoid: Boolean,
    canChangePaymentMethod: Boolean,
    viewModel: RegisterViewModel,
    historyState: HistoryUiState,
    historyViewModel: HistoryViewModel
) {
    val currentSession by SessionManager.currentSession.collectAsStateWithLifecycle()
    when (val destination = state.destination) {
        RegisterDestination.Register -> Unit
    
        RegisterDestination.History -> {
            Surface(modifier = Modifier.fillMaxSize()) {
                val branch = activeBranch
                if (branch == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    HistoryScreen(
                        state = historyState,
                        currency = currency,
                        timezoneId = branch.timezoneId,
                        onBack = {
                            historyViewModel.stopHistory()
                            viewModel.closeHistory()
                        },
                        onQueryChanged = historyViewModel::updateQuery,
                        onFilterChanged = historyViewModel::updateFilter,
                        onTransactionSelected = viewModel::openTransactionDetails,
                        onExpenseSelected = viewModel::openExpenseDetails
                    )
                }
            }
        }
    
        is RegisterDestination.TransactionDetails -> {
            Surface(modifier = Modifier.fillMaxSize()) {
                val details = state.transactionDetails
                if (details == null) {
                    BackHandler(onBack = viewModel::closeTransactionDetails)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        BackHandler(
                            enabled = !state.isExecuting,
                            onBack = viewModel::closeTransactionDetails
                        )
                        TransactionDetailsScreen(
                            details = details,
                            currency = currency,
                            timezoneId = activeBranch?.timezoneId,
                            onBack = viewModel::closeTransactionDetails,
                            onCorrectAmount = viewModel::executeAmountCorrectionFromDetails,
                            onRefund = viewModel::executeRefundFromDetails,
                            onRefundToCoupon = viewModel::beginRefundToCouponFromDetails,
                            onVoid = viewModel::executeVoidFromDetails,
                            onChangePaymentMethod = viewModel::executePaymentMethodChangeFromDetails,
                            correctAmountEnabled = canCorrectAmount && !state.isExecuting,
                            refundEnabled = canRefund,
                            voidEnabled = canVoid,
                            changePaymentEnabled = canChangePaymentMethod,
                            actionInProgress = state.isExecuting,
                            actionError = state.errorMessage?.asString(),
                            actionInfo = state.infoMessage?.asString()
                        )
    
                        val refundOverlay = state.overlay as? RegisterOverlay.Coupon
                        if (refundOverlay != null && state.refundToCouponDraft != null) {
                            CouponCompactDialogR7A(
                                state = state,
                                decimalPlaces = decimalPlaces,
                                startMode = refundOverlay.startMode,
                                currencyCode = activeBranch?.currencyCode ?: "OMR",
                                currencySymbol = currencySymbol,
                                merchantCountryCode = activeBranch?.countryCode ?: "OM",
                                businessName = viewModel.activeBusiness?.name.orEmpty(),
                                actions = CouponCompactDialogActions(
                                    onDismiss = viewModel::closeCouponSale,
                                    onStartNewSale = viewModel::startNewSale,
                                    onSearchCustomers = viewModel::searchCouponCustomers,
                                    onOpenWallet = viewModel::openCouponWallet,
                                    onCloseWallet = viewModel::closeCouponWallet,
                                    onSaveUser = viewModel::saveCouponUser,
                                    onConfirmRefund = viewModel::confirmRefundToCoupon,
                                    onConfirmRedemption = viewModel::confirmCouponRedemption,
                                    onConfirmTopUp = viewModel::confirmCouponTopUp,
                                    onConfirmSale = viewModel::confirmCouponSale,
                                    onLoadReceiptDetails = viewModel::loadSaleDetailsForWhatsApp
                                )
                            )
                        }
                    }
                }
            }
        }
    
        is RegisterDestination.ExpenseDetails -> {
            Surface(modifier = Modifier.fillMaxSize()) {
                val details = state.expenseDetails
                if (details == null) {
                    BackHandler(onBack = viewModel::closeExpenseDetails)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    ExpenseDetailsScreen(
                        details = details,
                        decimalPlaces = decimalPlaces,
                        currencySymbol = currencySymbol,
                        businessName = businessName,
                        timezoneId = activeBranch?.timezoneId ?: "Asia/Muscat",
                        supportingDocuments = state.expenseSupportingDocuments,
                        supportingDocumentsLoading = state.expenseSupportingDocumentsLoading,
                        supportingDocumentAttaching = state.expenseSupportingDocumentAttaching,
                        onRefreshSupportingDocuments = {
                            viewModel.refreshExpenseSupportingDocuments(details.expenseId)
                        },
                        onAttachSupportingDocument = { sourceUri ->
                            viewModel.attachSupportingDocumentToExpense(details.expenseId, sourceUri)
                        },
                        canManageFinancialLifecycle = currentSession?.user?.role == UserRole.ADMIN,
                        onCorrectExpense = viewModel::correctExpenseFromDetails,
                        onVoidExpense = viewModel::voidExpenseFromDetails,
                        onBack = viewModel::closeExpenseDetails
                    )
                }
            }
        }
    }
}

