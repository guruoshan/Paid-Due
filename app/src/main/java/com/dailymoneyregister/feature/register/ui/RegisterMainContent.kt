package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.ui.asString

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.theme.RegisterColors
import com.dailymoneyregister.feature.register.ui.components.*
import com.dailymoneyregister.feature.invoice.ui.InvoiceViewModel
import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import com.dailymoneyregister.feature.register.receipt.ReceiptReadModel
import com.dailymoneyregister.feature.register.receipt.ReceiptSharing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
internal fun RegisterMainContentHost(
    state: RegisterUiState,
    recentTxs: List<com.dailymoneyregister.core.model.financial.RecentFinancialActivity>,
    activeBusiness: com.dailymoneyregister.core.database.entity.BusinessEntity?,
    activeBranch: com.dailymoneyregister.core.database.entity.BranchEntity?,
    activeUser: com.dailymoneyregister.core.database.entity.UserAccountEntity?,
    currencyCode: String,
    currencySymbol: String,
    merchantCountryCode: String,
    decimalPlaces: Int,
    currency: com.dailymoneyregister.core.model.currency.CurrencyConfig,
    sizing: RegisterResponsiveSizing,
    canCorrectAmount: Boolean,
    canRefund: Boolean,
    canVoid: Boolean,
    canChangePaymentMethod: Boolean,
    viewModel: RegisterViewModel,
    invoiceViewModel: InvoiceViewModel,
    historyState: HistoryUiState,
    historyViewModel: HistoryViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToNotifications: () -> Unit = {}
) {
    val context = LocalContext.current
    val couponPaymentAction = couponPaymentActionUi(state)

    Scaffold(
        topBar = {
            if (state.destination == RegisterDestination.Register) Surface(
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
                        text = stringResource(R.string.app_name),
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
                        onClick = onNavigateToNotifications,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = stringResource(R.string.audit_notifications),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onBackground
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
            // BOARDLOCK C2 adaptive register panes.
            //
            // These composable slots are the ONE presentation implementation
            // for both single-column and two-pane layouts.
            //
            // No financial callback is duplicated between orientations.
            val registerEntryContent: @Composable () -> Unit = {

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
                    currencyCode = currencyCode,
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
            }

            val registerActionContent: @Composable () -> Unit = {
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
                        viewModel.clearSaleCustomer()
                        viewModel.openOnlinePayment()
                    },
                    onCash = {
                        viewModel.clearSaleCustomer()
                        viewModel.openCashCustomer()
                    },
                    buttonHeight = sizing.actionHeight,
                    couponEnabled = couponPaymentAction.enabled,
                    couponLabelRes = couponPaymentAction.labelRes
                )
                RegisterSecondaryActionBar(
                    onHistory = {
                        viewModel.openHistory()
                        historyViewModel.loadHistory()
                    },
                    onCustomer = onNavigateToCustomers,
                    onClear = { viewModel.openDayClosing() },
                    onShare = viewModel::openExpenses,
                    buttonHeight = sizing.actionHeight
                )
            }

            val registerRecentContent: @Composable () -> Unit = {
RecentTransactionsPanel(
                    transactions = recentTxs,
                    isExpanded = state.isRecentTransactionsExpanded,
                    currency = currency,
                    timezoneId = activeBranch?.timezoneId ?: "Asia/Muscat",
                    onToggleExpanded = { viewModel.toggleRecentTransactionsExpanded() },
                    onTransactionSelected = viewModel::openTransactionDetails,
                    onExpenseSelected = viewModel::openExpenseDetails,
                    maxListHeight = sizing.recentMaxHeight,
                    compactMaxListHeight = sizing.compactRecentMaxHeight
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (
                sizing.layoutMode ==
                RegisterLayoutMode.TWO_PANE
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        ),
                    horizontalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.47f)
                            .fillMaxHeight()
                            .verticalScroll(
                                rememberScrollState()
                            ),
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                        verticalArrangement =
                            Arrangement.spacedBy(
                                sizing.keypadSpacing
                            )
                    ) {
                        registerEntryContent()
                        registerRecentContent()
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.53f)
                            .fillMaxHeight()
                            .verticalScroll(
                                rememberScrollState()
                            ),
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                        verticalArrangement =
                            Arrangement.spacedBy(
                                sizing.keypadSpacing
                            )
                    ) {
                        registerActionContent()
                    }
                }
            }
            else {
                Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                    registerEntryContent()
                    registerActionContent()
                    registerRecentContent()
                }
            }
    
            // In-place Cash Sale Success Overlay
            state.lastCompletedTransaction?.takeIf { state.couponRedemptionResult == null }?.let { completedTx ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    val successSharePhone =
                        state.creditSaleSharePhone
                            ?.takeIf { it.isNotBlank() }
                            ?: state.saleCustomerPhone.takeIf { it.isNotBlank() }
    
                    val successShareName =
                        state.creditSaleShareName
                            ?.takeIf { it.isNotBlank() }
                            ?: state.saleCustomerName.takeIf { it.isNotBlank() }
    
                    if (!successSharePhone.isNullOrBlank()) {
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
                                    text = stringResource(R.string.sale_saved),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RegisterColors.CashPrimary
                                )
    
                                Button(
onClick = {
                                        viewModel.loadSaleDetailsForWhatsApp(
                                            completedTx.id
                                        ) { details ->
                                            if (details == null) {
                                                Toast.makeText(
                                                    context,
                                                    "Saved sale details are unavailable.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@loadSaleDetailsForWhatsApp
                                            }

                                            val receipt = try {
                                                ReceiptReadModel.from(
                                                    details = details,
                                                    businessName = activeBusiness
                                                        ?.name
                                                        ?.trim()
                                                        ?.takeIf { it.isNotEmpty() }
                                                        ?: "Paid&Due",
                                                    customerName = successShareName,
                                                    customerPhone = successSharePhone
                                                )
                                            } catch (_: IllegalArgumentException) {
                                                Toast.makeText(
                                                    context,
                                                    "Receipt is unavailable for this record.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@loadSaleDetailsForWhatsApp
                                            }

                                            if (state.creditSaleSharePhone.isNullOrBlank()) {
                                                ReceiptSharing.shareWhatsApp(
                                                    context = context,
                                                    receipt = receipt
                                                )
                                                return@loadSaleDetailsForWhatsApp
                                            }

                                            val creditGivenMinor = state.creditInvoiceGivenMinor
                                            val purchaseOutstandingMinor =
                                                state.creditInvoicePurchaseOutstandingMinor
                                            val totalOutstandingMinor =
                                                state.creditInvoiceTotalOutstandingMinor

                                            if (
                                                creditGivenMinor == null ||
                                                purchaseOutstandingMinor == null ||
                                                totalOutstandingMinor == null
                                            ) {
                                                Toast.makeText(
                                                    context,
                                                    "Credit invoice details are unavailable.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@loadSaleDetailsForWhatsApp
                                            }

                                            val message =
                                                UniversalWhatsAppMessageComposer.compose(
                                                    UniversalWhatsAppMessageComposer.CreditSale(
                                                        party = UniversalWhatsAppMessageComposer.Party(
                                                            businessName = receipt.businessName,
                                                            customerName = receipt.customerName,
                                                            customerPhone = receipt.customerPhone
                                                            labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
                                                        ),
                                                        formattedCreditGiven =
                                                            receipt.formattedMoney(creditGivenMinor),
                                                        formattedPurchaseOutstanding =
                                                            receipt.formattedMoney(
                                                                purchaseOutstandingMinor
                                                            ),
                                                        formattedTotalOutstanding =
                                                            receipt.formattedMoney(totalOutstandingMinor),
                                                        reference = receipt.reference,
                                                        transactionDateTime =
                                                            receipt.formattedTransactionDateTime(),
                                                        note = receipt.note
                                                    )
                                                )

                                            ReceiptSharing.shareWhatsApp(
                                                context = context,
                                                receipt = receipt,
                                                messageOverride = message
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF25D366),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(stringResource(R.string.whatsapp))
                                }
    
                                TextButton(
                                    onClick = {
                                        viewModel.startNewSale()
                                    }
                                ) {
                                    Text(stringResource(R.string.done))
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
    
        RegisterOverlayHost(
            state = state,
            viewModel = viewModel,
            invoiceViewModel = invoiceViewModel,
            decimalPlaces = decimalPlaces,
            currency = currency,
            currencyCode = currencyCode,
            currencySymbol = currencySymbol,
            merchantCountryCode = merchantCountryCode,
            activeBusiness = activeBusiness,
            activeUser = activeUser,
            onNavigateToSettings = onNavigateToSettings
        )
    
        RegisterDestinationHost(
            state = state,
        businessName = activeBusiness?.name.orEmpty(),
            activeBranch = activeBranch,
            currency = currency,
            decimalPlaces = decimalPlaces,
            currencySymbol = currencySymbol,
            canCorrectAmount = canCorrectAmount,
            canRefund = canRefund,
            canVoid = canVoid,
            canChangePaymentMethod = canChangePaymentMethod,
            viewModel = viewModel,
            historyState = historyState,
            historyViewModel = historyViewModel
        )
    }
}
