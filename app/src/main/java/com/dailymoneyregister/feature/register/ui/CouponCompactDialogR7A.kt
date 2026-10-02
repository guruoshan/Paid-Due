package com.dailymoneyregister.feature.register.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.core.ui.asString
import com.dailymoneyregister.feature.settings.ui.MerchantQrImage
import java.math.BigDecimal

/*
 * BOARDLOCK_COUPON_COMPACT_R7A
 *
 * PRESENTATION ONLY.
 *
 * Existing CouponSaleDialog remains untouched in RegisterScreen.kt.
 * Financial commands continue through RegisterViewModel.
 */
@Composable
internal fun CouponCompactDialogR7A(
    viewModel: RegisterViewModel,
    state: RegisterUiState,
    decimalPlaces: Int,
    startMode: CouponDialogStartMode
) {
    val context = LocalContext.current

    val refundMode =
        state.refundToCouponDraft != null

    var actionIsRedemption by rememberSaveable(startMode) {
        mutableStateOf(startMode.startsWithRedemption)
    }

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
        mutableStateOf<CustomerEntity?>(null)
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

    var amount by remember(
        state.refundToCouponDraft?.transactionId
    ) {
        mutableStateOf(
            state.refundToCouponDraft
                ?.amountInput
                .orEmpty()
        )
    }

    var funding by remember {
        mutableStateOf(PaymentCategory.CASH)
    }

    var selectedUserId by remember {
        mutableStateOf<String?>(null)
    }

    var newUserName by remember {
        mutableStateOf("")
    }

    var additionalUsersText by remember {
        mutableStateOf("")
    }

    val locked =
        state.isExecuting ||
        state.inFlightCouponSaleCommand != null ||
        state.inFlightCouponTopUpCommand != null ||
        state.inFlightCouponRedemptionCommand != null

    val pending =
        state.inFlightCouponSaleCommand

    val parsed =
        MoneyMinorUnits
            .parse(
                amount,
                decimalPlaces
            )
            .getOrNull()

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

    val selectedResult =
        customer?.let { selected ->
            state.couponCustomers.firstOrNull {
                it.customer.id == selected.id
            }
        }

    val selectedWallet =
        selectedResult?.wallet

    val selectedBalanceMinor =
        selectedResult?.balanceMinor ?: 0L

    val existingWallet =
        selectedWallet?.isActive == true

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
        additionalUsersText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    val remainderMinor =
        if (actionIsRedemption) {

            (
                state.draftState.grandTotal.amount -
                (parsed?.amount ?: 0L)
            ).coerceAtLeast(0L)

        } else {

            0L
        }

    val finished =
        state.couponSaleResult != null ||
        state.couponTopUpResult != null ||
        state.couponRedemptionResult != null ||
        state.refundToCouponResult != null

    val intentValid =
        when {

            refundMode -> {

                (customer != null || createCustomer) &&
                    customerName.isNotBlank() &&
                    (
                        customer != null ||
                            customerPhoneNormalized != null
                    ) &&
                    parsed?.isPositive == true &&
                    parsed.amount ==
                        state.refundToCouponDraft
                            ?.amountMinor
            }

            actionIsRedemption -> {

                customer != null &&
                    selectedUserId != null &&
                    !state.couponWalletLoading &&
                    parsed?.isPositive == true &&
                    parsed.amount <=
                        state.draftState.grandTotal.amount &&
                    parsed.amount <=
                        (walletDetails?.balanceMinor ?: 0L)
            }

            else -> {

                (customer != null || createCustomer) &&
                    customerName.isNotBlank() &&
                    (
                        customer != null ||
                            customerPhoneNormalized != null
                    ) &&
                    parsed?.isPositive == true
            }
        }

    val onlineReady =
        refundMode ||
            (
                (pending?.fundingCategory ?: funding) !=
                    PaymentCategory.ONLINE ||
                    state.onlineQr != null
                )

    val confirmEnabled =
        !locked &&
        intentValid &&
        onlineReady

    Dialog(
        onDismissRequest = {
            if (!locked && !finished) {
                viewModel.closeCouponSale()
            }
        },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false
            )
    ) {

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
            shape =
                RoundedCornerShape(24.dp),
            tonalElevation =
                8.dp
        ) {

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {

                // ====================================================
                // HEADER
                // ====================================================

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        text =
                            if (refundMode) {
                                "Refund to Coupon"
                            } else {
                                stringResource(
                                    when {

                                        actionIsRedemption ->
                                            R.string.coupon_use_action

                                        existingWallet ->
                                            R.string.coupon_top_up_action

                                        else ->
                                            R.string.coupon_purchase_title
                                    }
                                )
                            },
                        style =
                            MaterialTheme.typography.titleLarge,
                        fontWeight =
                            FontWeight.Bold
                    )

                    TextButton(
                        onClick =
                            viewModel::closeCouponSale,
                        enabled =
                            !locked
                    ) {

                        Text(
                            stringResource(
                                R.string.cancel
                            )
                        )
                    }
                }

                state.errorMessage?.let {

                    Text(
                        it.asString(),
                        color =
                            MaterialTheme.colorScheme.error
                    )
                }

                // ====================================================
                // SUCCESS
                // ====================================================

                if (finished) {

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        shape =
                            RoundedCornerShape(16.dp),
                        color =
                            MaterialTheme.colorScheme
                                .primaryContainer
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {

                            Text(
                                if (refundMode) {
                                    "Refund credited to Coupon"
                                } else {
                                    stringResource(
                                        R.string.coupon_purchase_saved
                                    )
                                },
                                fontWeight =
                                    FontWeight.Bold
                            )

                            state.refundToCouponResult
                                ?.let { result ->

                                    Text(
                                        result.refundEventId
                                    )

                                    Text(
                                        MoneyMinorUnits(
                                            result.refundAmountMinor
                                        ).formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencyCode
                                        ),
                                        fontWeight =
                                            FontWeight.Bold
                                    )

                                    result
                                        .couponBalanceAfterMinor
                                        ?.let { balance ->

                                            Text(
                                                "Coupon balance: " +
                                                    MoneyMinorUnits(
                                                        balance
                                                    ).formatDisplay(
                                                        decimalPlaces,
                                                        viewModel.currencyCode
                                                    )
                                            )
                                        }
                                }

                            state.couponRedemptionResult
                                ?.let { result ->

                                    Text(
                                        result.transaction.displayRef
                                    )

                                    Text(
                                        stringResource(
                                            R.string
                                                .coupon_redemption_receipt,
                                            result.authorizedUserName,
                                            MoneyMinorUnits(
                                                result.amountMinor
                                            ).formatDisplay(
                                                decimalPlaces,
                                                viewModel.currencyCode
                                            ),
                                            MoneyMinorUnits(
                                                result.balanceAfterMinor
                                            ).formatDisplay(
                                                decimalPlaces,
                                                viewModel.currencyCode
                                            )
                                        )
                                    )
                                }

                            state.couponSaleResult
                                ?.let { result ->

                                    Text(
                                        result.transaction.displayRef
                                    )

                                    Text(
                                        MoneyMinorUnits(
                                            result.purchasedAmountMinor
                                        ).formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencySymbol
                                        ),
                                        fontWeight =
                                            FontWeight.Bold
                                    )
                                }

                            state.couponTopUpResult
                                ?.let { result ->

                                    Text(
                                        result.transaction.displayRef
                                    )

                                    Text(
                                        MoneyMinorUnits(
                                            result.amountMinor
                                        ).formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencySymbol
                                        ),
                                        fontWeight =
                                            FontWeight.Bold
                                    )
                                }

                            // COUPON_SUCCESS_WHATSAPP_R7C
                            run {
                                val successCustomerName =
                                    customer?.name
                                        ?: customerName.ifBlank {
                                            "Customer"
                                        }

                                val successCustomerPhone =
                                    customer?.canonicalPhone
                                        ?: customerPhoneNormalized
                                        ?: customerPhone.rawNumber.trim()

                                val successReference =
                                    state.refundToCouponResult
                                        ?.refundEventId
                                        ?: state.couponRedemptionResult
                                            ?.transaction
                                            ?.displayRef
                                        ?: state.couponTopUpResult
                                            ?.transaction
                                            ?.displayRef
                                        ?: state.couponSaleResult
                                            ?.transaction
                                            ?.displayRef
                                        ?: ""

                                val successAmountMinor =
                                    state.refundToCouponResult
                                        ?.refundAmountMinor
                                        ?: state.couponRedemptionResult
                                            ?.amountMinor
                                        ?: state.couponTopUpResult
                                            ?.amountMinor
                                        ?: state.couponSaleResult
                                            ?.purchasedAmountMinor
                                        ?: 0L

                                val successPhoneValid =
                                    Regex(
                                        "\\+[1-9][0-9]{6,14}"
                                    ).matches(
                                        successCustomerPhone
                                    )

                                Button(
                                    onClick = {
                                        val successTitle =
                                            when {
                                                state.refundToCouponResult != null ->
                                                    "Refund credited to Coupon"

                                                state.couponRedemptionResult != null ->
                                                    "Coupon payment successful"

                                                state.couponTopUpResult != null ->
                                                    "Coupon top up successful"

                                                else ->
                                                    "Coupon purchase successful"
                                            }

                                        val message =
                                            buildString {
                                                viewModel.activeBusiness
                                                    ?.name
                                                    ?.takeIf {
                                                        it.isNotBlank()
                                                    }
                                                    ?.let {
                                                        append(it)
                                                        append("\n")
                                                    }

                                                append(successTitle)

                                                append("\nCustomer: ")
                                                append(
                                                    successCustomerName
                                                )

                                                append("\nAmount: ")
                                                append(
                                                    MoneyMinorUnits(
                                                        successAmountMinor
                                                    ).formatDisplay(
                                                        decimalPlaces,
                                                        viewModel.currencyCode
                                                    )
                                                )

                                                state
                                                    .refundToCouponResult
                                                    ?.couponBalanceAfterMinor
                                                    ?.let {
                                                        append(
                                                            "\nCoupon balance: "
                                                        )
                                                        append(
                                                            MoneyMinorUnits(
                                                                it
                                                            ).formatDisplay(
                                                                decimalPlaces,
                                                                viewModel.currencyCode
                                                            )
                                                        )
                                                    }

                                                state
                                                    .couponRedemptionResult
                                                    ?.let {
                                                        append(
                                                            "\nCoupon balance: "
                                                        )
                                                        append(
                                                            MoneyMinorUnits(
                                                                it.balanceAfterMinor
                                                            ).formatDisplay(
                                                                decimalPlaces,
                                                                viewModel.currencyCode
                                                            )
                                                        )
                                                    }

                                                if (
                                                    successReference
                                                        .isNotBlank()
                                                ) {
                                                    append("\nRef: ")
                                                    append(
                                                        successReference
                                                    )
                                                }
                                            }

                                        openCouponWhatsAppR7A(
                                            context,
                                            successCustomerPhone,
                                            message
                                        )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        successPhoneValid,
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor =
                                                Color(0xFF25D366),
                                            contentColor =
                                                Color.White
                                        )
                                ) {
                                    Text(
                                        "Send on WhatsApp"
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    // COUPON_REDEMPTION_DONE_R7C
                                    if (
                                        state.couponRedemptionResult != null
                                    ) {
                                        viewModel.startNewSale()
                                    } else {
                                        viewModel.closeCouponSale()
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                            ) {

                                Text(
                                    stringResource(
                                        R.string.coupon_purchase_done
                                    )
                                )
                            }
                        }
                    }

                } else {

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 620.dp)
                                .verticalScroll(
                                    rememberScrollState()
                                ),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {

                        // ================================================
                        // SEARCH ONLY UNTIL CUSTOMER IS CHOSEN
                        // ================================================

                        if (
                            customer == null &&
                            !createCustomer
                        ) {

                            Surface(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                shape =
                                    RoundedCornerShape(16.dp),
                                color =
                                    MaterialTheme.colorScheme
                                        .surfaceVariant
                                        .copy(alpha = 0.35f)
                            ) {

                                Column(
                                    modifier =
                                        Modifier.padding(12.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {

                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(6.dp)
                                    ) {

                                        FilterChip(
                                            selected =
                                                searchByPhone,
                                            onClick = {
                                                searchByPhone = true
                                            },
                                            enabled =
                                                !locked,
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
                                            enabled =
                                                !locked,
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

                                    if (searchByPhone) {

                                        PhoneNumberInput(
                                            value =
                                                searchPhone,
                                            onValueChange = {
                                                searchPhone = it
                                            },
                                            enabled =
                                                !locked,
                                            showValidation =
                                                searched &&
                                                searchPhoneNormalized ==
                                                    null
                                        )

                                    } else {

                                        OutlinedTextField(
                                            value =
                                                searchName,
                                            onValueChange = {
                                                searchName = it
                                            },
                                            modifier =
                                                Modifier.fillMaxWidth(),
                                            enabled =
                                                !locked,
                                            singleLine =
                                                true,
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

                                            searched =
                                                true

                                            val value =
                                                if (searchByPhone) {

                                                    searchPhoneNormalized
                                                        ?: searchPhone
                                                            .rawNumber
                                                            .trim()

                                                } else {

                                                    searchName.trim()
                                                }

                                            viewModel
                                                .searchCouponCustomers(
                                                    value
                                                )
                                        },
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        enabled =
                                            !locked &&
                                            !state.couponSearching &&
                                            (
                                                if (searchByPhone) {
                                                    searchPhone
                                                        .rawNumber
                                                        .isNotBlank()
                                                } else {
                                                    searchName
                                                        .isNotBlank()
                                                }
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

                            // ============================================
                            // RESULTS
                            // ============================================

                            state.couponCustomers
                                .forEach { found ->

                                    OutlinedButton(
                                        onClick = {

                                            customer =
                                                found.customer

                                            customerName =
                                                found.customer.name

                                            customerPhone =
                                                PhoneNumberInputValue(
                                                    viewModel
                                                        .merchantCountryCode,
                                                    found.customer
                                                        .canonicalPhone
                                                )

                                            selectedUserId =
                                                null

                                            if (actionIsRedemption) {

                                                amount =
                                                    BigDecimal
                                                        .valueOf(
                                                            minOf(
                                                                found
                                                                    .balanceMinor,
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
                                        enabled =
                                            !locked
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
                                                    .canonicalPhone,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall
                                            )

                                            if (
                                                found.wallet
                                                    ?.isActive == true
                                            ) {

                                                Text(
                                                    stringResource(
                                                        R.string
                                                            .coupon_wallet_balance,
                                                        MoneyMinorUnits(
                                                            found
                                                                .balanceMinor
                                                        ).formatDisplay(
                                                            decimalPlaces,
                                                            viewModel
                                                                .currencySymbol
                                                        )
                                                    ),
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .bodySmall
                                                )
                                            }
                                        }
                                    }
                                }

                            if (
                                searched &&
                                !state.couponSearching &&
                                state.couponCustomers
                                    .isEmpty() &&
                                !actionIsRedemption
                            ) {

                                OutlinedButton(
                                    onClick = {

                                        createCustomer =
                                            true

                                        if (
                                            searchPhoneNormalized !=
                                            null
                                        ) {

                                            customerPhone =
                                                searchPhone
                                        }
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked
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

                        // ================================================
                        // NEW CUSTOMER
                        // ================================================

                        if (createCustomer) {

                            Surface(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                shape =
                                    RoundedCornerShape(16.dp),
                                color =
                                    MaterialTheme.colorScheme
                                        .primaryContainer
                                        .copy(alpha = 0.25f)
                            ) {

                                Column(
                                    modifier =
                                        Modifier.padding(12.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
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
                                        value =
                                            customerName,
                                        onValueChange = {
                                            customerName = it
                                        },
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        enabled =
                                            !locked,
                                        singleLine =
                                            true,
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
                                        value =
                                            customerPhone,
                                        onValueChange = {
                                            customerPhone = it
                                        },
                                        enabled =
                                            !locked,
                                        showValidation =
                                            customerName.isNotBlank() &&
                                            customerPhone
                                                .rawNumber
                                                .isNotBlank() &&
                                            customerPhoneNormalized ==
                                                null
                                    )
                                }
                            }
                        }

                        // ================================================
                        // SELECTED CUSTOMER
                        // ================================================

                        customer?.let { selected ->

                            Surface(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                shape =
                                    RoundedCornerShape(16.dp),
                                color =
                                    MaterialTheme.colorScheme
                                        .primaryContainer
                                        .copy(alpha = 0.30f)
                            ) {

                                Row(
                                    modifier =
                                        Modifier.padding(12.dp),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.weight(1f)
                                    ) {

                                        Text(
                                            selected.name,
                                            fontWeight =
                                                FontWeight.Bold
                                        )

                                        Text(
                                            selected.canonicalPhone,
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall
                                        )

                                        if (existingWallet) {

                                            Text(
                                                stringResource(
                                                    R.string
                                                        .coupon_wallet_balance,
                                                    MoneyMinorUnits(
                                                        selectedBalanceMinor
                                                    ).formatDisplay(
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

                                    TextButton(
                                        onClick = {

                                            customer =
                                                null

                                            selectedUserId =
                                                null

                                            customerName =
                                                ""

                                            amount =
                                                if (refundMode) {
                                                    state
                                                        .refundToCouponDraft
                                                        ?.amountInput
                                                        .orEmpty()
                                                } else {
                                                    ""
                                                }

                                            actionIsRedemption =
                                                startMode
                                                    .startsWithRedemption

                                            viewModel
                                                .closeCouponWallet()
                                        },
                                        enabled =
                                            !locked
                                    ) {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_change_customer
                                            )
                                        )
                                    }
                                }
                            }

                            // ============================================
                            // WHATSAPP
                            // ============================================

                            if (existingWallet) {

                                Button(
                                    onClick = {

                                        val message =
                                            context.getString(
                                                R.string
                                                    .coupon_whatsapp_balance_message,
                                                selected.name,
                                                viewModel
                                                    .activeBusiness
                                                    ?.name
                                                    .orEmpty(),
                                                MoneyMinorUnits(
                                                    selectedBalanceMinor
                                                ).formatDisplay(
                                                    decimalPlaces,
                                                    viewModel.currencyCode
                                                )
                                            )

                                        openCouponWhatsAppR7A(
                                            context,
                                            selected.canonicalPhone,
                                            message
                                        )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked,
                                    colors =
                                        ButtonDefaults
                                            .buttonColors(
                                                containerColor =
                                                    Color(
                                                        0xFF25D366
                                                    ),
                                                contentColor =
                                                    Color.White
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
                        }

                        // ================================================
                        // MONEY CONTROLS
                        // ================================================

                        if (
                            customer != null ||
                            createCustomer
                        ) {

                            OutlinedTextField(
                                value =
                                    amount,
                                onValueChange = {
                                    if (!refundMode) {
                                        amount = it
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                                enabled =
                                    !locked &&
                                    !refundMode,
                                singleLine =
                                    true,
                                label = {

                                    Text(
                                        if (refundMode) {
                                            "Refund amount (${viewModel.currencyCode})"
                                        } else {
                                            stringResource(
                                                R.string
                                                    .coupon_purchase_amount,
                                                viewModel.currencyCode
                                            )
                                        }
                                    )
                                }
                            )

                            // ============================================
                            // AUTHORIZED USERS
                            // ============================================

                            if (
                                existingWallet &&
                                activeUsers.isNotEmpty()
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_purchase_users
                                    ),
                                    fontWeight =
                                        FontWeight.SemiBold
                                )

                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(
                                                rememberScrollState()
                                            ),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {

                                    activeUsers
                                        .forEach { authUser ->

                                            FilterChip(
                                                selected =
                                                    selectedUserId ==
                                                    authUser.user.id,
                                                onClick = {
                                                    selectedUserId =
                                                        authUser.user.id
                                                },
                                                enabled =
                                                    !locked,
                                                label = {

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
                                            )
                                        }
                                }
                            }

                            // ============================================
                            // EXISTING WALLET ADD USER
                            // ============================================

                            if (
                                existingWallet &&
                                !actionIsRedemption
                            ) {

                                OutlinedTextField(
                                    value =
                                        newUserName,
                                    onValueChange = {
                                        newUserName = it
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                        activeUsers.size < 21,
                                    singleLine =
                                        true,
                                    label = {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_authorized_user_name
                                            )
                                        )
                                    }
                                )

                                OutlinedButton(
                                    onClick = {

                                        val clean =
                                            newUserName.trim()

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
                                                    userId = null,
                                                    name = clean
                                                )

                                            newUserName =
                                                ""
                                        }
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                        newUserName
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
                            }

                            // ============================================
                            // NEW WALLET AUTHORIZED USERS
                            // ============================================

                            if (
                                !existingWallet &&
                                !actionIsRedemption
                            ) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_purchase_purchaser_included
                                    ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )

                                additionalUsers
                                    .forEachIndexed {
                                        index,
                                        userName ->

                                        Row(
                                            modifier =
                                                Modifier.fillMaxWidth(),
                                            verticalAlignment =
                                                Alignment.CenterVertically
                                        ) {

                                            Text(
                                                userName,
                                                modifier =
                                                    Modifier.weight(1f)
                                            )

                                            TextButton(
                                                onClick = {

                                                    additionalUsersText =
                                                        additionalUsers
                                                            .filterIndexed {
                                                                    i,
                                                                    _ ->

                                                                i != index
                                                            }
                                                            .joinToString(
                                                                "\n"
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
                                        newUserName,
                                    onValueChange = {
                                        newUserName = it
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                        additionalUsers.size < 20,
                                    singleLine =
                                        true,
                                    label = {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_authorized_user_name
                                            )
                                        )
                                    }
                                )

                                OutlinedButton(
                                    onClick = {

                                        val clean =
                                            newUserName.trim()

                                        if (
                                            clean.isNotEmpty() &&
                                            clean !in additionalUsers &&
                                            additionalUsers.size < 20
                                        ) {

                                            additionalUsersText =
                                                (
                                                    additionalUsers +
                                                    clean
                                                )
                                                    .joinToString(
                                                        "\n"
                                                    )

                                            newUserName =
                                                ""
                                        }
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    enabled =
                                        !locked &&
                                        newUserName
                                            .trim()
                                            .isNotEmpty() &&
                                        additionalUsers.size < 20
                                ) {

                                    Text(
                                        stringResource(
                                            R.string
                                                .coupon_add_user
                                        )
                                    )
                                }
                            }

                            // ============================================
                            // REDEMPTION BALANCE + TOP UP
                            // ============================================

                            if (actionIsRedemption) {

                                Text(
                                    stringResource(
                                        R.string
                                            .coupon_remainder_amount,
                                        MoneyMinorUnits(
                                            remainderMinor
                                        ).formatDisplay(
                                            decimalPlaces,
                                            viewModel.currencyCode
                                        )
                                    ),
                                    fontWeight =
                                        FontWeight.SemiBold
                                )

                                if (
                                    !refundMode &&
                                    existingWallet &&
                                    selectedBalanceMinor <
                                    state.draftState
                                        .grandTotal.amount
                                ) {

                                    OutlinedButton(
                                        onClick = {

                                            actionIsRedemption =
                                                false

                                            amount =
                                                ""

                                            funding =
                                                PaymentCategory.CASH
                                        },
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        enabled =
                                            !locked
                                    ) {

                                        Text(
                                            stringResource(
                                                R.string
                                                    .coupon_top_up_action
                                            )
                                        )
                                    }
                                }
                            }

                            // ============================================
                            // FUNDING
                            // ============================================

                            if (
                                !refundMode &&
                                (
                                    !actionIsRedemption ||
                                    remainderMinor > 0L
                                )
                            ) {

                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(
                                                rememberScrollState()
                                            ),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {

                                    val options =
                                        if (actionIsRedemption) {

                                            listOf(
                                                PaymentCategory.CASH,
                                                PaymentCategory.ONLINE,
                                                PaymentCategory.CREDIT
                                            )

                                        } else {

                                            listOf(
                                                PaymentCategory.CASH,
                                                PaymentCategory.ONLINE
                                            )
                                        }

                                    options
                                        .forEach { category ->

                                            FilterChip(
                                                selected =
                                                    funding ==
                                                    category,
                                                onClick = {
                                                    funding =
                                                        category
                                                },
                                                enabled =
                                                    !locked,
                                                label = {

                                                    Text(
                                                        stringResource(
                                                            when (
                                                                category
                                                            ) {

                                                                PaymentCategory.CASH ->
                                                                    R.string.cash

                                                                PaymentCategory.ONLINE ->
                                                                    R.string.online

                                                                else ->
                                                                    R.string.credit
                                                            }
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                }
                            }

                            // ============================================
                            // QR
                            // ============================================

                            if (
                                !refundMode &&
                                (
                                    pending?.fundingCategory
                                        ?: funding
                                ) ==
                                PaymentCategory.ONLINE
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

                                state.onlineQr
                                    ?.let { qr ->

                                        MerchantQrImage(
                                            qr.media
                                                .localReference,
                                            stringResource(
                                                R.string
                                                    .merchant_qr_preview
                                            ),
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(
                                                    max = 150.dp
                                                )
                                        )
                                    }
                            }

                            // ============================================
                            // ONE MAIN ACTION
                            // ============================================

                            Button(
                                onClick = {

                                    if (refundMode) {

                                        viewModel
                                            .confirmRefundToCoupon(
                                                customer =
                                                    customer,
                                                name =
                                                    customerName,
                                                phone =
                                                    customerPhoneNormalized
                                                        ?: customerPhone
                                                            .rawNumber,
                                                additionalUsers =
                                                    additionalUsers
                                            )

                                    } else if (
                                        actionIsRedemption
                                    ) {

                                        viewModel
                                            .confirmCouponRedemption(
                                                customer,
                                                selectedUserId,
                                                amount,
                                                funding
                                            )

                                    } else if (
                                        customer != null &&
                                        existingWallet
                                    ) {

                                        viewModel
                                            .confirmCouponTopUp(
                                                customer!!,
                                                selectedWallet!!.id,
                                                amount,
                                                pending
                                                    ?.fundingCategory
                                                    ?: funding
                                            )

                                    } else {

                                        viewModel
                                            .confirmCouponSale(
                                                customer,
                                                customerName,
                                                customerPhoneNormalized
                                                    ?: customerPhone
                                                        .rawNumber,
                                                amount,
                                                pending
                                                    ?.fundingCategory
                                                    ?: funding,
                                                additionalUsers
                                            )
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                                enabled =
                                    confirmEnabled
                            ) {

                                Text(
                                    if (refundMode) {
                                        "Refund to Coupon"
                                    } else {
                                        stringResource(
                                            when {

                                                pending != null ->
                                                    R.string
                                                        .coupon_purchase_retry

                                                actionIsRedemption ->
                                                    R.string
                                                        .coupon_use_action

                                                existingWallet ->
                                                    R.string
                                                        .coupon_top_up_action

                                                else ->
                                                    R.string
                                                        .coupon_purchase_confirm
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/*
 * R7A local WhatsApp helper.
 * Existing RegisterScreen helper remains untouched.
 */
private fun openCouponWhatsAppR7A(
    context: Context,
    phone: String,
    message: String
) {

    if (
        !Regex("\\+[1-9][0-9]{6,14}")
            .matches(phone)
    ) {

        Toast.makeText(
            context,
            R.string.coupon_whatsapp_invalid_phone,
            Toast.LENGTH_SHORT
        ).show()

        return
    }

    val uri =
        Uri.parse(
            "https://wa.me/${phone.drop(1)}?text=${
                Uri.encode(message)
            }"
        )

    val launched =
        listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            null
        ).any { packageName ->

            try {

                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    )
                        .setPackage(packageName)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                )

                true

            } catch (
                _: ActivityNotFoundException
            ) {

                false

            } catch (
                _: SecurityException
            ) {

                false
            }
        }

    if (!launched) {

        Toast.makeText(
            context,
            R.string.coupon_whatsapp_open_failed,
            Toast.LENGTH_SHORT
        ).show()
    }
}