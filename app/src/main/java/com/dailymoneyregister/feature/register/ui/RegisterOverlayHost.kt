package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.ui.asString
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.feature.register.ui.components.*
import com.dailymoneyregister.feature.settings.ui.MerchantQrImage
import com.dailymoneyregister.feature.invoice.ui.InvoiceFlow
import com.dailymoneyregister.feature.invoice.ui.InvoiceViewModel
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.feature.register.receipt.ReceiptRenderer
import com.dailymoneyregister.feature.register.receipt.ReceiptSharing
import com.dailymoneyregister.feature.register.ui.dialogs.CashCustomerDialog
import com.dailymoneyregister.feature.register.ui.dialogs.DayClosingCashTotals
import com.dailymoneyregister.feature.register.ui.dialogs.DayClosingConfirmActions
import com.dailymoneyregister.feature.register.ui.dialogs.DayClosingDismissAction
import com.dailymoneyregister.feature.register.ui.dialogs.DayClosingReconciliationSummary
import com.dailymoneyregister.feature.register.ui.dialogs.DayClosingResultDetails
import com.dailymoneyregister.feature.register.ui.dialogs.OnlinePaymentCustomerSection

@Composable
internal fun RegisterOverlayHost(
    state: RegisterUiState,
    viewModel: RegisterViewModel,
    invoiceViewModel: InvoiceViewModel,
    decimalPlaces: Int,
    currency: com.dailymoneyregister.core.model.currency.CurrencyConfig,
    currencyCode: String,
    currencySymbol: String,
    merchantCountryCode: String,
    activeBusiness: com.dailymoneyregister.core.database.entity.BusinessEntity?,
    activeUser: com.dailymoneyregister.core.database.entity.UserAccountEntity?,
    onNavigateToSettings: () -> Unit
) {
    val shareContext = LocalContext.current
    if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.DayClosing) {
        AlertDialog(
            onDismissRequest = {
                if (!state.isExecuting) {
                    viewModel.closeDayClosing()
                }
            },
            title = {
                Text(
                    when {
                        state.dayClosingResult != null ->
                            stringResource(
                                R.string.dmr22_shift_closed
                            )

                        state.dayClosingReview ->
                            stringResource(
                                R.string.dmr20_confirm_day_closing
                            )

                        else ->
                            stringResource(
                                R.string.dmr20_day_closing
                            )
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.dayClosingLoading) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dmr20_day_loading))
                    } else if (state.dayClosingReport == null) {
                        Text(stringResource(R.string.dmr20_day_no_open))
                    } else {
                        if (state.dayClosingResult != null) {
                            Text(
                                stringResource(
                                    R.string.dmr22_shift_closed_success
                                ),
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                stringResource(
                                    R.string.dmr20_day_ready
                                ),
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            Text(
                                stringResource(
                                    R.string.dmr20_day_review_totals
                                )
                            )
                        }
    
                        state.dayClosingReport?.let { report ->
    
                            val cashSalesDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        report.cashSalesMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )
    
                            val cashCreditDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        report.cashCreditRepaymentsMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )
    
                            val cashRefundDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        Math.negateExact(
                                            report.cashRefundsMinor
                                        )
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )
    
                            val cashExpenseDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        Math.negateExact(
                                            report.cashExpensesMinor
                                        )
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )
    
                            val expectedCashDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        report.expectedCashMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )

                            val cashCouponFundingDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        report.cashCouponFundingMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )
    
                            val onlineExpenseDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        Math.negateExact(
                                            report.onlineExpensesMinor
                                        )
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )
    
                            DayClosingCashTotals(
                                cashSalesDisplay = cashSalesDisplay,
                                cashCouponFundingDisplay = cashCouponFundingDisplay,
                                cashCreditDisplay = cashCreditDisplay,
                                cashRefundDisplay = cashRefundDisplay,
                                cashExpenseDisplay = cashExpenseDisplay,
                                expectedCashDisplay = expectedCashDisplay,
                                onlineExpenseDisplay = onlineExpenseDisplay
                            )
                        }
    
                        OutlinedTextField(
                            value = state.dayClosingActualCash,
                            onValueChange =
                                viewModel::updateDayClosingActualCash,
                            label = {
                                Text(stringResource(R.string.dmr20_actual_cash))
                            },
                            singleLine = true,
                            enabled =
                                !state.isExecuting &&
                                    !state.dayClosingReview &&
                                    state.dayClosingResult == null
                        )
    
                        val previewCountedMinor =
                            parseDayClosingCountedMinor(
                                value =
                                    state.dayClosingActualCash,
                                decimalPlaces =
                                    decimalPlaces
                            )

                        val previewReconciliation =
                            if (
                                state.dayClosingReview &&
                                state.dayClosingResult == null &&
                                state.dayClosingReport != null &&
                                previewCountedMinor != null
                            ) {
                                dayClosingReconciliation(
                                    expectedCashMinor =
                                        state.dayClosingReport
                                            .expectedCashMinor,
                                    countedCashMinor =
                                        previewCountedMinor
                                )
                            } else {
                                null
                            }

                        val displayedReconciliation =
                            state.dayClosingResult
                                ?.reconciliation
                                ?: previewReconciliation

                        displayedReconciliation?.let {
                            reconciliation ->

                            val expectedDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        reconciliation
                                            .expectedCashMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )

                            val countedDisplay =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        reconciliation
                                            .countedCashMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )

                            val rawDifference =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        reconciliation
                                            .differenceMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )

                            val differenceDisplay =
                                if (
                                    reconciliation
                                        .differenceMinor > 0L
                                ) {
                                    "+$rawDifference"
                                } else {
                                    rawDifference
                                }

                            val statusDisplay =
                                when (
                                    reconciliation.status
                                ) {
                                    DayClosingVarianceStatus.SHORT ->
                                        stringResource(
                                            R.string
                                                .dmr22_status_short
                                        )

                                    DayClosingVarianceStatus.EXACT ->
                                        stringResource(
                                            R.string
                                                .dmr22_status_exact
                                        )

                                    DayClosingVarianceStatus.OVER ->
                                        stringResource(
                                            R.string
                                                .dmr22_status_over
                                        )
                                }

                            DayClosingReconciliationSummary(
                                expectedDisplay = expectedDisplay,
                                countedDisplay = countedDisplay,
                                differenceDisplay = differenceDisplay,
                                statusDisplay = statusDisplay
                            )
                        }

                        state.dayClosingResult?.let {
                            result ->

                            DayClosingResultDetails(
                                shiftOwnerDisplayName = result.shiftOwnerDisplayName,
                                closedByDisplayName = result.closedByDisplayName,
                                closedAtDisplay = result.closedAtDisplay
                            )
                        }

                        if (state.dayClosingReview) {
                            Text(
                                stringResource(R.string.dmr20_day_notice)
                            )
                        }
    
                        state.errorMessage?.let {
                            Text(
                                it.asString(),
                                color =
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                DayClosingConfirmActions(
                    hasReport = state.dayClosingReport != null,
                    hasResult = state.dayClosingResult != null,
                    isReview = state.dayClosingReview,
                    isExecuting = state.isExecuting,
                    onShareSummary = { dayCloseSummaryTitle ->
                        val report = state.dayClosingReport
                        val result = state.dayClosingResult
                        if (report != null && result != null) {
                            fun money(minor: Long): String =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(minor)
                                    .formatDisplay(decimalPlaces, currencySymbol)
                            val reconciliation = result.reconciliation
                            val summary = ReceiptRenderer.EventReceipt(
                                businessName = activeBusiness?.name.orEmpty().ifBlank { "Paid&Due" },
                                title = dayCloseSummaryTitle,
                                reference = report.shift.shiftId.value,
                                dateTime = result.closedAtDisplay,
                                customerName = null,
                                heading = "DAY CLOSE SUMMARY",
                                rows = listOf(
                                    ReceiptRenderer.EventReceipt.Row("Branch", report.shift.branchId),
                                    ReceiptRenderer.EventReceipt.Row("Sales count", report.salesCount.toString()),
                                    ReceiptRenderer.EventReceipt.Row("Gross sales", money(report.grossSalesMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Cash sales", money(report.cashSalesMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Online sales", money(report.onlineSalesMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Credit given", money(report.creditGivenMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Credit repaid", money(report.totalCreditRepaymentsMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Coupons used", money(report.couponUsedMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Coupons issued", money(report.couponIssuedMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Coupon top-ups", money(report.couponTopUpMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Cash refunds", money(report.cashRefundsMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Online refunds", money(report.onlineRefundsMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Refund count", report.refundCount.toString()),
                                    ReceiptRenderer.EventReceipt.Row("Void count", report.voidCount.toString()),
                                    ReceiptRenderer.EventReceipt.Row("Correction count", report.correctionCount.toString()),
                                    ReceiptRenderer.EventReceipt.Row("Cash expenses", money(report.cashExpensesMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Online expenses", money(report.onlineExpensesMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Expected cash", money(report.expectedCashMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Counted cash", money(reconciliation.countedCashMinor)),
                                    ReceiptRenderer.EventReceipt.Row("Cash difference", money(reconciliation.differenceMinor))
                                )
                            )
                            ReceiptSharing.shareDaySummary(shareContext, summary)
                        }
                    },
                    onDone = viewModel::closeDayClosing,
                    onConfirmClose = viewModel::confirmDayClosing,
                    onReview = viewModel::reviewDayClosing
                )
            },
            dismissButton = {
                DayClosingDismissAction(
                    hasResult = state.dayClosingResult != null,
                    isReview = state.dayClosingReview,
                    isExecuting = state.isExecuting,
                    onBack = viewModel::backFromDayClosingReview,
                    onCancel = viewModel::closeDayClosing
                )
            }
        )
    }
    
    if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.Expense) {
        ExpenseDialog(
            state = state,
            decimalPlaces = decimalPlaces,
            currencyCode = currencyCode,
            currencySymbol = currencySymbol,
            merchantCountryCode = merchantCountryCode,
            actions = ExpenseDialogActions(
                onStageSupportingDocument = viewModel::stageExpenseSupportingDocument,
                onLookupPayeeByPhone = viewModel::lookupExpensePayeeByPhone,
                onDismiss = viewModel::closeExpenses,
                onRemoveSupportingDocument = viewModel::removeStagedExpenseSupportingDocument,
                onOpenExpenseDetails = viewModel::openExpenseDetails,
                onSave = viewModel::saveExpense
            )
        )
    }
    
    // Dialogs
       if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.CreditSale) {
           CreditSaleDialog(
               state = state,
               merchantCountryCode = merchantCountryCode,
               actions = CreditSaleDialogActions(
                   onDismiss = viewModel::closeCreditSale,
                   onSearchCustomers = viewModel::searchCouponCustomers,
                   onConfirm = viewModel::confirmCreditSale
               )
           )
       }
       val couponOverlay = state.overlay as? RegisterOverlay.Coupon
       if (state.destination == RegisterDestination.Register && couponOverlay != null) {
        CouponCompactDialogR7A(
            state = state,
            decimalPlaces = decimalPlaces,
            startMode = couponOverlay.startMode,
            currencyCode = currencyCode,
            currencySymbol = currencySymbol,
            merchantCountryCode = merchantCountryCode,
            businessName = activeBusiness?.name.orEmpty(),
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
    
       if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.CouponWallet) {
        CouponWalletDialog(
            state = state,
            decimalPlaces = decimalPlaces,
            currencyCode = currencyCode,
            currencySymbol = currencySymbol,
            businessName = activeBusiness?.name.orEmpty(),
            actions = CouponWalletDialogActions(
                onDismiss = viewModel::closeCouponWallet,
                onSaveUser = viewModel::saveCouponUser
            )
        )
    }
    var itemNameQuery by remember { mutableStateOf("") }
    var itemSuggestions by remember { mutableStateOf(emptyList<ProductEntity>()) }
    LaunchedEffect(itemNameQuery) {
        itemSuggestions = if (itemNameQuery.isBlank()) {
            emptyList()
        } else {
            viewModel.searchProducts(itemNameQuery)
        }
    }
    val editingItem = state.editingItemId?.let { id -> state.draftState.items.firstOrNull { it.id == id } }
    
    if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.ItemizedBill) {
        ItemizedBillDialog(
            draftState = state.draftState,
            currency = currency,
            onDismiss = { viewModel.toggleItemizedBillDialog(false) },
            onOpenAddItem = { viewModel.toggleAddItemDialog(true) },
            onEditItem = { id -> viewModel.startEditingItem(id) },
            onUpdateQuantity = { id, qty -> viewModel.updateItemQuantity(id, qty) },
            onRemoveItem = { id -> viewModel.removeItem(id) },
            onOpenShippingPacking = { viewModel.toggleShippingPackingDialog(true) },
            onCreateInvoice = if (invoiceViewModel.canIssueInvoice()) {
                {
                    viewModel.toggleItemizedBillDialog(false)
                    invoiceViewModel.openForm()
                }
            } else null
        )
    }
    
    InvoiceFlow(
        viewModel = invoiceViewModel,
        draftState = state.draftState,
        currency = currency
    )
    
    if (state.destination == RegisterDestination.Register && state.overlay is RegisterOverlay.AddItem) {
        AddItemDialog(
            currency = currency,
            draftState = state.draftState,
            editingItem = editingItem,
            suggestions = itemSuggestions,
            onNameQueryChange = { itemNameQuery = it },
            onDismiss = {
                viewModel.toggleAddItemDialog(false)
                viewModel.cancelEditingItem()
            },
            onSaveTaxRate = { rate -> viewModel.updateTaxRatePercent(rate) },
            onAddItem = { name, priceText, qty, desc ->
                viewModel.addItem(name, priceText, qty, desc)
            },
            onUpdateItem = { name, priceText, qty, desc ->
                val id = state.editingItemId
                if (id != null) viewModel.editItem(id, name, priceText, qty, desc)
            }
        )
    }
    
    if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.Tax) {
        TaxDialog(
            draftState = state.draftState,
            currency = currency,
            onDismiss = { viewModel.toggleTaxDialog(false) },
            onSaveTaxRate = { rate ->
                viewModel.updateTaxRatePercent(rate)
            }
        )
    }
    
    if (state.destination == RegisterDestination.Register && state.overlay is RegisterOverlay.ShippingPacking) {
        ShippingPackingDialog(
            draftState = state.draftState,
            currency = currency,
            onDismiss = { viewModel.toggleShippingPackingDialog(false) },
            onSave = { sText, pText -> viewModel.updateTxShippingPacking(sText, pText) }
        )
    }
    
    if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.CashCustomer) {
        var customerName by remember { mutableStateOf("") }
        var customerPhone by remember {
            mutableStateOf(PhoneNumberInputValue(merchantCountryCode))
        }
    
        val normalizedPhone = remember(customerPhone) {
            runCatching { customerPhone.normalizedNumber() }.getOrNull()
        }
    
        val lookupCompleted =
            normalizedPhone != null &&
                state.saleCustomerLookupCompletedPhone == normalizedPhone
    
        val existingCustomer =
            state.saleCustomerLookupResult
                ?.takeIf { lookupCompleted }
    
        val needsNewCustomerName =
            lookupCompleted && existingCustomer == null
    
        CashCustomerDialog(
            customerName = customerName,
            customerPhone = customerPhone,
            normalizedPhone = normalizedPhone,
            lookupCompleted = lookupCompleted,
            existingCustomerName = existingCustomer?.name,
            needsNewCustomerName = needsNewCustomerName,
            isExecuting = state.isExecuting,
            isLookupLoading = state.saleCustomerLookupLoading,
            onPhoneChange = { value ->
                customerPhone = value
                customerName = ""
                viewModel.setSaleCustomerPhone(
                    runCatching { value.normalizedNumber() }.getOrNull().orEmpty()
                )
            },
            onNameChange = { customerName = it },
            onFindCustomer = { phone ->
                viewModel.setSaleCustomerPhone(phone)
                viewModel.lookupSaleCustomerByPhone(phone)
            },
            onSkipCustomer = {
                viewModel.clearSaleCustomer()
                viewModel.closeCashCustomer()
                viewModel.executeCashSale()
            },
            onUseExistingCustomer = { name ->
                viewModel.setSaleCustomerPhone(normalizedPhone.orEmpty())
                viewModel.setSaleCustomerName(name)
                viewModel.closeCashCustomer()
                viewModel.executeCashSale()
            },
            onSaveNewCustomer = { phone, name ->
                viewModel.setSaleCustomerPhone(phone)
                viewModel.setSaleCustomerName(name)
                viewModel.closeCashCustomer()
                viewModel.executeCashSale()
            },
            onDismiss = {
                viewModel.clearSaleCustomer()
                viewModel.closeCashCustomer()
            },
            onCancel = {
                viewModel.clearSaleCustomer()
                viewModel.closeCashCustomer()
            }
        )
    }
    if (state.destination == RegisterDestination.Register && state.overlay == RegisterOverlay.OnlinePayment) {
        BackHandler(enabled = !state.isExecuting) { viewModel.cancelOnlinePayment() }
        Dialog(onDismissRequest = { if (!state.isExecuting) viewModel.cancelOnlinePayment() }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.online_payment_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.amount_due, state.draftState.grandTotal.formatDisplay(currency)), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (state.onlineQr == null) {
                        Text(
                            stringResource(R.string.online_no_qr_configured),
                            textAlign = TextAlign.Center
                        )
    
                        if (activeUser?.role == com.dailymoneyregister.core.model.enums.UserRole.ADMIN) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.cancelOnlinePayment()
                                    onNavigateToSettings()
                                },
                                enabled = !state.isExecuting
                            ) {
                                Text(stringResource(R.string.online_add_qr))
                            }
                        }
                    } else {
                        state.onlineQr?.let { onlineQr ->
                            MerchantQrImage(
                                path = onlineQr.media.localReference,
                                contentDescription = stringResource(R.string.merchant_qr_preview),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            )
    
                            if (onlineQr.config.bankAppName.isNotBlank()) {
                                Text(
                                    onlineQr.config.bankAppName,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
    
                            onlineQr.config.accountLabel
                                ?.takeIf { it.isNotBlank() }
                                ?.let { Text(it) }
                        }
                    }
    
                    Text(
                        stringResource(R.string.online_customer_choice_hint),
                        textAlign = TextAlign.Center
                    )
    
                    Text(
                        stringResource(R.string.online_cashier_confirmation_warning),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
    
                    var onlineCustomerName by remember { mutableStateOf("") }
                    var onlineCustomerPhone by remember {
                        mutableStateOf(PhoneNumberInputValue(merchantCountryCode))
                    }
    
                    val onlineCustomerPhoneNormalized =
                        remember(onlineCustomerPhone) {
                            runCatching {
                                onlineCustomerPhone.normalizedNumber()
                            }.getOrNull()
                        }
    
                    val onlineLookupCompleted =
                        onlineCustomerPhoneNormalized != null &&
                            state.saleCustomerLookupCompletedPhone ==
                                onlineCustomerPhoneNormalized
    
                    val onlineExistingCustomer =
                        state.saleCustomerLookupResult
                            ?.takeIf { onlineLookupCompleted }
    
                    val onlineNeedsNewCustomerName =
                        onlineLookupCompleted &&
                            onlineExistingCustomer == null
    
                    OnlinePaymentCustomerSection(
                        customerName = onlineCustomerName,
                        customerPhone = onlineCustomerPhone,
                        normalizedPhone = onlineCustomerPhoneNormalized,
                        lookupCompleted = onlineLookupCompleted,
                        existingCustomerName = onlineExistingCustomer?.name,
                        needsNewCustomerName = onlineNeedsNewCustomerName,
                        isExecuting = state.isExecuting,
                        isLookupLoading = state.saleCustomerLookupLoading,
                        confirmEnabled =
                            !state.isExecuting &&
                                !state.saleCustomerLookupLoading &&
                                (
                                    onlineCustomerPhoneNormalized == null ||
                                        onlineExistingCustomer != null ||
                                        (
                                            onlineNeedsNewCustomerName &&
                                                onlineCustomerName.isNotBlank()
                                        )
                                ),
                        onPhoneChange = { value ->
                            onlineCustomerPhone = value
                            onlineCustomerName = ""
                            viewModel.setSaleCustomerPhone(
                                runCatching {
                                    value.normalizedNumber()
                                }.getOrNull().orEmpty()
                            )
                        },
                        onFindCustomer = { phone ->
                            viewModel.setSaleCustomerPhone(phone)
                            viewModel.lookupSaleCustomerByPhone(phone)
                        },
                        onNameChange = { onlineCustomerName = it },
                        onConfirm = {
                            when {
                                onlineCustomerPhoneNormalized == null -> {
                                    viewModel.clearSaleCustomer()
                                }

                                onlineExistingCustomer != null -> {
                                    viewModel.setSaleCustomerPhone(
                                        onlineCustomerPhoneNormalized
                                    )
                                    viewModel.setSaleCustomerName(
                                        onlineExistingCustomer.name
                                    )
                                }

                                onlineNeedsNewCustomerName -> {
                                    viewModel.setSaleCustomerPhone(
                                        onlineCustomerPhoneNormalized
                                    )
                                    viewModel.setSaleCustomerName(
                                        onlineCustomerName.trim()
                                    )
                                }
                            }
                            viewModel.executeOnlineSale()
                        },
                        onCancel = viewModel::cancelOnlinePayment
                    )
                }
            }
        }
    }
}


