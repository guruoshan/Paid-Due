package com.dailymoneyregister.feature.customers.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dailymoneyregister.feature.register.receipt.ReceiptSharing
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.asString
import com.dailymoneyregister.feature.customers.data.CustomerCreditHistoryKind
import com.dailymoneyregister.feature.customers.data.CustomerInvoiceRow
import com.dailymoneyregister.feature.register.data.RegisterRepository

private data class CustomerPaymentDisplayRow(
    val occurredAt: Long,
    val title: String,
    val detail: String,
    val amountMinor: Long,
    val currencyCode: String,
    val isMoneyOut: Boolean,
    val reference: String?
)

data class CustomerDetailScreenActions(
    val onNavigateBack: () -> Unit,
    val onLoad: () -> Unit,
    val onOpenReceivePayment: () -> Unit,
    val onOpenInvoice: (CustomerInvoiceRow) -> Unit,
    val onRepaymentAmountChange: (String) -> Unit,
    val onSelectRepaymentMethod: (PaymentCategory) -> Unit,
    val onConfirmReceivePayment: () -> Unit,
    val onCloseReceivePayment: () -> Unit,
    val onCloseInvoice: () -> Unit,
    val onSharePdf: (android.content.Context) -> Unit,
    val onShareWhatsApp: (android.content.Context) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailScreen(
    state: CustomerDetailUiState,
    actions: CustomerDetailScreenActions
) {
    val customer = state.customer
    val context = LocalContext.current

    /*
     * BOARDLOCK_CUSTOMER_V1_TAB_STATE
     *
     * Presentation-only state.
     * No financial/customer truth is stored here.
     */
    var selectedTab by remember { mutableIntStateOf(0) }

    /*
     * CUSTOMER_WHATSAPP_V1
     *
     * Presentation-only communication state.
     * No financial truth is stored here.
     */
    var whatsappChoice by remember { mutableStateOf<String?>(null) }
    var whatsappPreview by remember { mutableStateOf<String?>(null) }

    fun messageParty(): CustomerWhatsAppMessageComposer.Party? {
        val currentCustomer = customer ?: return null
        val businessName = state.businessName?.takeIf { it.isNotBlank() } ?: return null
        val phone = currentCustomer.canonicalPhone
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return CustomerWhatsAppMessageComposer.Party(
            businessName = businessName,
            customerName = currentCustomer.name,
            customerPhone = phone
            labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
        )
    }

    val customerTabs =
        listOf(
            "Transactions",
            "Coupons",
            "Credit",
            stringResource(R.string.audit_customer_documents), "Payments"
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            customer?.name ?: stringResource(R.string.customers_title),
                            fontWeight = FontWeight.SemiBold
                        )
                        customer?.canonicalPhone
                            ?.takeIf { it.isNotBlank() }
                            ?.let { phone ->
                                Text(
                                    phone,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                state.error != null -> {
                    Text(state.error!!.asString(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = actions.onLoad) {
                        Text(stringResource(R.string.retry))
                    }
                }

                state.notFound || customer == null -> {
                    Text(stringResource(R.string.customers_not_found))
                }

                else -> {
                    /*
                     * BOARDLOCK_CUSTOMER_V1
                     *
                     * One customer identity.
                     * Four role-oriented views over authoritative data.
                     *
                     * No financial values are independently persisted here.
                     */

                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        customerTabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = {
                                    selectedTab = index
                                },
                                text = {
                                    Text(
                                        text = title,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }

                    when (selectedTab) {
                        /*
                         * TRANSACTIONS
                         *
                         * Read-only presentation of authoritative,
                         * lifecycle-resolved customer financial history.
                         *
                         * Presentation intentionally follows the Coupon
                         * Recent Activity layout.
                         *
                         * Transaction IDs/references remain authoritative
                         * backend evidence but are not displayed here.
                         */
                        0 -> {
                            val transactions = state.transactions

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.audit_customer_transactions_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                )

                                Text(
                                    stringResource(R.string.audit_customer_recent_activity),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )

                                if (transactions.isEmpty()) {
                                    Text(
                                        stringResource(R.string.audit_customer_no_transactions),
                                        fontSize = 14.sp,
                                        color =
                                            MaterialTheme
                                                .colorScheme
                                                .onSurfaceVariant
                                    )
                                } else {
                                    transactions.forEach { financial ->

                                        /*
                                         * Human-readable title only.
                                         *
                                         * Payment category comes from the
                                         * resolved financial view.
                                         * No financial state is inferred
                                         * from transaction IDs or invoices.
                                         */
                                        val transactionTitle =
                                            when (financial.financialKind) {
                                                com.dailymoneyregister.core.model.financial.FinancialTransactionKind.COUPON_SALE ->
                                                    "Coupon Sale"
                                                com.dailymoneyregister.core.model.financial.FinancialTransactionKind.COUPON_TOP_UP ->
                                                    "Coupon Top Up"
                                                com.dailymoneyregister.core.model.financial.FinancialTransactionKind.MERCHANDISE_SALE ->
                                                    when (financial.currentPaymentCategory) {
                                                        PaymentCategory.CASH -> "Cash Sale"
                                                        PaymentCategory.ONLINE -> "Online Sale"
                                                        PaymentCategory.CREDIT -> "Credit Sale"
                                                        PaymentCategory.COUPON -> "Coupon Sale"
                                                        null -> financial.description?.takeIf { it.isNotBlank() } ?: "Sale"
                                                    }
                                            }

                                        val lifecycleLabel =
                                            when {
                                                financial.isVoided ->
                                                    "Voided"

                                                financial.isFullyRefunded ->
                                                    "Fully refunded"

                                                financial.isPartiallyRefunded ->
                                                    "Partially refunded"

                                                financial.hasAmountCorrection ->
                                                    "Amount corrected"

                                                financial.currentPaymentCategory !=
                                                    financial.originalPaymentCategory ||
                                                    financial.currentPaymentChannel !=
                                                    financial.originalPaymentChannel ->
                                                    "Payment method changed"

                                                else ->
                                                    null
                                            }

                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp),
                                            horizontalArrangement =
                                                Arrangement.SpaceBetween,
                                            verticalAlignment =
                                                Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement =
                                                    Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    transactionTitle,
                                                    fontWeight =
                                                        FontWeight.SemiBold,
                                                    fontSize = 14.sp
                                                )

                                                lifecycleLabel?.let { label ->
                                                    Text(
                                                        label,
                                                        fontSize = 12.sp,
                                                        color =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .onSurfaceVariant
                                                    )
                                                }

                                                Text(
                                                    formatCustomerDateTime(
                                                        financial.latestActivityAt
                                                    ),
                                                    fontSize = 12.sp,
                                                    color =
                                                        MaterialTheme
                                                            .colorScheme
                                                            .onSurfaceVariant
                                                )
                                            }

                                            Text(
                                                text =
                                                    formatCustomerMoney(
                                                        financial.netAmount.amount,
                                                        financial
                                                            .transaction
                                                            .currencyDecimalPlaces,
                                                        financial
                                                            .transaction
                                                            .currencyCode
                                                    ),
                                                fontWeight =
                                                    FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }



                        /*
                         * COUPONS
                         *
                         * Read-only projection of the authoritative Coupon
                         * wallet and Coupon ledger.
                         *
                         * No balance is calculated or persisted in Compose.
                         */
                        1 -> {
                            val coupon = state.couponWalletDetails

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.audit_customer_coupons_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (coupon == null) {
                                    Text(
                                        stringResource(R.string.audit_customer_no_coupon_wallet),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 18.sp
                                    )

                                    Text(
                                        stringResource(R.string.audit_customer_no_coupon_activity),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    val couponCurrency =
                                        coupon.ledgerEvents
                                            .lastOrNull()
                                            ?.currencyCode
                                            ?: state.creditCurrencyCode
                                            ?: ""

                                    Text(
                                        stringResource(R.string.audit_coupon_balance),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        formatCustomerMoney(
                                            coupon.balanceMinor,
                                            state.creditDecimalPlaces,
                                            couponCurrency
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 28.sp
                                    )

                                    Button(
                                        onClick = {
                                            whatsappChoice = "COUPON"
                                        },
                                        enabled =
                                            customer.canonicalPhone
                                                ?.isNotBlank() == true &&
                                                !state.businessName.isNullOrBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF25D366),
                                                contentColor = Color.White
                                            )
                                    ) {
                                        Text(stringResource(R.string.whatsapp))
                                    }

                                    if (coupon.ledgerEvents.isEmpty()) {
                                        Text(
                                            stringResource(R.string.audit_customer_no_coupon_history),
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            stringResource(R.string.audit_customer_recent_activity),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )

                                        coupon.ledgerEvents
                                            .asReversed()
                                            .take(5)
                                            .forEach { event ->

                                                val eventTitle =
                                                    when (coupon.activityKinds[event.id]) {
                                                        RegisterRepository.CouponWalletActivityKind.SALE -> "Coupon Sale"
                                                        RegisterRepository.CouponWalletActivityKind.TOP_UP -> "Coupon Top Up"
                                                        RegisterRepository.CouponWalletActivityKind.BONUS -> "Promotion Bonus"
                                                        RegisterRepository.CouponWalletActivityKind.REDEMPTION -> "Coupon Used"
                                                        RegisterRepository.CouponWalletActivityKind.REFUND_TO_COUPON -> "Refund to Coupon"
                                                        null -> "Coupon Activity"
                                                    }

                                                Row(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp),
                                                    horizontalArrangement =
                                                        Arrangement.SpaceBetween,
                                                    verticalAlignment =
                                                        Alignment.CenterVertically
                                                ) {
                                                    Column(
                                                        modifier =
                                                            Modifier.weight(1f),
                                                        verticalArrangement =
                                                            Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Text(
                                                            eventTitle,
                                                            fontWeight =
                                                                FontWeight.SemiBold,
                                                            fontSize = 14.sp
                                                        )

                                                        Text(
                                                            formatCustomerDateTime(
                                                                event.occurredAt
                                                            ),
                                                            fontSize = 12.sp,
                                                            color =
                                                                MaterialTheme
                                                                    .colorScheme
                                                                    .onSurfaceVariant
                                                        )
                                                    }

                                                    Text(
                                                        text =
                                                            (
                                                                if (
                                                                    event.amountSignedMinor >
                                                                        0L
                                                                ) {
                                                                    "+"
                                                                } else {
                                                                    ""
                                                                }
                                                            ) +
                                                                formatCustomerMoney(
                                                                    event.amountSignedMinor,
                                                                    state.creditDecimalPlaces,
                                                                    event.currencyCode
                                                                ),
                                                        fontWeight =
                                                            FontWeight.SemiBold,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }

                                        if (coupon.ledgerEvents.size > 5) {
                                            Text(
                                                "${coupon.ledgerEvents.size} total Coupon activities",
                                                fontSize = 12.sp,
                                                color =
                                                    MaterialTheme
                                                        .colorScheme
                                                        .onSurfaceVariant
                                            )
                                        }
                                    }

                                    val activeUsers =
                                        coupon.authorizedUsers
                                            .filter { it.isActive }
                                            .map { it.name }
                                            .filter { it.isNotBlank() }

                                    if (activeUsers.isNotEmpty()) {
                                        Text(
                                            stringResource(R.string.audit_customer_authorized_users),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )

                                        Text(
                                            activeUsers.joinToString(", "),
                                            fontSize = 13.sp,
                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        /*
                         * CREDIT
                         *
                         * creditOutstandingMinor is supplied by the existing
                         * authoritative Credit read path in the ViewModel.
                         */
                        2 -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                    Text(
                                        stringResource(R.string.audit_customer_amount_due),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )

                                    Text(
                                        formatCustomerMoney(
                                            state.creditOutstandingMinor,
                                            state.creditDecimalPlaces,
                                            state.creditCurrencyCode ?: ""
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp,
                                        color =
                                            if (
                                                state.creditOutstandingMinor >
                                                    0L
                                            ) {
                                                MaterialTheme
                                                    .colorScheme
                                                    .error
                                            } else {
                                                MaterialTheme
                                                    .colorScheme
                                                    .primary
                                            }
                                    )
                                    if (state.creditOutstandingMinor > 0L) {

                                    Button(
                                        onClick =
                                            actions.onOpenReceivePayment,
                                        enabled =
                                            state.creditOutstandingMinor >
                                                0L &&
                                                !state
                                                    .isSubmittingRepayment,
                                        modifier =
                                            Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.receive_payment))
                                    }
                                    }

                                    Button(
                                        onClick = {
                                            whatsappChoice = "CREDIT"
                                        },
                                        enabled =
                                            customer.canonicalPhone
                                                ?.isNotBlank() == true &&
                                                !state.businessName.isNullOrBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF25D366),
                                                contentColor = Color.White
                                            )
                                    ) {
                                        Text(stringResource(R.string.whatsapp))
                                    }

                                    state.repaymentSuccess
                                        ?.let { message ->
                                            Text(
                                                message,
                                                color =
                                                    MaterialTheme
                                                        .colorScheme
                                                        .primary,
                                                fontSize = 14.sp
                                            )
                                        }

                                    if (
                                        state.creditOutstandingMinor ==
                                            0L
                                    ) {
                                        Text(
                                            stringResource(R.string.audit_customer_no_outstanding_credit),
                                            fontSize = 14.sp,
                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        stringResource(R.string.audit_customer_credit_history),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        modifier = Modifier.padding(top = 14.dp)
                                    )

                                    if (state.creditHistory.isEmpty()) {
                                        Text(
                                            stringResource(R.string.audit_customer_no_credit_activity),
                                            fontSize = 14.sp,
                                            color =
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        state.creditHistory.forEach { row ->

                                            val title =
                                                when (row.kind.name) {
                                                    "OBLIGATION" -> "Credit Sale"

                                                    "REPAYMENT" -> {
                                                        val method =
                                                            row.repaymentMethodName
                                                                ?.lowercase()
                                                                ?.replaceFirstChar {
                                                                    it.uppercase()
                                                                }

                                                        if (method == null) {
                                                            stringResource(R.string.audit_payment_received)
                                                        } else {
                                                            "${stringResource(R.string.audit_payment_received)} - $method"
                                                        }
                                                    }

                                                    "ADJUSTMENT" -> "Credit Adjustment"

                                                    else -> "Credit Activity"
                                                }

                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 6.dp),
                                                horizontalArrangement =
                                                    Arrangement.SpaceBetween,
                                                verticalAlignment =
                                                    Alignment.CenterVertically
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement =
                                                        Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        title,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 14.sp
                                                    )

                                                    Text(
                                                        formatCustomerDateTime(
                                                            row.occurredAt
                                                        ),
                                                        fontSize = 12.sp,
                                                        color =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .onSurfaceVariant
                                                    )

                                                    // BOARDLOCK_UNIVERSAL_RECEIPT_IMAGE_V4
                                                    // Receipt for a Credit payment received.
                                                    val paymentReceiptParty =
                                                        if (row.kind.name == "REPAYMENT") messageParty() else null

                                                    if (paymentReceiptParty != null) {
                                                        TextButton(
                                                            onClick = {
                                                                val money: (Long) -> String = { minor ->
                                                                    formatCustomerMoney(
                                                                        minor,
                                                                        state.creditDecimalPlaces,
                                                                        row.currencyCode
                                                                    )
                                                                }

                                                                // The remaining balance is only exact for the newest entry.
                                                                val isNewestEntry =
                                                                    state.creditHistory.none {
                                                                        it.occurredAt > row.occurredAt
                                                                    }

                                                                val paymentEvent =
                                                                    UniversalWhatsAppMessageComposer.PaymentReceived(
                                                                        party = UniversalWhatsAppMessageComposer.Party(
                                                                            businessName = paymentReceiptParty.businessName,
                                                                            customerName = paymentReceiptParty.customerName,
                                                                            customerPhone = paymentReceiptParty.customerPhone
                                                                            labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
                                                                        ),
                                                                        formattedAmount =
                                                                            money(kotlin.math.abs(row.amountSignedMinor)),
                                                                        paymentMethod =
                                                                            row.repaymentMethodName
                                                                                ?.lowercase()
                                                                                ?.replaceFirstChar { it.uppercase() }
                                                                                ?: "Recorded payment",
                                                                        formattedCreditPending =
                                                                            if (isNewestEntry) {
                                                                                money(state.creditOutstandingMinor)
                                                                            } else {
                                                                                null
                                                                            },
                                                                        eventDateTime =
                                                                            formatCustomerDateTime(row.occurredAt)
                                                                    )

                                                                runCatching {
                                                                    ReceiptSharing.sharePaymentReceivedWhatsApp(
                                                                        context = context,
                                                                        event = paymentEvent
                                                                    )
                                                                }.onFailure {
                                                                    WhatsAppTransport.share(
                                                                        context = context,
                                                                        prepared = PreparedWhatsAppMessage(
                                                                            text = UniversalWhatsAppMessageComposer
                                                                                .compose(paymentEvent),
                                                                            recipientPhone = paymentReceiptParty.customerPhone
                                                                        )
                                                                    )
                                                                }
                                                            },
                                                            contentPadding =
                                                                androidx.compose.foundation.layout.PaddingValues(0.dp)
                                                        ) {
                                                            Text(
                                                                stringResource(R.string.audit_customer_send_receipt),
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    }
                                                }

                                                val amountText =
                                                    formatCustomerMoney(
                                                        row.amountSignedMinor,
                                                        state.creditDecimalPlaces,
                                                        row.currencyCode
                                                    )

                                                Text(
                                                    text =
                                                        if (row.amountSignedMinor > 0L) {
                                                            "+$amountText"
                                                        } else {
                                                            amountText
                                                        },
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                        /*

                         * DOCUMENTS
                         *
                         * Existing frozen invoice snapshots and existing
                         * PDF / WhatsApp share path are reused unchanged.
                         */
                        3 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween,
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.audit_customer_documents),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )

                                Text(
                                    "${state.invoices.size} invoice" +
                                        if (state.invoices.size == 1) {
                                            ""
                                        } else {
                                            "s"
                                        },
                                    fontSize = 13.sp,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                )
                            }

                            if (state.invoices.isEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.audit_customer_no_documents),
                                        modifier =
                                            Modifier.padding(20.dp),
                                        color =
                                            MaterialTheme
                                                .colorScheme
                                                .onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        state.invoices,
                                        key = {
                                            it.documentId
                                        }
                                    ) { row ->
                                        InvoiceHistoryRow(
                                            row = row,
                                            onClick = {
                                                actions.onOpenInvoice(
                                                    row
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        4 -> {
                            /*
                             * BOARDLOCK_CUSTOMER_PAYMENTS_V2
                             *
                             * Presentation-only relationship history.
                             * Expense = authoritative Money Out.
                             * Credit REPAYMENT = authoritative Money In.
                             * No Payment entity or mutable financial state is created.
                             */
                            val paymentRows =
                                buildList {
                                    state.expenses.forEach { expense ->
                                        add(
                                            CustomerPaymentDisplayRow(
                                                occurredAt = expense.occurredAt,
                                                title = stringResource(R.string.audit_payment_made),
                                                detail = "${expense.category} - ${expense.paymentCategory.name}",
                                                amountMinor = expense.amount.amount,
                                                currencyCode = expense.currencyCode,
                                                isMoneyOut = true,
                                                reference = expense.reference
                                            )
                                        )
                                    }

                                    state.creditHistory
                                        .filter { it.kind == CustomerCreditHistoryKind.REPAYMENT }
                                        .forEach { repayment ->
                                            add(
                                                CustomerPaymentDisplayRow(
                                                    occurredAt = repayment.occurredAt,
                                                    title = stringResource(R.string.audit_payment_received),
                                                    detail = repayment.repaymentMethodName ?: "Repayment",
                                                    amountMinor = kotlin.math.abs(repayment.amountSignedMinor),
                                                    currencyCode = repayment.currencyCode,
                                                    isMoneyOut = false,
                                                    reference = null
                                                )
                                            )
                                        }
                                }.sortedByDescending { it.occurredAt }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.audit_customer_payments_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    stringResource(R.string.audit_customer_payments_subtitle),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )

                                if (paymentRows.isEmpty()) {
                                    Text(
                                        stringResource(R.string.audit_customer_no_payment_activity),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    paymentRows.forEach { payment ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    payment.title,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp
                                                )
                                                Text(
                                                    payment.detail,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    formatCustomerDateTime(payment.occurredAt),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                payment.reference
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let { reference ->
                                                        Text(
                                                            reference,
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                            }

                                            Text(
                                                text =
                                                    (if (payment.isMoneyOut) "-" else "+") +
                                                        formatCustomerMoney(
                                                            payment.amountMinor,
                                                            state.creditDecimalPlaces,
                                                            payment.currencyCode
                                                        ),
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.isReceivePaymentOpen) {
        CreditReceivePaymentDialog(
            state = state,

            onAmountChange =
                actions.onRepaymentAmountChange,

            onMethodChange =
                actions.onSelectRepaymentMethod,

            onConfirm =
                actions.onConfirmReceivePayment,

            onDismiss =
                actions.onCloseReceivePayment
        )
    }

    if (whatsappChoice == "CREDIT") {
        AlertDialog(
            onDismissRequest = {
                whatsappChoice = null
            },
            title = {
                Text(stringResource(R.string.send_credit_via_whatsapp))
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val party = messageParty()

                            if (party != null) {
                                whatsappPreview =
                                    CustomerWhatsAppMessageComposer
                                        .creditBalance(
                                            party = party,
                                            formattedAmount =
                                                formatCustomerMoney(
                                                    state.creditOutstandingMinor,
                                                    state.creditDecimalPlaces,
                                                    state.creditCurrencyCode ?: ""
                                                ),
                                            messageDateTime =
                                                formatCustomerDateTime(
                                                    System.currentTimeMillis()
                                                )
                                        )

                                whatsappChoice = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.credit_balance))
                    }

                    if (state.creditOutstandingMinor > 0L) {
                        Button(
                            onClick = {
                                val party = messageParty()

                                if (party != null) {
                                    whatsappPreview =
                                        CustomerWhatsAppMessageComposer
                                            .paymentReminder(
                                                party = party,
                                                outstandingMinor =
                                                    state.creditOutstandingMinor,
                                                formattedAmount =
                                                    formatCustomerMoney(
                                                        state.creditOutstandingMinor,
                                                        state.creditDecimalPlaces,
                                                        state.creditCurrencyCode ?: ""
                                                    ),
                                                messageDateTime =
                                                    formatCustomerDateTime(
                                                        System.currentTimeMillis()
                                                    )
                                            )

                                    whatsappChoice = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.payment_reminder))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        whatsappChoice = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (whatsappChoice == "COUPON") {
        val coupon =
            state.couponWalletDetails

        val latestPurchase =
            coupon
                ?.ledgerEvents
                ?.asReversed()
                ?.firstOrNull {
                    it.type.name == "PURCHASE"
                }

        AlertDialog(
            onDismissRequest = {
                whatsappChoice = null
            },
            title = {
                Text(stringResource(R.string.send_coupon_via_whatsapp))
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val party = messageParty()

                            if (
                                party != null &&
                                coupon != null
                            ) {
                                val currency =
                                    coupon
                                        .ledgerEvents
                                        .lastOrNull()
                                        ?.currencyCode
                                        ?: state.creditCurrencyCode
                                        ?: ""

                                whatsappPreview =
                                    CustomerWhatsAppMessageComposer
                                        .couponBalance(
                                            party = party,
                                            formattedBalance =
                                                formatCustomerMoney(
                                                    coupon.balanceMinor,
                                                    state.creditDecimalPlaces,
                                                    currency
                                                ),
                                            messageDateTime =
                                                formatCustomerDateTime(
                                                    System.currentTimeMillis()
                                                )
                                        )

                                whatsappChoice = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.coupon_balance))
                    }

                    if (
                        coupon != null &&
                        latestPurchase != null
                    ) {
                        Button(
                            onClick = {
                                val party = messageParty()

                                if (party != null) {
                                    val balanceCurrency =
                                        coupon
                                            .ledgerEvents
                                            .lastOrNull()
                                            ?.currencyCode
                                            ?: state.creditCurrencyCode
                                            ?: ""

                                    whatsappPreview =
                                        CustomerWhatsAppMessageComposer
                                            .couponPurchaseOrTopUp(
                                                party = party,
                                                formattedEventAmount =
                                                    formatCustomerMoney(
                                                        latestPurchase
                                                            .amountSignedMinor,
                                                        state.creditDecimalPlaces,
                                                        latestPurchase
                                                            .currencyCode
                                                    ),
                                                formattedCurrentBalance =
                                                    formatCustomerMoney(
                                                        coupon.balanceMinor,
                                                        state.creditDecimalPlaces,
                                                        balanceCurrency
                                                    ),
                                                transactionDateTime =
                                                    formatCustomerDateTime(
                                                        latestPurchase
                                                            .occurredAt
                                                    ),
                                                messageDateTime =
                                                    formatCustomerDateTime(
                                                        System.currentTimeMillis()
                                                    )
                                            )

                                    whatsappChoice = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.coupon_purchase_top_up_option))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        whatsappChoice = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    whatsappPreview?.let { exactMessage ->
        AlertDialog(
            onDismissRequest = {
                whatsappPreview = null
            },
            title = {
                Text(stringResource(R.string.whatsapp_preview))
            },
            text = {
                LazyColumn {
                    item {
                        Text(
                            exactMessage,
                            fontSize = 14.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        /*
                         * exactMessage is both the previewed text and
                         * the text handed to WhatsApp.
                         */
                        val recipientPhone =
                            customer
                                ?.canonicalPhone
                                ?.takeIf {
                                    it.isNotBlank()
                                }

                        if (recipientPhone != null) {
                            WhatsAppTransport.share(
                                context = context,
                                prepared = PreparedWhatsAppMessage(
                                    text = exactMessage,
                                    recipientPhone = recipientPhone
                                )
                            )

                            whatsappPreview = null
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                Color(0xFF25D366),
                            contentColor =
                                Color.White
                        )
                ) {
                    Text(stringResource(R.string.send_via_whatsapp))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        whatsappPreview = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    state.selectedInvoice?.let { invoice ->
        AlertDialog(
            onDismissRequest = actions.onCloseInvoice,
            title = { Text(invoice.displayNumber) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.customers_invoice_options_hint),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.isPreparingShare) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(stringResource(R.string.customers_preparing_pdf), fontSize = 14.sp)
                        }
                    }
                    state.shareError?.let { error ->
                        Text(error.asString(), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = { actions.onSharePdf(context) },
                        enabled = !state.isPreparingShare,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.inv_pdf_share_pdf)) }
                    Button(
                        onClick = { actions.onShareWhatsApp(context) },
                        enabled = !state.isPreparingShare,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF25D366),
                            contentColor = Color.White
                        )
                    ) { Text(stringResource(R.string.inv_pdf_share_whatsapp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = actions.onCloseInvoice,
                    enabled = !state.isPreparingShare
                ) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvoiceHistoryRow(row: CustomerInvoiceRow, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.displayNumber, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    formatCustomerDateTime(row.createdAt),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val total = row.totalMinor
                val code = row.currencyCode
                val places = row.decimalPlaces
                Text(
                    text = if (total != null && code != null && places != null) {
                        formatCustomerMoney(total, places, code)
                    } else {
                        stringResource(R.string.customers_amount_unavailable)
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )

            }
        }
    }
}

/**
 * CREDIT_1D_B1_RECEIVE_PAYMENT
 *
 * Presentation only.
 *
 * Financial mutation remains inside the already-tested
 * RegisterRepository.executeCreditRepayment command path.
 */
@Composable
private fun CreditReceivePaymentDialog(
    state: CustomerDetailUiState,
    onAmountChange: (String) -> Unit,
    onMethodChange: (PaymentCategory) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val currency =
        state.creditCurrencyCode ?: ""

    AlertDialog(
        onDismissRequest = {
            if (
                !state.isSubmittingRepayment
            ) {
                onDismiss()
            }
        },

        title = {
            Text(
                stringResource(R.string.audit_customer_receive_credit_payment)
            )
        },

        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    stringResource(R.string.audit_customer_outstanding) + " " +
                        formatCustomerMoney(
                            state.creditOutstandingMinor,
                            state.creditDecimalPlaces,
                            currency
                        ),

                    fontWeight =
                        FontWeight.SemiBold
                )

                OutlinedTextField(
                    value =
                        state.repaymentAmount,

                    onValueChange =
                        onAmountChange,

                    label = {
                        Text(
                            stringResource(R.string.audit_amount)
                        )
                    },

                    singleLine =
                        true,

                    enabled =
                        !state.isSubmittingRepayment,

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),

                    modifier =
                        Modifier.fillMaxWidth()
                )

                Text(
                    stringResource(R.string.audit_payment_method_titlecase),

                    fontWeight =
                        FontWeight.SemiBold
                )

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    FilterChip(
                        selected =
                            state.repaymentMethod ==
                                PaymentCategory.CASH,

                        onClick = {
                            onMethodChange(
                                PaymentCategory.CASH
                            )
                        },

                        label = {
                            Text(
                                stringResource(R.string.audit_cash_titlecase)
                            )
                        },

                        enabled =
                            !state.isSubmittingRepayment
                    )

                    FilterChip(
                        selected =
                            state.repaymentMethod ==
                                PaymentCategory.ONLINE,

                        onClick = {
                            onMethodChange(
                                PaymentCategory.ONLINE
                            )
                        },

                        label = {
                            Text(
                                stringResource(R.string.audit_online_titlecase)
                            )
                        },

                        enabled =
                            !state.isSubmittingRepayment
                    )
                }

                state.repaymentError
                    ?.let { message ->

                        Text(
                            message,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }

                if (
                    state.isSubmittingRepayment
                ) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier.fillMaxWidth()
                    )
                }
            }
        },

        confirmButton = {

            Button(
                onClick =
                    onConfirm,

                enabled =
                    !state.isSubmittingRepayment &&
                        state.repaymentAmount
                            .isNotBlank()
            ) {
                Text(
                    stringResource(R.string.audit_customer_receive_payment)
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss,

                enabled =
                    !state.isSubmittingRepayment
            ) {
                Text(
                    stringResource(R.string.audit_cancel)
                )
            }
        }
    )
}




