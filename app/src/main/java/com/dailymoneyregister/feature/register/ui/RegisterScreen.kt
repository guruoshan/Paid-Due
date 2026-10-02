package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.ui.asString
import com.dailymoneyregister.core.model.currency.CurrencyRegistry

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.theme.RegisterColors
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.feature.register.ui.components.*
import com.dailymoneyregister.feature.settings.ui.MerchantQrImage
import androidx.hilt.navigation.compose.hiltViewModel
import com.dailymoneyregister.feature.invoice.ui.InvoiceFlow
import com.dailymoneyregister.feature.invoice.ui.InvoiceViewModel
import com.dailymoneyregister.core.database.entity.ProductEntity

internal enum class CouponDialogStartMode(val startsWithRedemption: Boolean) {
    ENTRY(false),
    REDEMPTION(true)
}

internal val CouponDialogStartMode.opensSellCouponDialog: Boolean
    get() = !startsWithRedemption

internal val CouponDialogStartMode.dialogTitleRes: Int
    get() = if (startsWithRedemption) R.string.coupon_use_action else R.string.coupon_purchase_title

internal val CouponDialogStartMode.confirmLabelRes: Int
    get() = if (startsWithRedemption) R.string.coupon_use_action else R.string.coupon_purchase_confirm

internal data class CouponPaymentActionUi(
    val visible: Boolean,
    val enabled: Boolean,
    val dialogStartMode: CouponDialogStartMode,
    val labelRes: Int
)

internal enum class RegisterCouponActionRoute {
    PAYMENT_COUPON
}

internal data class RegisterCouponActionRender(
    val route: RegisterCouponActionRoute,
    val labelRes: Int,
    val dialogStartMode: CouponDialogStartMode,
    val visible: Boolean
)

internal fun couponPaymentActionUi(state: RegisterUiState): CouponPaymentActionUi {
    val activeSale = state.draftState.grandTotal.isPositive
    return CouponPaymentActionUi(
        visible = true,
        enabled = !state.isExecuting && state.lastCompletedTransaction == null,
        dialogStartMode = if (activeSale) CouponDialogStartMode.REDEMPTION else CouponDialogStartMode.ENTRY,
        labelRes = if (activeSale) R.string.coupon_use_action else R.string.coupon
    )
}

