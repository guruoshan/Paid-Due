package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.ui.asString

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.feature.register.ui.components.*
import com.dailymoneyregister.feature.customers.ui.PreparedWhatsAppMessage
import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import com.dailymoneyregister.feature.customers.ui.WhatsAppTransport

internal data class CreditSaleDialogActions(
    val onDismiss: () -> Unit,
    val onSearchCustomers: (String) -> Unit,
    val onConfirm: (
        com.dailymoneyregister.core.database.entity.CustomerEntity?,
        String,
        String
    ) -> Unit
)

@Composable
internal fun CreditSaleDialog(
    state: RegisterUiState,
    merchantCountryCode: String,
    actions: CreditSaleDialogActions
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
                merchantCountryCode
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
                merchantCountryCode
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
                actions.onDismiss()
            }
        },

        title = {
            Text(
                text = stringResource(R.string.credit_sale_label),
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
                        text = stringResource(R.string.find_customer),
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
                                Text(stringResource(R.string.phone))
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
                                Text(stringResource(R.string.name))
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
                                Text(stringResource(R.string.coupon_purchase_name))
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
                                actions.onSearchCustomers(searchValue)
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
                                stringResource(R.string.audit_no_customer_found)
                            )

                        } else {
                            Text(
                                text = stringResource(R.string.search_results),
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
                        Text(stringResource(R.string.create_new_customer))
                    }
                }

                if (createCustomer) {
                    Text(
                        text = stringResource(R.string.new_customer),
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
                            Text(stringResource(R.string.coupon_purchase_name))
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
                        Text(stringResource(R.string.find_existing_customer))
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
                        Text(stringResource(R.string.coupon_change_customer))
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
                    actions.onConfirm(
                        customer,
                            customer?.name
                                ?: customerName.trim(),
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
                    actions.onDismiss,

                enabled = !locked
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

internal data class CouponWalletDialogActions(
    val onDismiss: () -> Unit,
    val onSaveUser: (String?, String, Boolean) -> Unit
)

@Composable
internal fun CouponWalletDialog(
    state: RegisterUiState,
    decimalPlaces: Int,
    currencyCode: String,
    currencySymbol: String,
    businessName: String,
    actions: CouponWalletDialogActions
) {
    val details = state.couponWalletDetails
    val context = androidx.compose.ui.platform.LocalContext.current
    var editingId by remember { mutableStateOf<String?>(null) }
    var userName by remember { mutableStateOf("") }
    val locked = state.isExecuting || state.inFlightCouponUserCommand != null
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.coupon_wallet_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.errorMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.couponWalletLoading) {
                    Text(stringResource(R.string.coupon_loading))
                } else if (details == null) {
                    Text(stringResource(R.string.coupon_wallet_none_for_customer))
                } else {
                    Text(
                        com.dailymoneyregister.core.model.money.MoneyMinorUnits(details.balanceMinor)
                            .formatDisplay(decimalPlaces, currencySymbol),
                        fontWeight = FontWeight.Bold, fontSize = 20.sp
                    )
                    Text(stringResource(R.string.authorized_users_title), fontWeight = FontWeight.SemiBold)
                    details.authorizedUsers.forEach { authUser ->
                        val latestName = authUser.name
                        Row { Text("•"); Text("$latestName${if (authUser.user.isPurchaser) " (" + stringResource(R.string.coupon_purchaser_tag) + ")" else ""}") }
                        if (authUser.isActive) {
                            Row {
                                TextButton(onClick = { editingId = authUser.user.id; userName = latestName }, enabled = !locked) {
                                    Text(stringResource(R.string.coupon_edit_user))
                                }
                                if (!authUser.user.isPurchaser) TextButton(
                                    onClick = { actions.onSaveUser(authUser.user.id, latestName, true) }, enabled = !locked
                                ) { Text(stringResource(R.string.coupon_deactivate_user)) }
                            }
                        } else Text(stringResource(R.string.coupon_user_inactive))
                    }
                    OutlinedTextField(userName, { userName = it }, enabled = !locked, singleLine = true,
                        label = { Text(stringResource(R.string.coupon_authorized_user_name)) })
                    Button(onClick = { actions.onSaveUser(editingId, userName, false) },
                        enabled = !state.isExecuting && (userName.isNotBlank() || state.inFlightCouponUserCommand != null)) {
                        Text(stringResource(if (state.inFlightCouponUserCommand != null) R.string.coupon_purchase_retry
                            else if (editingId == null) R.string.coupon_add_user else R.string.coupon_save_user))
                    }
                    if (editingId != null) TextButton(onClick = { editingId = null; userName = "" }, enabled = !locked) {
                        Text(stringResource(R.string.cancel))
                    }
                    details.customer?.let { customer ->
                        Button(onClick = {
                            val message = UniversalWhatsAppMessageComposer.compose(
                                UniversalWhatsAppMessageComposer.CouponBalance(
                                    party = UniversalWhatsAppMessageComposer.Party(
                                        businessName = businessName,
                                        customerName = customer.name,
                                        customerPhone = customer.canonicalPhone
                                        labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
                                    ),
                                    formattedBalance =
                                        com.dailymoneyregister.core.model.money.MoneyMinorUnits(
                                            details.balanceMinor
                                        ).formatDisplay(decimalPlaces, currencyCode)
                                )
                            )
                            WhatsAppTransport.share(
                                context = context,
                                prepared = PreparedWhatsAppMessage(
                                    text = message,
                                    recipientPhone = customer.canonicalPhone
                                )
                            )
                        }, enabled = !locked) { Text(stringResource(R.string.coupon_share_updated_balance)) }
                    }
                    Text(stringResource(R.string.transaction_history), fontWeight = FontWeight.SemiBold)
                    details.ledgerEvents.forEach { event ->
                        Text("${event.type}: " + com.dailymoneyregister.core.model.money.MoneyMinorUnits(event.amountSignedMinor)
                            .formatDisplay(decimalPlaces, currencySymbol))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
