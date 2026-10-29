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
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.feature.register.ui.components.*
import com.dailymoneyregister.feature.settings.ui.MerchantQrImage
import com.dailymoneyregister.feature.invoice.ui.InvoiceFlow
import com.dailymoneyregister.feature.invoice.ui.InvoiceViewModel
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.feature.register.receipt.ReceiptRenderer
import com.dailymoneyregister.feature.register.receipt.ReceiptSharing

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
    
                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(
                                        R.string.dmr21_day_cash_sales,
                                        cashSalesDisplay
                                    )
                                )

                                Text(
                                    stringResource(
                                        R.string.dmr21_day_cash_coupon_funding,
                                        cashCouponFundingDisplay
                                    )
                                )
    
                                Text(
                                    stringResource(
                                        R.string.dmr21_day_cash_credit_payments,
                                        cashCreditDisplay
                                    )
                                )
    
                                Text(
                                    stringResource(
                                        R.string.dmr21_day_cash_refunds,
                                        cashRefundDisplay
                                    )
                                )
    
                                Text(
                                    stringResource(
                                        R.string.dmr21_day_cash_expenses,
                                        cashExpenseDisplay
                                    )
                                )
    
                                Text(
                                    text =
                                        stringResource(
                                            R.string.dmr21_day_expected_cash,
                                            expectedCashDisplay
                                        ),
                                    fontWeight =
                                        FontWeight.Bold
                                )
    
                                Text(
                                    stringResource(
                                        R.string.dmr21_day_online_expenses,
                                        onlineExpenseDisplay
                                    ),
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                            }
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

                            HorizontalDivider()

                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(
                                        R.string
                                            .dmr22_expected_cash,
                                        expectedDisplay
                                    )
                                )

                                Text(
                                    stringResource(
                                        R.string
                                            .dmr22_cash_counted,
                                        countedDisplay
                                    )
                                )

                                Text(
                                    text =
                                        stringResource(
                                            R.string
                                                .dmr22_difference,
                                            differenceDisplay
                                        ),
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Text(
                                    text =
                                        stringResource(
                                            R.string
                                                .dmr22_status,
                                            statusDisplay
                                        ),
                                    fontWeight =
                                        FontWeight.Bold
                                )
                            }
                        }

                        state.dayClosingResult?.let {
                            result ->

                            HorizontalDivider()

                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(
                                        R.string
                                            .dmr22_shift_owner,
                                        result
                                            .shiftOwnerDisplayName
                                    )
                                )

                                Text(
                                    stringResource(
                                        R.string
                                            .dmr22_closed_by,
                                        result
                                            .closedByDisplayName
                                    )
                                )

                                Text(
                                    stringResource(
                                        R.string
                                            .dmr22_closed_at,
                                        result.closedAtDisplay
                                    )
                                )
                            }
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
                if (state.dayClosingReport != null) {
                    if (state.dayClosingResult != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val dayCloseSummaryTitle =
                                stringResource(R.string.day_close_summary_title)
                            OutlinedButton(
                                onClick = {
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
                                }
                            ) {
                                Text(stringResource(R.string.share_day_summary))
                            }
                            Button(onClick = viewModel::closeDayClosing) {
                                Text(stringResource(R.string.done))
                            }
                        }
                    } else if (state.dayClosingReview) {
                        Button(
                            onClick =
                                viewModel::confirmDayClosing,
                            enabled = !state.isExecuting
                        ) {
                            Text(
                                if (state.isExecuting) {
                                    stringResource(R.string.dmr20_closing)
                                } else {
                                    stringResource(R.string.dmr20_confirm_close)
                                }
                            )
                        }
                    } else {
                        Button(
                            onClick =
                                viewModel::reviewDayClosing,
                            enabled = !state.isExecuting
                        ) {
                            Text(stringResource(R.string.dmr20_review))
                        }
                    }
                }
            },
            dismissButton = {
                if (state.dayClosingResult != null) {
                    // Final result has one explicit Done action.
                } else if (state.dayClosingReview) {
                    TextButton(
                        onClick =
                            viewModel::backFromDayClosingReview,
                        enabled = !state.isExecuting
                    ) {
                        Text(stringResource(R.string.dmr20_back))
                    }
                } else {
                    TextButton(
                        onClick =
                            viewModel::closeDayClosing,
                        enabled = !state.isExecuting
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
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
    
        AlertDialog(
            onDismissRequest = {
                if (!state.isExecuting) {
                    viewModel.clearSaleCustomer()
                    viewModel.closeCashCustomer()
                }
            },
            title = {
                Text(stringResource(R.string.audit_customer_optional), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.audit_customer_phone_instruction))
    
                    PhoneNumberInput(
                        value = customerPhone,
                        onValueChange = {
                            customerPhone = it
                            customerName = ""
                            viewModel.setSaleCustomerPhone(
                                runCatching { it.normalizedNumber() }.getOrNull().orEmpty()
                            )
                        },
                        enabled = !state.isExecuting &&
                            !state.saleCustomerLookupLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
    
                    if (normalizedPhone != null && !lookupCompleted) {
                        Button(
                            onClick = {
                                viewModel.setSaleCustomerPhone(normalizedPhone)
                                viewModel.lookupSaleCustomerByPhone(normalizedPhone)
                            },
                            enabled = !state.isExecuting &&
                                !state.saleCustomerLookupLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.saleCustomerLookupLoading)
                                    "Finding Customer..."
                                else
                                    "Find Customer"
                            )
                        }
                    }
    
                    if (existingCustomer != null) {
                        Text(
                            text = stringResource(R.string.audit_existing_customer, existingCustomer.name),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
    
                    if (needsNewCustomerName) {
                        Text(stringResource(R.string.audit_no_existing_customer))
    
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text(stringResource(R.string.audit_customer_name)) },
                            singleLine = true,
                            enabled = !state.isExecuting,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                when {
                    normalizedPhone == null -> {
                        Button(
                            onClick = {
                                viewModel.clearSaleCustomer()
                                viewModel.closeCashCustomer()
                                viewModel.executeCashSale()
                            },
                            enabled = !state.isExecuting
                        ) {
                            Text(stringResource(R.string.audit_skip_customer))
                        }
                    }
    
                    existingCustomer != null -> {
                        Button(
                            onClick = {
                                viewModel.setSaleCustomerPhone(normalizedPhone)
                                viewModel.setSaleCustomerName(existingCustomer.name)
                                viewModel.closeCashCustomer()
                                viewModel.executeCashSale()
                            },
                            enabled = !state.isExecuting &&
                                !state.saleCustomerLookupLoading
                        ) {
                            Text(stringResource(R.string.audit_save_sale))
                        }
                    }
    
                    needsNewCustomerName -> {
                        Button(
                            onClick = {
                                viewModel.setSaleCustomerPhone(normalizedPhone)
                                viewModel.setSaleCustomerName(customerName.trim())
                                viewModel.closeCashCustomer()
                                viewModel.executeCashSale()
                            },
                            enabled = !state.isExecuting &&
                                customerName.isNotBlank()
                        ) {
                            Text(stringResource(R.string.audit_save_sale))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.clearSaleCustomer()
                        viewModel.closeCashCustomer()
                    },
                    enabled = !state.isExecuting
                ) {
                    Text(stringResource(R.string.audit_cancel))
                }
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
    
                    Text(
                        stringResource(R.string.audit_customer_optional),
                        fontWeight = FontWeight.SemiBold
                    )
    
                    Text(
                        stringResource(R.string.audit_customer_phone_optional_instruction)
                    )
    
                    PhoneNumberInput(
                        value = onlineCustomerPhone,
                        onValueChange = {
                            onlineCustomerPhone = it
                            onlineCustomerName = ""
                            viewModel.setSaleCustomerPhone(
                                runCatching {
                                    it.normalizedNumber()
                                }.getOrNull().orEmpty()
                            )
                        },
                        enabled = !state.isExecuting &&
                            !state.saleCustomerLookupLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
    
                    if (onlineCustomerPhoneNormalized != null &&
                        !onlineLookupCompleted
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.setSaleCustomerPhone(
                                    onlineCustomerPhoneNormalized
                                )
                                viewModel.lookupSaleCustomerByPhone(
                                    onlineCustomerPhoneNormalized
                                )
                            },
                            enabled = !state.isExecuting &&
                                !state.saleCustomerLookupLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.saleCustomerLookupLoading)
                                    "Finding Customer..."
                                else
                                    "Find Customer"
                            )
                        }
                    }
    
                    if (onlineExistingCustomer != null) {
                        Text(
                            text = stringResource(R.string.audit_existing_customer, onlineExistingCustomer.name),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
    
                    if (onlineNeedsNewCustomerName) {
                        Text(stringResource(R.string.audit_no_existing_customer))
    
                        OutlinedTextField(
                            value = onlineCustomerName,
                            onValueChange = { onlineCustomerName = it },
                            label = { Text(stringResource(R.string.audit_customer_name)) },
                            singleLine = true,
                            enabled = !state.isExecuting,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
    
                    Button(
                        onClick = {
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
                        enabled =
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            stringResource(R.string.payment_received),
                            fontSize = 17.sp
                        )
                    }
                    TextButton(onClick = { viewModel.cancelOnlinePayment() }, enabled = !state.isExecuting) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}