internal fun registerScreenCouponActions(state: RegisterUiState): List<RegisterCouponActionRender> {
    val paymentAction = couponPaymentActionUi(state)
    return listOf(
        RegisterCouponActionRender(
            route = RegisterCouponActionRoute.PAYMENT_COUPON,
            labelRes = paymentAction.labelRes,
            dialogStartMode = paymentAction.dialogStartMode,
            visible = paymentAction.visible
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    viewModel: RegisterViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    invoiceViewModel: InvoiceViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val recentTxs by viewModel.recentTransactions.collectAsState()
    val historyState by historyViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val sizing = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        RegisterResponsiveSizing.forScreen(configuration.screenWidthDp, configuration.screenHeightDp)
    }

    val decimalPlaces = viewModel.activeBranch?.currencyDecimalPlaces ?: 3
    val currency = CurrencyRegistry.findByCode(viewModel.currencyCode).copy(
        decimalPlaces = decimalPlaces,
        symbol = viewModel.currencySymbol
    )

    val transactionDetails = state.transactionDetails
    if (transactionDetails != null) {

        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {

            TransactionDetailsScreen(
                details = transactionDetails,
                currency = currency,
                timezoneId = viewModel.activeBranch?.timezoneId,
                onBack = viewModel::closeTransactionDetails,
                onCorrectAmount = viewModel::executeAmountCorrectionFromDetails,
                onRefund = viewModel::executeRefundFromDetails,
                onRefundToCoupon =
                    viewModel::beginRefundToCouponFromDetails,
                onVoid = viewModel::executeVoidFromDetails,
                onChangePaymentMethod =
                    viewModel::executePaymentMethodChangeFromDetails,
                correctAmountEnabled = !state.isExecuting,
                refundEnabled = viewModel.canRefundFromDetails,
                voidEnabled = viewModel.canVoidFromDetails,
                changePaymentEnabled =
                    viewModel.canChangePaymentMethodFromDetails,
                actionInProgress = state.isExecuting,
                actionError = state.errorMessage?.asString(),
                actionInfo = state.infoMessage?.asString()
            )

            if (
                state.isCouponSaleOpen &&
                state.refundToCouponDraft != null
            ) {
                CouponCompactDialogR7A(
                    viewModel = viewModel,
                    state = state,
                    decimalPlaces = decimalPlaces,
                    startMode =
                        CouponDialogStartMode.ENTRY
                )
            }
        }

        return
    }
    if (historyState.isOpen) {
        val historyBranch = viewModel.activeBranch

        if (historyBranch != null) {
            HistoryScreen(
                state = historyState,
                currency = currency,
                timezoneId = historyBranch.timezoneId,
                onBack = historyViewModel::closeHistory,
                onQueryChanged = historyViewModel::updateQuery,
                onFilterChanged = historyViewModel::updateFilter,
                onTransactionSelected = viewModel::openTransactionDetails
            )
            return
        }
    }

    val creditName = stringResource(R.string.payment_credit_name)
    val onlineName = stringResource(R.string.payment_online_name)
    val customerName = stringResource(R.string.customer)
    val shareName = stringResource(R.string.share)
    val couponPaymentAction = couponPaymentActionUi(state)
    var couponDialogStartMode by rememberSaveable { mutableStateOf(CouponDialogStartMode.ENTRY) }

    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            Toast.makeText(context, it.resolve(context), Toast.LENGTH_SHORT).show()
            viewModel.clearInfoMessage()
        }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Paid&Due",
                        modifier = Modifier.weight(0.28f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f),
                        maxLines = 1,
                        softWrap = false
                    )

                    Box(
                        modifier = Modifier
                            .weight(0.72f)
                            .padding(horizontal = 6.dp)
                    ) {
                        CompactDescriptionField(
                            value = state.manualDescription,
                            onValueChange = { viewModel.onManualDescriptionChanged(it) },
                            placeholder = stringResource(R.string.note_optional)
                        )
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.menu),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.errorMessage?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error.asString(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }


                // Compact amount display
                CompactAmountDisplay(
                    currencyCode = viewModel.currencyCode,
                    expression = state.calculatorState.expression,
                    totalText = state.draftState.grandTotal
                        .formatDisplay(decimalPlaces, "")
                        .trim(),
                    totalTextSize = sizing.totalTextSize.sp,
                    isItemized = state.draftState.isItemized,
                    itemCount = state.draftState.items.size,
                    showTax = state.draftState.isTaxEnabled &&
                        !state.draftState.taxAmount.isZero,
                    taxText = stringResource(R.string.tax_amount, state.draftState.taxAmount.formatDisplay(currency)),
                    showShippingPacking =
                        !state.draftState.txShipping.isZero ||
                        !state.draftState.txPacking.isZero ||
                        !state.draftState.itemShippingTotal.isZero ||
                        !state.draftState.itemPackingTotal.isZero,
                    shippingPackingText = stringResource(
                        R.string.ship_pack_amount,
                        (
                            state.draftState.txShipping +
                                state.draftState.txPacking +
                                state.draftState.itemShippingTotal +
                                state.draftState.itemPackingTotal
                            ).formatDisplay(currency)
                    ),
                    onBackspace = {
                        viewModel.onKeypadPress(
                            com.dailymoneyregister.feature.register.domain.KeypadKey.Backspace
                        )
                    },
                    onOpenItems = {
                        viewModel.toggleItemizedBillDialog(true)
                    },
                    onOpenTax = {
                        viewModel.toggleTaxDialog(true)
                    },
                    onClearTax = {
                        viewModel.updateTaxRatePercent("0")
                    },
                    onOpenShippingPacking = {
                        viewModel.toggleShippingPackingDialog(true)
                    },
                    onClearShippingPacking = {
                        viewModel.updateTxShippingPacking("0", "0")
                    }
                )
                // Action Bar Controls (Bill, Tax, Shipping/Packing)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.toggleItemizedBillDialog(true) },
                        modifier = Modifier.weight(1f).height(sizing.actionHeight),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1EAFE), contentColor = Color(0xFF5B21B6))
                    ) {
                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (state.draftState.isItemized) stringResource(R.string.bill_with_count, stringResource(R.string.invoice), state.draftState.items.size.toString()) else stringResource(R.string.invoice),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { viewModel.toggleTaxDialog(true) },
                        modifier = Modifier.weight(1f).height(sizing.actionHeight),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF4CC), contentColor = Color(0xFF713F12))
                    ) {
                        Icon(Icons.Default.Percent, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.tax),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { viewModel.toggleShippingPackingDialog(true) },
                        modifier = Modifier.weight(1f).height(sizing.actionHeight),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDF5F1), contentColor = Color(0xFF115E59))
                    ) {
                        Text(
                            text = stringResource(R.string.ship_pack),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { viewModel.clearDraft() },
                        modifier = Modifier.weight(1f).height(sizing.actionHeight),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFEE2E2),
                            contentColor = Color(0xFFB91C1C)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.clear),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                // Calculator Keypad Grid
                RegisterKeypad(
                    onKeyPress = { viewModel.onKeypadPress(it) },
                    buttonHeight = sizing.keypadButtonHeight,
                    rowSpacing = sizing.keypadSpacing
                )

                // Compact payment method bar
                PaymentMethodBar(
                    cashEnabled = !state.isExecuting &&
                        !state.draftState.grandTotal.isZero,
                    isExecuting = state.isExecuting,
                    onCoupon = {
                        couponDialogStartMode = couponPaymentAction.dialogStartMode
                        if (couponPaymentAction.dialogStartMode.startsWithRedemption) {
                            viewModel.openCouponRedemption()
                        } else {
                            viewModel.openCouponSale()
                        }
                    },
                    onCredit = {
                        viewModel.openCreditSale()
                    },
                    onOnline = {
                        viewModel.openOnlinePayment()
                    },
                    onCash = {
                        viewModel.executeCashSale()
                    },
                    buttonHeight = sizing.actionHeight,
                    couponEnabled = couponPaymentAction.enabled,
                    couponLabelRes = couponPaymentAction.labelRes
                )
                RegisterSecondaryActionBar(
                    onHistory = historyViewModel::openHistory,
                    onCustomer = onNavigateToCustomers,
                    onClear = { viewModel.triggerDisabledPaymentMethod("Day Closing") },
                    onShare = { viewModel.triggerDisabledPaymentMethod("Expenses") },
                    buttonHeight = sizing.actionHeight
                )
                RecentTransactionsPanel(
                    transactions = recentTxs,
                    isExpanded = state.isRecentTransactionsExpanded,
                    currency = currency,
                    timezoneId = viewModel.activeBranch?.timezoneId ?: "Asia/Muscat",
                    onToggleExpanded = { viewModel.toggleRecentTransactionsExpanded() },
                    onTransactionSelected = viewModel::openTransactionDetails,
                    maxListHeight = sizing.recentMaxHeight,
                    compactMaxListHeight = sizing.compactRecentMaxHeight
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // In-place Cash Sale Success Overlay
            state.lastCompletedTransaction?.takeIf { state.couponRedemptionResult == null }?.let { completedTx ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!state.creditSaleSharePhone.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = RegisterColors.CashContainer,
                            shadowElevation = 6.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = 22.dp,
                                    vertical = 18.dp
                                ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Sale Saved",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RegisterColors.CashPrimary
                                )

                                Button(
                                    onClick = {
                                        val customerName =
                                            state.creditSaleShareName
                                                ?.takeIf { it.isNotBlank() }
                                                ?: "Customer"

                                        val invoiceOccurredAt =


                                            state.creditInvoicePurchaseOccurredAt



                                        val creditGivenMinor =


                                            state.creditInvoiceGivenMinor



                                        val purchaseOutstandingMinor =


                                            state.creditInvoicePurchaseOutstandingMinor



                                        val totalOutstandingMinor =


                                            state.creditInvoiceTotalOutstandingMinor



                                        val invoiceCurrency =


                                            state.creditInvoiceCurrencyCode



                                        val invoiceDecimals =


                                            state.creditInvoiceDecimalPlaces



                                        if (


                                            invoiceOccurredAt == null ||


                                            creditGivenMinor == null ||


                                            purchaseOutstandingMinor == null ||


                                            totalOutstandingMinor == null ||


                                            invoiceCurrency.isNullOrBlank() ||


                                            invoiceDecimals == null


                                        ) {


                                            android.widget.Toast.makeText(


                                                context,


                                                "Credit invoice details are unavailable.",


                                                android.widget.Toast.LENGTH_SHORT


                                            ).show()


                                            return@Button


                                        }



                                        fun formatCreditAmount(


                                            amountMinor: Long


                                        ): String {


                                            var divisor = 1L


                                            repeat(invoiceDecimals) {


                                                divisor =


                                                    Math.multiplyExact(


                                                        divisor,


                                                        10L


                                                    )


                                            }



                                            val whole = amountMinor / divisor



                                            if (invoiceDecimals == 0) {


                                                return "$invoiceCurrency $whole"


                                            }



                                            val fraction =


                                                kotlin.math.abs(


                                                    amountMinor % divisor


                                                )


                                                    .toString()


                                                    .padStart(


                                                        invoiceDecimals,


                                                        '0'


                                                    )



                                            return "$invoiceCurrency $whole.$fraction"


                                        }



                                        val purchaseDate =


                                            java.text.SimpleDateFormat(


                                                "MMM d, yyyy",


                                                java.util.Locale.getDefault()


                                            ).format(


                                                java.util.Date(


                                                    invoiceOccurredAt


                                                )


                                            )



                                        val businessName =


                                            viewModel.activeBusiness


                                                ?.name


                                                ?.trim()


                                                ?.takeIf {


                                                    it.isNotEmpty()


                                                }


                                                ?: "Paid&Due"



                                        val message =


                                            buildString {


                                                append(businessName)


                                                append("\n\n")


                                                append("CREDIT INVOICE")


                                                append("\n\n")


                                                append("Dear ")


                                                append(customerName)


                                                append(",")


                                                append("\n\n")


                                                append("Purchase date: ")


                                                append(purchaseDate)


                                                append("\n")


                                                append("Credit given: ")


                                                append(


                                                    formatCreditAmount(


                                                        creditGivenMinor


                                                    )


                                                )


                                                append("\n\n")


                                                append("Pending for this purchase: ")


                                                append(


                                                    formatCreditAmount(


                                                        purchaseOutstandingMinor


                                                    )


                                                )


                                                append("\n")


                                                append("Total credit pending: ")


                                                append(


                                                    formatCreditAmount(


                                                        totalOutstandingMinor


                                                    )


                                                )


                                                append("\n\n")


                                                append("Thank you for your business.")


                                                append("\n\n")


                                                append("Powered by Paid&Due")


                                            }

                                        openCouponWhatsApp(
                                            context,
                                            state.creditSaleSharePhone!!,
                                            message
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF25D366),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("WhatsApp")
                                }

                                TextButton(
                                    onClick = {
                                        viewModel.startNewSale()
                                    }
                                ) {
                                    Text("Done")
                                }
                            }
                        }
                    } else {
                        CashSaleSuccessOverlay(
                            transaction = completedTx,
                            onStartNewSale = {
                                viewModel.startNewSale()
                            },
                            onDisabledAction = {
                                viewModel.triggerDisabledPaymentMethod(it)
                            }
                        )
                    }
                }
            }
        }

        // Dialogs
           if (state.isCreditSaleOpen) {
               CreditSaleDialog(
                   viewModel = viewModel,
                   state = state
               )
           }
           if (state.isCouponSaleOpen) {
            CouponCompactDialogR7A(viewModel, state, decimalPlaces, couponDialogStartMode)
         }

           if (state.isCouponWalletOpen) {
            CouponWalletDialog(viewModel, state, decimalPlaces)
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

        if (state.isItemizedBillDialogOpen) {
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

        if (state.isAddItemDialogOpen) {
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

        if (state.isTaxDialogOpen) {
            TaxDialog(
                draftState = state.draftState,
                currency = currency,
                onDismiss = { viewModel.toggleTaxDialog(false) },
                onSaveTaxRate = { rate ->
                    viewModel.updateTaxRatePercent(rate)
                }
            )
        }

        if (state.isShippingPackingDialogOpen) {
            ShippingPackingDialog(
                draftState = state.draftState,
                currency = currency,
                onDismiss = { viewModel.toggleShippingPackingDialog(false) },
                onSave = { sText, pText -> viewModel.updateTxShippingPacking(sText, pText) }
            )
        }

        if (state.isOnlinePaymentOpen) {
            BackHandler(enabled = !state.isExecuting) { viewModel.cancelOnlinePayment() }
            Dialog(onDismissRequest = { if (!state.isExecuting) viewModel.cancelOnlinePayment() }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
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

                            if (viewModel.activeUser?.role == com.dailymoneyregister.core.model.enums.UserRole.ADMIN) {
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

                        Button(
                            onClick = { viewModel.executeOnlineSale() },
                            enabled = !state.isExecuting,
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
}

@Composable
private fun CreditSaleDialog(
    viewModel: RegisterViewModel,
    state: RegisterUiState
) {

    
    var searchByPhone by rememberSaveable {
        mutableStateOf(true)
    }

    var searchName by remember {
        mutableStateOf("")
    }

    var searchPhone by remember {
        mutableStateOf(
            PhoneNumberInputValue(
                viewModel.merchantCountryCode
            )
        )
    }

    var searched by rememberSaveable {
        mutableStateOf(false)
    }

    var customer by remember {
        mutableStateOf<
            com.dailymoneyregister.core.database.entity.CustomerEntity?
        >(null)
    }

    var createCustomer by rememberSaveable {
        mutableStateOf(false)
    }

    var customerName by remember {
        mutableStateOf("")
    }

    var customerPhone by remember {
        mutableStateOf(
            PhoneNumberInputValue(
                viewModel.merchantCountryCode
            )
        )
    }

    val searchPhoneNormalized =
        remember(searchPhone) {
            runCatching {
                searchPhone.normalizedNumber()
            }.getOrNull()
        }

    val customerPhoneNormalized =
        remember(customerPhone) {
            runCatching {
                customerPhone.normalizedNumber()
            }.getOrNull()
        }

    val locked =
        state.isExecuting ||
            state.inFlightCreditSaleCommand != null

    val canConfirm =
        !locked &&
            (
                customer != null ||
                    (
                        createCustomer &&
                            customerName.isNotBlank() &&
                            customerPhoneNormalized != null
                    )
            )

    AlertDialog(
        onDismissRequest = {
            if (!locked) {
                viewModel.closeCreditSale()
            }
        },

        title = {
            Text(
                text = "Credit Sale",
                fontWeight = FontWeight.Bold
            )
        },

        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(
                            rememberScrollState()
                        ),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text =
                        "Select the customer who is taking this sale on Credit."
                )

                state.errorMessage?.let { message ->
                    Text(
                        text = message.asString(),
                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }

                if (
                    customer == null &&
                    !createCustomer
                ) {
                    Text(
                        text = "Find Customer",
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = searchByPhone,

                            onClick = {
                                if (!locked) {
                                    searchByPhone = true
                                    searched = false
                                }
                            },

                            label = {
                                Text("Phone")
                            }
                        )

                        FilterChip(
                            selected = !searchByPhone,

                            onClick = {
                                if (!locked) {
                                    searchByPhone = false
                                    searched = false
                                }
                            },

                            label = {
                                Text("Name")
                            }
                        )
                    }

                    if (searchByPhone) {
                        PhoneNumberInput(
                            value = searchPhone,

                            onValueChange = {
                                searchPhone = it
                            },

                            enabled = !locked,

                            showValidation =
                                searched &&
                                    searchPhone.rawNumber
                                        .isNotBlank() &&
                                    searchPhoneNormalized == null
                        )

                    } else {
                        OutlinedTextField(
                            value = searchName,

                            onValueChange = {
                                searchName = it
                            },

                            modifier =
                                Modifier.fillMaxWidth(),

                            enabled = !locked,

                            singleLine = true,

                            label = {
                                Text("Customer Name")
                            }
                        )
                    }

                    Button(
                        onClick = {
                            searched = true
                            customer = null
                            createCustomer = false

                            val searchValue =
                                if (searchByPhone) {
                                    searchPhoneNormalized
                                        ?: searchPhone
                                            .rawNumber
                                            .trim()
                                } else {
                                    searchName.trim()
                                }

                            if (searchValue.isNotBlank()) {
                                viewModel
                                    .searchCouponCustomers(
                                        searchValue
                                    )
                            }
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        enabled =
                            !locked &&
                                !state.couponSearching &&
                                (
                                    (
                                        searchByPhone &&
                                            searchPhone
                                                .rawNumber
                                                .isNotBlank()
                                    ) ||
                                        (
                                            !searchByPhone &&
                                                searchName
                                                    .isNotBlank()
                                        )
                                )
                    ) {
                        Text(
                            if (state.couponSearching) {
                                "Searching..."
                            } else {
                                "Search Customer"
                            }
                        )
                    }

                    if (
                        searched &&
                        !state.couponSearching
                    ) {
                        if (state.couponCustomers.isEmpty()) {
                            Text(
                                "No customer found."
                            )

                        } else {
                            Text(
                                text = "Search Results",
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            state.couponCustomers
                                .forEach { found ->

                                    OutlinedButton(
                                        onClick = {
                                            customer =
                                                found.customer

                                            createCustomer =
                                                false
                                        },

                                        enabled = !locked,

                                        modifier =
                                            Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text =
                                                    found.customer.name,

                                                fontWeight =
                                                    FontWeight.SemiBold
                                            )

                                            Text(
                                                text =
                                                    found.customer
                                                        .canonicalPhone
                                            )
                                        }
                                    }
                                }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            customer = null
                            createCustomer = true

                            if (
                                searchByPhone &&
                                searchPhone.rawNumber
                                    .isNotBlank()
                            ) {
                                customerPhone =
                                    searchPhone
                            }

                            if (
                                !searchByPhone &&
                                searchName.isNotBlank()
                            ) {
                                customerName =
                                    searchName.trim()
                            }
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        enabled = !locked
                    ) {
                        Text("Create New Customer")
                    }
                }

                if (createCustomer) {
                    Text(
                        text = "New Customer",
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = customerName,

                        onValueChange = {
                            customerName = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        enabled = !locked,

                        singleLine = true,

                        label = {
                            Text("Customer Name")
                        }
                    )

                    PhoneNumberInput(
                        value = customerPhone,

                        onValueChange = {
                            customerPhone = it
                        },

                        enabled = !locked,

                        showValidation =
                            customerName.isNotBlank() &&
                                customerPhone.rawNumber
                                    .isNotBlank() &&
                                customerPhoneNormalized == null
                    )

                    TextButton(
                        onClick = {
                            createCustomer = false
                            searched = false
                        },

                        enabled = !locked
                    ) {
                        Text("Find Existing Customer")
                    }
                }

                customer?.let { selected ->
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(16.dp),

                        color =
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                                .copy(alpha = 0.25f)
                    ) {
                        Column(
                            modifier =
                                Modifier.padding(12.dp),

                            verticalArrangement =
                                Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = selected.name,
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                text =
                                    selected.canonicalPhone
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            customer = null
                            searched = false
                        },

                        enabled = !locked
                    ) {
                        Text("Change Customer")
                    }
                }

                Text(
                    text =
                        "No payment is collected now. The amount becomes the customer's Credit obligation."
                )

            }
        },

        confirmButton = {
            Button(
                onClick = {
                    viewModel.confirmCreditSale(
                        customer = customer,

                        name =
                            customer?.name
                                ?: customerName.trim(),

                        phone =
                            customer?.canonicalPhone
                                ?: customerPhoneNormalized
                                    .orEmpty()
                    )
                },

                enabled = canConfirm
            ) {
                Text(
                    if (state.isExecuting) {
                        "Saving..."
                    } else {
                        "Confirm Credit Sale"
                    }
                )
            }
        },

        dismissButton = {
            TextButton(
                onClick =
                    viewModel::closeCreditSale,

                enabled = !locked
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CouponSaleDialog(
    viewModel: RegisterViewModel,
    state: RegisterUiState,
    decimalPlaces: Int,
    startMode: CouponDialogStartMode
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var useCoupon by rememberSaveable(startMode) {
        mutableStateOf(startMode.startsWithRedemption)
    }

    var selectedUserId by remember {
        mutableStateOf<String?>(null)
    }

    // Phone is the primary cashier lookup in the compact flow.
    var searchByPhone by rememberSaveable {
        mutableStateOf(true)
    }

    var query by remember {
        mutableStateOf("")
    }

    var phoneSearch by remember {
        mutableStateOf(
            PhoneNumberInputValue(
                viewModel.merchantCountryCode
            )
        )
    }

    var customer by remember {
        mutableStateOf<
            com.dailymoneyregister.core.database.entity.CustomerEntity?
        >(null)
    }

    var name by remember {
        mutableStateOf("")
    }

    var phone by remember {
        mutableStateOf(
            PhoneNumberInputValue(
                viewModel.merchantCountryCode
            )
        )
    }

    var amount by remember {
        mutableStateOf("")
    }

    var extras by remember {
        mutableStateOf("")
    }

    var newAuthorizedUserName by remember {
        mutableStateOf("")
    }

    var searched by rememberSaveable {
        mutableStateOf(false)
    }

    var createCustomer by rememberSaveable {
        mutableStateOf(false)
    }

    var funding by remember {
        mutableStateOf(
            com.dailymoneyregister.core.model.enums.PaymentCategory.CASH
        )
    }

    val locked =
        state.isExecuting ||
        state.inFlightCouponSaleCommand != null ||
        state.inFlightCouponTopUpCommand != null ||
        state.inFlightCouponRedemptionCommand != null

    val result = state.couponSaleResult
    val topUpResult = state.couponTopUpResult
    val redemptionResult = state.couponRedemptionResult
    val pending = state.inFlightCouponSaleCommand

    val parsed =
        com.dailymoneyregister.core.model.money.MoneyMinorUnits
            .parse(
                amount,
                decimalPlaces
            )
            .getOrNull()

    val selectedCustomerWallet =
        customer?.let { selected ->

            state.couponCustomers.firstOrNull {
                it.customer.id == selected.id
            }
        }

    val selectedWallet =
        selectedCustomerWallet?.wallet

    val selectedWalletBalanceMinor =
        selectedCustomerWallet?.balanceMinor ?: 0L

    val phoneForSearch =
        remember(phoneSearch) {

            runCatching {
                phoneSearch.normalizedNumber()
            }.getOrNull()
        }

    val phoneForCreate =
        remember(phone) {

            runCatching {
                phone.normalizedNumber()
            }.getOrNull()
        }

    val walletDetails =
        state.couponWalletDetails?.takeIf {
            it.wallet.customerId == customer?.id
        }

    val activeUsers =
        walletDetails
            ?.authorizedUsers
            ?.filter { it.isActive }
            .orEmpty()

    val additionalUsers =
        extras
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    val isExistingWallet =
        selectedWallet?.isActive == true

    val confirmEnabled =
        !state.isExecuting &&
        (
            if (useCoupon) {

                selectedUserId != null &&
                    !state.couponWalletLoading &&
                    parsed?.isPositive == true &&
                    parsed.amount <=
                        state.draftState.grandTotal.amount &&
                    parsed.amount <=
                        (walletDetails?.balanceMinor ?: 0L)

            } else {

                (customer != null || createCustomer) &&
                    name.isNotBlank() &&
                    (customer != null || phoneForCreate != null) &&
                    parsed?.isPositive == true
            }
        ) &&
        (
            (pending?.fundingCategory ?: funding) !=
                com.dailymoneyregister.core.model.enums
                    .PaymentCategory.ONLINE ||
                state.onlineQr != null
        )

    // BOARDLOCK_COUPON_VISUAL_REBUILD_R5B
    AlertDialog(
        onDismissRequest =
            viewModel::closeCouponSale,

        title = {

            Text(
                stringResource(
                    startMode.dialogTitleRes
                ),
                fontWeight = FontWeight.Bold
            )
        },

        text = {

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 540.dp)
                        .verticalScroll(
                            rememberScrollState()
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                state.errorMessage?.let {

                    Text(
                        it.asString(),
                        color =
                            MaterialTheme.colorScheme.error
                    )
                }

                // ====================================================
                // COMPLETED REDEMPTION
                // ====================================================

                if (redemptionResult != null) {

                    androidx.compose.material3.Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        shape =
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(
                                    18.dp
                                ),
                        color =
                            MaterialTheme.colorScheme
                                .primaryContainer
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(6.dp)
                        ) {

                            Text(
                                stringResource(
                                    R.string.coupon_purchase_saved
                                ),
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                redemptionResult
                                    .transaction
                                    .displayRef
                            )

                            Text(
                                stringResource(
                                    R.string.coupon_redemption_receipt,
                                    redemptionResult
                                        .authorizedUserName,
                                    com.dailymoneyregister
                                        .core.model.money
                                        .MoneyMinorUnits(
                                            redemptionResult.amountMinor
                                        )
                                        .formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencyCode
                                        ),
                                    com.dailymoneyregister
                                        .core.model.money
                                        .MoneyMinorUnits(
                                            redemptionResult
                                                .balanceAfterMinor
                                        )
                                        .formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencyCode
                                        )
                                )
                            )

                            customer?.let { owner ->

                                Button(
                                    onClick = {

                                        val timestamp =
                                            java.time.Instant
                                                .ofEpochMilli(
                                                    redemptionResult
                                                        .transaction
                                                        .createdAt
                                                )
                                                .atZone(
                                                    java.time.ZoneId.of(
                                                        viewModel
                                                            .activeBranch
                                                            ?.timezoneId
                                                            ?: "Asia/Muscat"
                                                    )
                                                )
                                                .format(
                                                    java.time.format
                                                        .DateTimeFormatter
                                                        .ofPattern(
                                                            "dd MMM yyyy, h:mm a"
                                                        )
                                                )

                                        openCouponWhatsApp(
                                            context,
                                            owner.canonicalPhone,
                                            context.getString(
                                                R.string
                                                    .coupon_whatsapp_redemption,
                                                owner.name,
                                                viewModel
                                                    .activeBusiness
                                                    ?.name
                                                    .orEmpty(),
                                                com.dailymoneyregister
                                                    .core.model.money
                                                    .MoneyMinorUnits(
                                                        redemptionResult
                                                            .amountMinor
                                                    )
                                                    .formatDisplay(
                                                        decimalPlaces,
                                                        viewModel
                                                            .currencyCode
                                                    ),
                                                timestamp,
                                                redemptionResult
                                                    .authorizedUserName,
                                                com.dailymoneyregister
                                                    .core.model.money
                                                    .MoneyMinorUnits(
                                                        redemptionResult
                                                            .balanceAfterMinor
                                                    )
                                                    .formatDisplay(
                                                        decimalPlaces,
                                                        viewModel
                                                            .currencyCode
                                                    )
                                            )
                                        )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                ) {

                                    Text(
                                        stringResource(
                                            R.string
                                                .coupon_send_receipt
                                        )
                                    )
                                }
                            }
                        }
                    }

                // ====================================================
                // COMPLETED SELL / TOP-UP
                // ====================================================

                } else if (
                    result != null ||
                    topUpResult != null
                ) {

                    androidx.compose.material3.Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        shape =
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(
                                    18.dp
                                ),
                        color =
                            MaterialTheme.colorScheme
                                .primaryContainer
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(6.dp)
                        ) {

                            Text(
                                stringResource(
                                    R.string.coupon_purchase_saved
                                ),
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                result
                                    ?.transaction
                                    ?.displayRef
                                    ?: topUpResult!!
                                        .transaction
                                        .displayRef
                            )

                            Text(
                                com.dailymoneyregister
                                    .core.model.money
                                    .MoneyMinorUnits(
                                        result
                                            ?.purchasedAmountMinor
                                            ?: topUpResult!!
                                                .amountMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        viewModel.currencySymbol
                                    ),
                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }

                } else {

                    // =================================================
                    // STEP 1 - CUSTOMER
                    // =================================================

                    androidx.compose.material3.Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        shape =
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(
                                    18.dp
                                ),
                        color =
                            MaterialTheme.colorScheme
                                .surfaceVariant
                                .copy(alpha = 0.35f)
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(14.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {

                                FilterChip(
                                    selected =
                                        searchByPhone,
                                    onClick = {
                                        searchByPhone = true
                                    },
                                    enabled = !locked,
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_search_phone
                                            )
                                        )
                                    }
                                )

                                FilterChip(
                                    selected =
                                        !searchByPhone,
                                    onClick = {
                                        searchByPhone = false
                                    },
                                    enabled = !locked,
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_search_name
                                            )
                                        )
                                    }
                                )
                            }

                            if (
                                customer == null &&
                                !createCustomer
                            ) {

                                if (searchByPhone) {

                                    PhoneNumberInput(
                                        value =
                                            phoneSearch,
                                        onValueChange = {
                                            phoneSearch = it
                                        },
                                        enabled = !locked,
                                        showValidation =
                                            searched &&
                                                phoneForSearch == null
                                    )

                                } else {

                                    OutlinedTextField(
                                        value = query,
                                        onValueChange = {
                                            query = it
                                        },
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        enabled = !locked,
                                        singleLine = true,
                                        label = {

                                            Text(
                                                stringResource(
                                                    R.string
                                                        .coupon_purchase_search
                                                )
                                            )
                                        }
                                    )
                                }

                                Button(
                                    onClick = {

                                        searched = true
                                        customer = null
                                        createCustomer = false

                                        val searchValue =
                                            if (searchByPhone) {

                                                phoneForSearch
                                                    ?: phoneSearch
                                                        .rawNumber
                                                        .trim()

                                            } else {

                                                query.trim()
                                            }

                                        viewModel
                                            .searchCouponCustomers(
                                                searchValue
                                            )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                            !state.couponSearching &&
                                            (
                                                (
                                                    searchByPhone &&
                                                        phoneSearch
                                                            .rawNumber
                                                            .isNotBlank()
                                                ) ||
                                                    (
                                                        !searchByPhone &&
                                                            query
                                                                .isNotBlank()
                                                    )
                                                )
                                ) {

                                    Text(
                                        stringResource(
                                            R.string
                                                .coupon_purchase_search_button
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // =================================================
                    // SEARCH RESULTS
                    // =================================================

                    if (
                        customer == null &&
                        !createCustomer
                    ) {

                        state.couponCustomers
                            .forEach { found ->

                                val walletText =
                                    when {

                                        found.wallet == null ->

                                            stringResource(
                                                R.string
                                                    .coupon_wallet_not_found,
                                                viewModel.currencyCode
                                            )

                                        !found.wallet.isActive ->

                                            stringResource(
                                                R.string
                                                    .coupon_wallet_inactive
                                            )

                                        else ->

                                            stringResource(
                                                R.string
                                                    .coupon_wallet_balance,
                                                com.dailymoneyregister
                                                    .core.model.money
                                                    .MoneyMinorUnits(
                                                        found.balanceMinor
                                                    )
                                                    .formatDisplay(
                                                        decimalPlaces,
                                                        viewModel
                                                            .currencySymbol
                                                    )
                                            )
                                    }

                                OutlinedButton(
                                    onClick = {

                                        customer =
                                            found.customer

                                        name =
                                            found.customer.name

                                        phone =
                                            PhoneNumberInputValue(
                                                viewModel
                                                    .merchantCountryCode,
                                                found.customer
                                                    .canonicalPhone
                                            )

                                        createCustomer = false
                                        selectedUserId = null

                                        if (useCoupon) {

                                            amount =
                                                java.math.BigDecimal
                                                    .valueOf(
                                                        minOf(
                                                            found.balanceMinor,
                                                            state
                                                                .draftState
                                                                .grandTotal
                                                                .amount
                                                        ),
                                                        decimalPlaces
                                                    )
                                                    .toPlainString()
                                        }

                                        viewModel
                                            .openCouponWallet(
                                                found.customer.id,
                                                false
                                            )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled = !locked
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                    ) {

                                        Text(
                                            found.customer.name,
                                            fontWeight =
                                                FontWeight.Bold
                                        )

                                        Text(
                                            found.customer
                                                .canonicalPhone
                                        )

                                        Text(
                                            walletText,
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall
                                        )
                                    }
                                }
                            }

                        if (
                            searched &&
                            !state.couponSearching &&
                            state.couponCustomers
                                .isEmpty() &&
                            !useCoupon
                        ) {

                            OutlinedButton(
                                onClick = {

                                    createCustomer = true

                                    if (
                                        phoneForSearch != null
                                    ) {
                                        phone = phoneSearch
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                                enabled = !locked
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_create_customer
                                    )
                                )
                            }
                        }
                    }

                    // =================================================
                    // CREATE NEW CUSTOMER
                    // =================================================

                    if (createCustomer) {

                        androidx.compose.material3.Surface(
                            modifier =
                                Modifier.fillMaxWidth(),
                            shape =
                                androidx.compose.foundation.shape
                                    .RoundedCornerShape(
                                        18.dp
                                    ),
                            color =
                                MaterialTheme.colorScheme
                                    .primaryContainer
                                    .copy(alpha = 0.25f)
                        ) {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_create_customer
                                    ),
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                OutlinedTextField(
                                    value = name,
                                    onValueChange = {
                                        name = it
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled = !locked,
                                    singleLine = true,
                                    label = {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_purchase_name
                                            )
                                        )
                                    }
                                )

                                PhoneNumberInput(
                                    value = phone,
                                    onValueChange = {
                                        phone = it
                                    },
                                    enabled = !locked,
                                    showValidation =
                                        name.isNotBlank() &&
                                            phone.rawNumber
                                                .isNotBlank() &&
                                            phoneForCreate == null
                                )
                            }
                        }
                    }

                    // =================================================
                    // SELECTED CUSTOMER SUMMARY
                    // =================================================

                    customer?.let { selectedCustomer ->

                        androidx.compose.material3.Surface(
                            modifier =
                                Modifier.fillMaxWidth(),
                            shape =
                                androidx.compose.foundation.shape
                                    .RoundedCornerShape(
                                        18.dp
                                    ),
                            color =
                                MaterialTheme.colorScheme
                                    .primaryContainer
                                    .copy(alpha = 0.30f)
                        ) {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                Text(
                                    selectedCustomer.name,
                                    fontWeight =
                                        FontWeight.Bold,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .primary
                                )

                                Text(
                                    selectedCustomer
                                        .canonicalPhone
                                )

                                if (isExistingWallet) {

                                    Text(
                                        stringResource(
                                            R.string
                                                .coupon_wallet_balance,
                                            com.dailymoneyregister
                                                .core.model.money
                                                .MoneyMinorUnits(
                                                    selectedWalletBalanceMinor
                                                )
                                                .formatDisplay(
                                                    decimalPlaces,
                                                    viewModel
                                                        .currencySymbol
                                                )
                                        ),
                                        fontWeight =
                                            FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // WhatsApp immediately after customer card.
                        if (isExistingWallet) {

                            Button(
                                onClick = {

                                    val message =
                                        context.getString(
                                            R.string
                                                .coupon_whatsapp_balance_message,
                                            selectedCustomer.name,
                                            viewModel
                                                .activeBusiness
                                                ?.name
                                                .orEmpty(),
                                            com.dailymoneyregister
                                                .core.model.money
                                                .MoneyMinorUnits(
                                                    selectedWalletBalanceMinor
                                                )
                                                .formatDisplay(
                                                    decimalPlaces,
                                                    viewModel
                                                        .currencyCode
                                                )
                                        )

                                    openCouponWhatsApp(
                                        context,
                                        selectedCustomer
                                            .canonicalPhone,
                                        message
                                    )
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                                enabled = !locked,
                                colors =
                                    androidx.compose.material3
                                        .ButtonDefaults
                                        .buttonColors(
                                            containerColor =
                                                androidx.compose.ui
                                                    .graphics
                                                    .Color(
                                                        0xFF25D366
                                                    ),
                                            contentColor =
                                                androidx.compose.ui
                                                    .graphics
                                                    .Color.White
                                        )
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_share_updated_balance
                                    )
                                )
                            }
                        }

                        TextButton(
                            onClick = {

                                customer = null
                                selectedUserId = null
                                name = ""
                                amount = ""

                                phone =
                                    PhoneNumberInputValue(
                                        viewModel
                                            .merchantCountryCode
                                    )
                            },
                            enabled = !locked
                        ) {

                            Text(
                                stringResource(
                                    R.string
                                        .coupon_change_customer
                                )
                            )
                        }
                    }

                    // =================================================
                    // VALUE + WALLET ACTIONS
                    // =================================================

                    if (
                        customer != null ||
                        createCustomer
                    ) {

                        OutlinedTextField(
                            value = amount,
                            onValueChange = {
                                amount = it
                            },
                            modifier =
                                Modifier.fillMaxWidth(),
                            enabled = !locked,
                            singleLine = true,
                            label = {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_purchase_amount,
                                        viewModel.currencyCode
                                    )
                                )
                            }
                        )

                        // =============================================
                        // USE COUPON
                        // =============================================

                        if (useCoupon) {

                            if (
                                state.couponWalletLoading
                            ) {

                                Text(
                                    stringResource(
                                        R.string.coupon_loading
                                    )
                                )
                            }

                            if (
                                activeUsers.isNotEmpty()
                            ) {

                                androidx.compose.material3.Surface(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    shape =
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(
                                                18.dp
                                            ),
                                    color =
                                        MaterialTheme.colorScheme
                                            .surfaceVariant
                                            .copy(alpha = 0.35f)
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.padding(14.dp),
                                        verticalArrangement =
                                            Arrangement.spacedBy(
                                                8.dp
                                            )
                                    ) {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_purchase_users
                                            ),
                                            fontWeight =
                                                FontWeight.Bold
                                        )

                                        activeUsers
                                            .forEach { authUser ->

                                                FilterChip(
                                                    selected =
                                                        selectedUserId ==
                                                            authUser
                                                                .user
                                                                .id,
                                                    onClick = {

                                                        selectedUserId =
                                                            authUser
                                                                .user
                                                                .id
                                                    },
                                                    enabled =
                                                        !locked,
                                                    label = {

                                                        Text(
                                                            authUser.name
                                                        )
                                                    }
                                                )
                                            }
                                    }
                                }
                            }

                            val remainderMinor =
                                (
                                    state.draftState
                                        .grandTotal.amount -
                                        (
                                            parsed?.amount
                                                ?: 0L
                                            )
                                    )
                                    .coerceAtLeast(0L)

                            Text(
                                stringResource(
                                    R.string
                                        .coupon_remainder_amount,
                                    com.dailymoneyregister
                                        .core.model.money
                                        .MoneyMinorUnits(
                                            remainderMinor
                                        )
                                        .formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencyCode
                                        )
                                ),
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            // Existing supported UX:
                            // cashier can add balance without leaving dialog.
                            Button(
                                onClick = {
                                    useCoupon = false
                                    amount = ""
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                                enabled = !locked,
                                colors =
                                    androidx.compose.material3
                                        .ButtonDefaults
                                        .buttonColors(
                                            containerColor =
                                                MaterialTheme
                                                    .colorScheme
                                                    .primary,
                                            contentColor =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onPrimary
                                        )
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_top_up_action
                                    )
                                )
                            }

                        // =============================================
                        // SELL / TOP-UP
                        // =============================================

                        } else {

                            if (isExistingWallet) {

                                if (
                                    activeUsers.isNotEmpty()
                                ) {

                                    androidx.compose.material3.Surface(
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        shape =
                                            androidx.compose.foundation.shape
                                                .RoundedCornerShape(
                                                    18.dp
                                                ),
                                        color =
                                            MaterialTheme
                                                .colorScheme
                                                .surfaceVariant
                                                .copy(
                                                    alpha = 0.35f
                                                )
                                    ) {

                                        Column(
                                            modifier =
                                                Modifier.padding(
                                                    14.dp
                                                ),
                                            verticalArrangement =
                                                Arrangement.spacedBy(
                                                    6.dp
                                                )
                                        ) {

                                            Text(
                                                stringResource(
                                                    R.string
                                                        .coupon_purchase_users
                                                ),
                                                fontWeight =
                                                    FontWeight.Bold
                                            )

                                            activeUsers
                                                .forEach {
                                                        authUser ->

                                                    Text(
                                                        buildString {

                                                            append(
                                                                authUser.name
                                                            )

                                                            if (
                                                                authUser
                                                                    .user
                                                                    .isPurchaser
                                                            ) {
                                                                append(
                                                                    " ✓"
                                                                )
                                                            }
                                                        }
                                                    )
                                                }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value =
                                        newAuthorizedUserName,
                                    onValueChange = {
                                        newAuthorizedUserName =
                                            it
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                            activeUsers.size < 21,
                                    singleLine = true,
                                    label = {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_authorized_user_name
                                            )
                                        )
                                    }
                                )

                                Button(
                                    onClick = {

                                        val clean =
                                            newAuthorizedUserName
                                                .trim()

                                        if (
                                            clean.isNotEmpty() &&
                                            activeUsers.none {
                                                it.name.equals(
                                                    clean,
                                                    ignoreCase =
                                                        true
                                                )
                                            }
                                        ) {

                                            viewModel
                                                .saveCouponUser(
                                                    userId =
                                                        null,
                                                    name =
                                                        clean
                                                )

                                            newAuthorizedUserName =
                                                ""
                                        }
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                            newAuthorizedUserName
                                                .trim()
                                                .isNotEmpty() &&
                                            activeUsers.size < 21
                                ) {

                                    Text(
                                        stringResource(
                                            R.string
                                                .coupon_add_user
                                        )
                                    )
                                }

                            } else {

                                androidx.compose.material3.Surface(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    shape =
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(
                                                18.dp
                                            ),
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .surfaceVariant
                                            .copy(
                                                alpha = 0.35f
                                            )
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.padding(
                                                14.dp
                                            ),
                                        verticalArrangement =
                                            Arrangement.spacedBy(
                                                8.dp
                                            )
                                    ) {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_purchase_users
                                            ),
                                            fontWeight =
                                                FontWeight.Bold
                                        )

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_purchase_purchaser_included
                                            )
                                        )

                                        additionalUsers
                                            .forEachIndexed {
                                                    index,
                                                    userName ->

                                                Row(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth(),
                                                    horizontalArrangement =
                                                        Arrangement
                                                            .SpaceBetween,
                                                    verticalAlignment =
                                                        Alignment
                                                            .CenterVertically
                                                ) {

                                                    Text(
                                                        userName
                                                    )

                                                    TextButton(
                                                        onClick = {

                                                            extras =
                                                                additionalUsers
                                                                    .filterIndexed {
                                                                            i,
                                                                            _ ->
                                                                        i !=
                                                                            index
                                                                    }
                                                                    .joinToString(
                                                                        separator =
                                                                            "`n"
                                                                    )
                                                        },
                                                        enabled =
                                                            !locked
                                                    ) {

                                                        Text(
                                                            stringResource(
                                                                R.string
                                                                    .coupon_deactivate_user
                                                            )
                                                        )
                                                    }
                                                }
                                            }

                                        OutlinedTextField(
                                            value =
                                                newAuthorizedUserName,
                                            onValueChange = {
                                                newAuthorizedUserName =
                                                    it
                                            },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth(),
                                            enabled =
                                                !locked &&
                                                    additionalUsers
                                                        .size < 20,
                                            singleLine = true,
                                            label = {

                                                Text(
                                                    stringResource(
                                                        R.string
                                                            .coupon_authorized_user_name
                                                    )
                                                )
                                            }
                                        )

                                        Button(
                                            onClick = {

                                                val clean =
                                                    newAuthorizedUserName
                                                        .trim()

                                                if (
                                                    clean.isNotEmpty() &&
                                                    clean !in
                                                        additionalUsers &&
                                                    additionalUsers
                                                        .size < 20
                                                ) {

                                                    extras =
                                                        (
                                                            additionalUsers +
                                                                clean
                                                            )
                                                            .joinToString(
                                                                separator =
                                                                    "`n"
                                                            )

                                                    newAuthorizedUserName =
                                                        ""
                                                }
                                            },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth(),
                                            enabled =
                                                !locked &&
                                                    newAuthorizedUserName
                                                        .trim()
                                                        .isNotEmpty() &&
                                                    additionalUsers
                                                        .size < 20
                                        ) {

                                            Text(
                                                stringResource(
                                                    R.string
                                                        .coupon_add_user
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // =============================================
                        // PAYMENT / REMAINDER METHOD
                        // =============================================

                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {

                            (
                                listOf(
                                    com.dailymoneyregister
                                        .core.model.enums
                                        .PaymentCategory.CASH,
                                    com.dailymoneyregister
                                        .core.model.enums
                                        .PaymentCategory.ONLINE
                                ) +
                                    if (useCoupon) {

                                        listOf(
                                            com.dailymoneyregister
                                                .core.model.enums
                                                .PaymentCategory.CREDIT
                                        )

                                    } else {

                                        emptyList()
                                    }
                                )
                                .forEach { category ->

                                    FilterChip(
                                        selected =
                                            funding == category,
                                        onClick = {
                                            funding = category
                                        },
                                        enabled = !locked,
                                        label = {

                                            Text(
                                                stringResource(
                                                    if (
                                                        category ==
                                                        com.dailymoneyregister
                                                            .core.model.enums
                                                            .PaymentCategory.CASH
                                                    ) {
                                                        R.string.cash
                                                    } else if (
                                                        category ==
                                                        com.dailymoneyregister
                                                            .core.model.enums
                                                            .PaymentCategory.ONLINE
                                                    ) {
                                                        R.string.online
                                                    } else {
                                                        R.string.credit
                                                    }
                                                )
                                            )
                                        }
                                    )
                                }
                        }

                        if (
                            (pending?.fundingCategory
                                ?: funding) ==
                            com.dailymoneyregister
                                .core.model.enums
                                .PaymentCategory.ONLINE
                        ) {

                            if (
                                state.onlineQr == null
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .online_qr_not_configured
                                    )
                                )
                            }

                            state.onlineQr?.let { qr ->

                                MerchantQrImage(
                                    qr.media.localReference,
                                    stringResource(
                                        R.string
                                            .merchant_qr_preview
                                    ),
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(
                                            max = 180.dp
                                        )
                                )

                                Text(
                                    stringResource(
                                        R.string
                                            .online_payment_instruction
                                    )
                                )
                            }
                        }

                        if (pending != null) {

                            Text(
                                stringResource(
                                    R.string
                                        .coupon_purchase_retry_hint
                                )
                            )
                        }
                    }
                }
            }
        },

        confirmButton = {

            if (
                result != null ||
                topUpResult != null ||
                redemptionResult != null
            ) {

                TextButton(
                    onClick =
                        viewModel::closeCouponSale
                ) {

                    Text(
                        stringResource(
                            R.string
                                .coupon_purchase_done
                        )
                    )
                }

            } else {

                Button(
                    onClick = {

                        if (useCoupon) {

                            viewModel
                                .confirmCouponRedemption(
                                    customer,
                                    selectedUserId,
                                    amount,
                                    funding
                                )

                        } else if (
                            customer != null &&
                            selectedWallet
                                ?.isActive == true
                        ) {

                            viewModel
                                .confirmCouponTopUp(
                                    customer!!,
                                    selectedWallet.id,
                                    amount,
                                    pending
                                        ?.fundingCategory
                                        ?: funding
                                )

                        } else {

                            viewModel
                                .confirmCouponSale(
                                    customer,
                                    name,
                                    phoneForCreate
                                        ?: phone.rawNumber,
                                    amount,
                                    pending
                                        ?.fundingCategory
                                        ?: funding,
                                    additionalUsers
                                )
                        }
                    },
                    enabled =
                        confirmEnabled
                ) {

                    Text(
                        stringResource(
                            if (
                                pending != null
                            ) {
                                R.string
                                    .coupon_purchase_retry
                            } else {
                                startMode.confirmLabelRes
                            }
                        )
                    )
                }
            }
        },

        dismissButton = {

            if (
                result == null &&
                topUpResult == null &&
                redemptionResult == null
            ) {

                TextButton(
                    onClick =
                        viewModel::closeCouponSale,
                    enabled = !locked
                ) {

                    Text(
                        stringResource(
                            R.string.cancel
                        )
                    )
                }
            }
        }
    )
}

