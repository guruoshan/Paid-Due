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
import androidx.compose.runtime.*
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
import com.dailymoneyregister.feature.register.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    viewModel: RegisterViewModel,
    onNavigateToSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val recentTxs by viewModel.recentTransactions.collectAsState()
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
    val couponName = stringResource(R.string.payment_coupon_name)
    val creditName = stringResource(R.string.payment_credit_name)
    val onlineName = stringResource(R.string.payment_online_name)
    val historyName = stringResource(R.string.history)
    val customerName = stringResource(R.string.customer)
    val shareName = stringResource(R.string.share)

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
                    .fillMaxWidth()
                    .statusBarsPadding(),
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
                        modifier = Modifier.weight(1f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1
                    )

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

                // Compact optional material / description field
                CompactDescriptionField(
                    value = state.manualDescription,
                    onValueChange = { viewModel.onManualDescriptionChanged(it) },
                    placeholder = stringResource(R.string.note_optional)
                )

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
                        viewModel.triggerDisabledPaymentMethod(couponName)
                    },
                    onCredit = {
                        viewModel.triggerDisabledPaymentMethod(creditName)
                    },
                    onOnline = {
                        viewModel.triggerDisabledPaymentMethod(onlineName)
                    },
                    onCash = {
                        viewModel.executeCashSale()
                    },
                    buttonHeight = sizing.actionHeight
                )
                RegisterSecondaryActionBar(
                    onHistory = { viewModel.triggerDisabledPaymentMethod(historyName) },
                    onCustomer = { viewModel.triggerDisabledPaymentMethod(customerName) },
                    onClear = { viewModel.clearDraft() },
                    onShare = { viewModel.triggerDisabledPaymentMethod(shareName) },
                    buttonHeight = sizing.actionHeight
                )
                RecentTransactionsPanel(
                    transactions = recentTxs,
                    isExpanded = state.isRecentTransactionsExpanded,
                    currency = currency,
                    timezoneId = viewModel.activeBranch?.timezoneId ?: "Asia/Muscat",
                    onToggleExpanded = { viewModel.toggleRecentTransactionsExpanded() },
                    maxListHeight = sizing.recentMaxHeight,
                    compactMaxListHeight = sizing.compactRecentMaxHeight
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // In-place Cash Sale Success Overlay
            state.lastCompletedTransaction?.let { completedTx ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CashSaleSuccessOverlay(
                        transaction = completedTx,
                        onStartNewSale = { viewModel.startNewSale() },
                        onDisabledAction = { viewModel.triggerDisabledPaymentMethod(it) }
                    )
                }
            }
        }

        // Dialogs
        if (state.isItemizedBillDialogOpen) {
            ItemizedBillDialog(
                draftState = state.draftState,
                currency = currency,
                onDismiss = { viewModel.toggleItemizedBillDialog(false) },
                onOpenAddItem = { viewModel.toggleAddItemDialog(true) },
                onUpdateQuantity = { id, qty -> viewModel.updateItemQuantity(id, qty) },
                onRemoveItem = { id -> viewModel.removeItem(id) }
            )
        }

        if (state.isAddItemDialogOpen) {
            AddItemDialog(
                currency = currency,
                onDismiss = { viewModel.toggleAddItemDialog(false) },
                onAddItem = { name, priceText, qty, desc, packingText, shippingText ->
                    viewModel.addItem(name, priceText, qty, desc, packingText, shippingText)
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
    }
}