@Composable
private fun CouponWalletDialog(viewModel: RegisterViewModel, state: RegisterUiState, decimalPlaces: Int) {
    val details = state.couponWalletDetails
    val context = androidx.compose.ui.platform.LocalContext.current
    var editingId by remember { mutableStateOf<String?>(null) }
    var userName by remember { mutableStateOf("") }
    val locked = state.isExecuting || state.inFlightCouponUserCommand != null
    AlertDialog(
        onDismissRequest = viewModel::closeCouponWallet,
        title = { Text("Coupon Wallet") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.errorMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.couponWalletLoading) {
                    Text("Loading...")
                } else if (details == null) {
                    Text("No coupon wallet found for this customer.")
                } else {
                    Text(
                        com.dailymoneyregister.core.model.money.MoneyMinorUnits(details.balanceMinor)
                            .formatDisplay(decimalPlaces, viewModel.currencySymbol),
                        fontWeight = FontWeight.Bold, fontSize = 20.sp
                    )
                    Text("Authorized Users", fontWeight = FontWeight.SemiBold)
                    details.authorizedUsers.forEach { authUser ->
                        val latestName = authUser.name
                        Text("• $latestName${if (authUser.user.isPurchaser) " (Purchaser)" else ""}")
                        if (authUser.isActive) {
                            Row {
                                TextButton(onClick = { editingId = authUser.user.id; userName = latestName }, enabled = !locked) {
                                    Text(stringResource(R.string.coupon_edit_user))
                                }
                                if (!authUser.user.isPurchaser) TextButton(
                                    onClick = { viewModel.saveCouponUser(authUser.user.id, latestName, true) }, enabled = !locked
                                ) { Text(stringResource(R.string.coupon_deactivate_user)) }
                            }
                        } else Text(stringResource(R.string.coupon_user_inactive))
                    }
                    OutlinedTextField(userName, { userName = it }, enabled = !locked, singleLine = true,
                        label = { Text(stringResource(R.string.coupon_authorized_user_name)) })
                    Button(onClick = { viewModel.saveCouponUser(editingId, userName) },
                        enabled = !state.isExecuting && (userName.isNotBlank() || state.inFlightCouponUserCommand != null)) {
                        Text(stringResource(if (state.inFlightCouponUserCommand != null) R.string.coupon_purchase_retry
                            else if (editingId == null) R.string.coupon_add_user else R.string.coupon_save_user))
                    }
                    if (editingId != null) TextButton(onClick = { editingId = null; userName = "" }, enabled = !locked) {
                        Text(stringResource(R.string.cancel))
                    }
                    details.customer?.let { customer ->
                        Button(onClick = {
                            val message = context.getString(R.string.coupon_whatsapp_balance_message, customer.name,
                                viewModel.activeBusiness?.name.orEmpty(),
                                com.dailymoneyregister.core.model.money.MoneyMinorUnits(details.balanceMinor)
                                    .formatDisplay(decimalPlaces, viewModel.currencyCode))
                            openCouponWhatsApp(context, customer.canonicalPhone, message)
                        }, enabled = !locked) { Text(stringResource(R.string.coupon_share_updated_balance)) }
                    }
                    Text("Transaction History", fontWeight = FontWeight.SemiBold)
                    details.ledgerEvents.forEach { event ->
                        Text("${event.type}: " + com.dailymoneyregister.core.model.money.MoneyMinorUnits(event.amountSignedMinor)
                            .formatDisplay(decimalPlaces, viewModel.currencySymbol))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::closeCouponWallet) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Opens a customer-reviewed draft; sending remains an explicit action in WhatsApp. */
private fun openCouponWhatsApp(context: android.content.Context, phone: String, message: String) {
    if (!Regex("\\+[1-9][0-9]{6,14}").matches(phone)) {
        android.widget.Toast.makeText(context, R.string.coupon_whatsapp_invalid_phone, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = android.net.Uri.parse("https://wa.me/${phone.drop(1)}?text=${android.net.Uri.encode(message)}")
    val launched = listOf("com.whatsapp", "com.whatsapp.w4b", null).any { packageName ->
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .setPackage(packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: android.content.ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }
    if (!launched) android.widget.Toast.makeText(context, R.string.coupon_whatsapp_open_failed, android.widget.Toast.LENGTH_SHORT).show()
}

