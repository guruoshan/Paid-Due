package com.dailymoneyregister.feature.register.ui

import androidx.compose.foundation.layout.Arrangement
import com.dailymoneyregister.feature.register.data.VoidBlockReason
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private sealed interface TransactionTimelineItem {
    val occurredAt: Long
    val stableId: String

    data class Original(
        override val occurredAt: Long,
        override val stableId: String
    ) : TransactionTimelineItem

    data class CreditRepayment(
        val allocation: CreditRepaymentAllocationEntity,
        val repayment: CreditRepaymentEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long
            get() = repayment.occurredAt

        override val stableId: String
            get() = "credit:${repayment.id}:${allocation.id}"
    }

    data class AmountCorrection(
        val event: AmountCorrectionEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "correction:${event.id}"
    }

    data class PaymentMethodChange(
        val event: PaymentMethodChangeEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "payment:${event.id}"
    }

    data class Refund(
        val event: RefundEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "refund:${event.id}"
    }

    data class Void(
        val event: VoidEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "void:${event.id}"
    }

    data class Settlement(
        val event: SettlementEntryEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "settlement:${event.id}"
    }
}

@Composable
fun TransactionDetailsScreen(
    details: TransactionDetailsReadModel,
    currency: CurrencyConfig,
    timezoneId: String?,
    onBack: () -> Unit,
    onCorrectAmount: (String, String?) -> Unit = { _, _ -> },
    onRefund: (String, PaymentCategory, String?) -> Unit = { _, _, _ -> },
    onRefundToCoupon: (String, String?) -> Unit = { _, _ -> },
    onVoid: (String?) -> Unit = {},
    onChangePaymentMethod: (PaymentCategory, String?) -> Unit = { _, _ -> },
    correctAmountEnabled: Boolean = false,
    refundEnabled: Boolean = false,
    voidEnabled: Boolean = false,
    changePaymentEnabled: Boolean = false,
    actionInProgress: Boolean = false,
    actionError: String? = null,
    actionInfo: String? = null,
    modifier: Modifier = Modifier
) {
    val validatedTimezoneId =
        validateTransactionDetailsTimezoneId(
            timezoneId
        )

    if (validatedTimezoneId == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = onBack) {
                Text(stringResource(R.string.back))
            }

            Text(
                text = stringResource(
                    R.string.tx_details_timezone_unavailable
                ),
                color = MaterialTheme.colorScheme.error
            )
        }

        return
    }

    val transaction = details.financial

    // Presentation-only disclosure state.
    // This is NOT financial state.
    val showAuditDetails =
        androidx.compose.runtime.saveable.rememberSaveable(
            transaction.transaction.id,
            "showAuditDetails"
        ) {
            androidx.compose.runtime.mutableStateOf(false)
        }

    // BOARDLOCK_CORRECT_AMOUNT_STATE
    val showCorrectAmountDialog =
        androidx.compose.runtime.saveable.rememberSaveable(
            transaction.transaction.id
        ) {
            androidx.compose.runtime.mutableStateOf(false)
        }

    val correctedAmountInput =
        androidx.compose.runtime.saveable.rememberSaveable(
            transaction.transaction.id,
            "correctedAmount"
        ) {
            androidx.compose.runtime.mutableStateOf("")
        }

    val correctionReasonInput =
        androidx.compose.runtime.saveable.rememberSaveable(
            transaction.transaction.id,
            "correctedReason"
        ) {
            androidx.compose.runtime.mutableStateOf("")
        }

    val showRefundDialog = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "refundDialog"
    ) { androidx.compose.runtime.mutableStateOf(false) }
    val refundAmountInput = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "refundAmount"
    ) { androidx.compose.runtime.mutableStateOf("") }
    val refundReasonInput = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "refundReason"
    ) { androidx.compose.runtime.mutableStateOf("") }
    val refundDestination = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "refundDestination"
    ) { androidx.compose.runtime.mutableStateOf(transaction.currentPaymentCategory ?: PaymentCategory.CASH) }
    val showVoidDialog = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "voidDialog"
    ) { androidx.compose.runtime.mutableStateOf(false) }
    val voidReasonInput = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "voidReason"
    ) { androidx.compose.runtime.mutableStateOf("") }
    val showChangePaymentDialog = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "changePaymentDialog"
    ) { androidx.compose.runtime.mutableStateOf(false) }
    val changePaymentReasonInput = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "changePaymentReason"
    ) { androidx.compose.runtime.mutableStateOf("") }
    val changePaymentDestination = androidx.compose.runtime.saveable.rememberSaveable(
        transaction.transaction.id, "changePaymentDestination"
    ) {
        androidx.compose.runtime.mutableStateOf(
            if (transaction.currentPaymentCategory == PaymentCategory.CASH) {
                PaymentCategory.ONLINE
            } else {
                PaymentCategory.CASH
            }
        )
    }

    val creditReturnRoute =
        creditReturnUiRoute(
            paymentCategory =
                transaction.currentPaymentCategory,
            paymentChannel =
                transaction.currentPaymentChannel,
            creditRepaymentAllocationCount =
                details.creditRepaymentAllocations.size
        )

    /*
     * Financial eligibility comes from TransactionActionEligibilityResolver,
     * which delegates to the authoritative *FinancialIntegrity validators.
     * Permission and in-flight UI state remain separate presentation gates.
     */
    val actionEligibility =
        details.actionEligibility

    val canCorrectAmount =
        correctAmountEnabled &&
            actionEligibility.canCorrectAmount &&
            !actionInProgress

    val canRefund =
        refundEnabled &&
            actionEligibility.canRefund &&
            !actionInProgress

    val canVoid =
        voidEnabled &&
            actionEligibility.canVoid &&
            !actionInProgress

    val canChangePayment =
        changePaymentEnabled &&
            actionEligibility.canChangePaymentMethod &&
            !actionInProgress
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text(stringResource(R.string.back))
            }

            Text(
                text =
                    stringResource(
                        R.string.tx_details_title
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = 72.dp),
                style =
                    MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign =
                    androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text =
                        when {
                            transaction.isVoided ->
                                stringResource(R.string.audit_transaction_voided)

                            transaction.lifecycleStates.contains(
                                FinancialLifecycleState.FULLY_REFUNDED
                            ) ->
                                "Fully Refunded"

                            transaction.lifecycleStates.contains(
                                FinancialLifecycleState.PARTIALLY_REFUNDED
                            ) ->
                                "Partially Refunded"

                            transaction.currentPaymentCategory ==
                                PaymentCategory.CREDIT ->
                                "Credit Sale"

                            else ->
                                "Sale"
                        },
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text =
                        transaction.currentAmount
                            .formatDisplay(currency),
                    style =
                        MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text =
                        simplePaymentLabel(
                            transaction.currentPaymentCategory
                        ),
                    style =
                        MaterialTheme.typography.titleMedium
                )

                Text(
                    text =
                        formatTransactionTimestamp(
                            transaction.transaction.createdAt,
                            validatedTimezoneId
                        ),
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (
                    transaction.refundedAmount.amount > 0L
                ) {
                    TransactionDetailRow(
                        "Returned",
                        transaction.refundedAmount
                            .formatDisplay(currency)
                    )
                }

                TransactionDetailRow(
                    "Net amount",
                    transaction.netAmount
                        .formatDisplay(currency)
                )

                androidx.compose.material3.TextButton(
                    onClick = {
                        showAuditDetails.value =
                            !showAuditDetails.value
                    }
                ) {
                    Text(
                        if (showAuditDetails.value) {
                            "Hide audit details"
                        } else {
                            "View audit details"
                        }
                    )
                }

                if (showAuditDetails.value) {
                    Card(
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                stringResource(R.string.audit_audit_information),
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            TransactionDetailRow(
                                "Transaction ID",
                                transaction.transaction.id
                            )

                            TransactionDetailRow(
                                "Original amount",
                                transaction.originalAmount
                                    .formatDisplay(currency)
                            )

                            TransactionDetailRow(
                                "Current amount",
                                transaction.currentAmount
                                    .formatDisplay(currency)
                            )

                            TransactionDetailRow(
                                "Returned",
                                transaction.refundedAmount
                                    .formatDisplay(currency)
                            )

                            TransactionDetailRow(
                                "Net amount",
                                transaction.netAmount
                                    .formatDisplay(currency)
                            )

                            TransactionDetailRow(
                                "Status",
                                lifecycleLabel(
                                    transaction.lifecycleStates
                                )
                            )

                            TransactionDetailRow(
                                "Original payment",
                                paymentMethodLabel(
                                    transaction.originalPaymentCategory,
                                    transaction.originalPaymentChannel
                                )
                            )

                            TransactionDetailRow(
                                "Current payment",
                                paymentMethodLabel(
                                    transaction.currentPaymentCategory,
                                    transaction.currentPaymentChannel
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // BOARDLOCK_CORRECT_AMOUNT_BUTTON
                    Button(
                        onClick = {
                            correctedAmountInput.value = ""
                            correctionReasonInput.value = ""
                            showCorrectAmountDialog.value = true
                        },
                        enabled = canCorrectAmount,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(
                                R.string.tx_details_correct_amount
                            )
                        )
                    }

                    // Refund is offered for every sale. Credit sales open the credit-return mode
                    // of the same dialog, which reduces unpaid Credit with no payout.
                        Button(
                            onClick = {
                                refundAmountInput.value = transaction.netAmount
                                    .formatDisplay(currency.decimalPlaces, "").trim()
                                refundReasonInput.value = ""
                                refundDestination.value =
                                    transaction.currentPaymentCategory ?: PaymentCategory.CASH

                                showRefundDialog.value = true
                            },
                            enabled = canRefund,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.tx_details_refund)) }

                    Button(
                        onClick = {
                            voidReasonInput.value = ""
                            showVoidDialog.value = true
                        },
                        enabled = canVoid,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.tx_details_void)) }
                }

                actionEligibility.voidBlockReason?.let { reason ->
                    Text(
                        text = stringResource(
                            when (reason) {
                                VoidBlockReason.PAYMENT_RECORD_UNCLEAR ->
                                    R.string.tx_void_unavailable_payment_unclear
                                else -> R.string.tx_void_unavailable_credit_or_coupon
                            }
                        ),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        changePaymentDestination.value =
                            if (transaction.currentPaymentCategory == PaymentCategory.CASH) {
                                PaymentCategory.ONLINE
                            } else {
                                PaymentCategory.CASH
                            }
                        changePaymentReasonInput.value = ""
                        showChangePaymentDialog.value = true
                    },
                    enabled = canChangePayment,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.tx_details_change_payment)) }
                // BOARDLOCK_CORRECT_AMOUNT_DIALOG

                actionError
                    ?.takeIf { it.isNotBlank() }
                    ?.let { message ->
                        Text(
                            text = message,
                            color =
                                MaterialTheme.colorScheme.error
                        )
                    }

                actionInfo
                    ?.takeIf { it.isNotBlank() }
                    ?.let { message ->
                        Text(
                            text = message,
                            color =
                                MaterialTheme.colorScheme.primary
                        )
                    }

                if (showCorrectAmountDialog.value) {

                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            if (!actionInProgress) {
                                showCorrectAmountDialog.value =
                                    false
                            }
                        },
                        title = {
                            Text(
                                stringResource(
                                    R.string.tx_correct_amount_title
                                )
                            )
                        },
                        text = {
                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        12.dp
                                    )
                            ) {
                                Text(
                                    stringResource(
                                        R.string.tx_correct_amount_current,
                                        transaction.currentAmount
                                            .formatDisplay(
                                                currency
                                            )
                                    )
                                )

                                androidx.compose.material3.OutlinedTextField(
                                    value =
                                        correctedAmountInput.value,
                                    onValueChange = {
                                        correctedAmountInput.value =
                                            it
                                    },
                                    enabled =
                                        !actionInProgress,
                                    singleLine = true,
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.tx_correct_amount_new
                                            )
                                        )
                                    }
                                )

                                androidx.compose.material3.OutlinedTextField(
                                    value =
                                        correctionReasonInput.value,
                                    onValueChange = {
                                        correctionReasonInput.value =
                                            it
                                    },
                                    enabled =
                                        !actionInProgress,
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.tx_correct_amount_reason
                                            )
                                        )
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    onCorrectAmount(
                                        correctedAmountInput.value,
                                        correctionReasonInput.value
                                    )
                                    showCorrectAmountDialog.value =
                                        false
                                },
                                enabled =
                                    !actionInProgress &&
                                        correctedAmountInput
                                            .value
                                            .isNotBlank()
                            ) {
                                Text(
                                    stringResource(
                                        R.string.tx_correct_amount_confirm
                                    )
                                )
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showCorrectAmountDialog.value =
                                        false
                                },
                                enabled =
                                    !actionInProgress
                            ) {
                                Text(
                                    stringResource(
                                        R.string.cancel
                                    )
                                )
                            }
                        }
                    )
                }

                if (showRefundDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            showRefundDialog.value = false
                        },
                        title = { Text(stringResource(R.string.tx_refund_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    stringResource(
                                        R.string.tx_refund_available,
                                        transaction.netAmount.formatDisplay(currency)
                                    )
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = refundAmountInput.value,
                                    onValueChange = { refundAmountInput.value = it },
                                    enabled = !actionInProgress,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.tx_refund_amount_input)) }
                                )
                                if (
                                    creditReturnRoute ==
                                        CreditReturnUiRoute.UNPAID_CREDIT ||
                                    creditReturnRoute ==
                                        CreditReturnUiRoute.REPAID_CREDIT
                                ) {
                                    Text(
                                        text =
                                            if (
                                                creditReturnRoute ==
                                                    CreditReturnUiRoute.REPAID_CREDIT
                                            ) {
                                                "The return reduces unpaid Credit first. " +
                                                    "Any refundable paid value will use the destination selected below."
                                            } else {
                                                "Nothing has been repaid, so this return only reduces the customer's debt. No money is paid out."
                                            }
                                    )

                                    if (creditReturnRoute != CreditReturnUiRoute.UNPAID_CREDIT) {
                                        Text(
                                            stringResource(
                                                R.string.tx_refund_destination
                                            )
                                        )

                                        TransactionActionChoice(
                                            selected =
                                                refundDestination.value ==
                                                    PaymentCategory.CASH,
                                            label = stringResource(R.string.tx_payment_cash),
                                            enabled =
                                                !actionInProgress,
                                            onClick = {
                                                refundDestination.value =
                                                    PaymentCategory.CASH
                                            }
                                        )

                                        TransactionActionChoice(
                                            selected =
                                                refundDestination.value ==
                                                    PaymentCategory.ONLINE,
                                            label = stringResource(R.string.tx_payment_online),
                                            enabled =
                                                !actionInProgress,
                                            onClick = {
                                                refundDestination.value =
                                                    PaymentCategory.ONLINE
                                            }
                                        )

                                        TransactionActionChoice(
                                            selected =
                                                refundDestination.value ==
                                                    PaymentCategory.COUPON,
                                            label =
                                                stringResource(
                                                    R.string.tx_refund_to_coupon
                                                ),
                                            enabled =
                                                !actionInProgress,
                                            onClick = {
                                                refundDestination.value =
                                                    PaymentCategory.COUPON
                                            }
                                        )
                                    }
                                } else {
                                    Text(
                                        stringResource(
                                            R.string.tx_refund_destination
                                        )
                                    )

                                    TransactionActionChoice(
                                        selected =
                                            refundDestination.value ==
                                            transaction.currentPaymentCategory,
                                        label =
                                            stringResource(
                                                R.string.tx_refund_original_destination,
                                                paymentMethodLabel(
                                                    transaction.currentPaymentCategory,
                                                    transaction.currentPaymentChannel
                                                )
                                            ),
                                        enabled =
                                            !actionInProgress,
                                        onClick = {
                                            transaction
                                                .currentPaymentCategory
                                                ?.let {
                                                    refundDestination.value =
                                                        it
                                                }
                                        }
                                    )

                                    TransactionActionChoice(
                                        selected =
                                            refundDestination.value ==
                                            PaymentCategory.COUPON,
                                        label =
                                            stringResource(
                                                R.string.tx_refund_to_coupon
                                            ),
                                        enabled =
                                            !actionInProgress,
                                        onClick = {
                                            refundDestination.value =
                                                PaymentCategory.COUPON
                                        }
                                    )
                                }
                                androidx.compose.material3.OutlinedTextField(
                                    value = refundReasonInput.value,
                                    onValueChange = { refundReasonInput.value = it },
                                    enabled = !actionInProgress,
                                    label = { Text(stringResource(R.string.tx_action_reason)) }
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    if (
                                        refundDestination.value ==
                                        PaymentCategory.COUPON
                                    ) {
                                        onRefundToCoupon(
                                            refundAmountInput.value,
                                            refundReasonInput.value
                                        )
                                    } else {
                                        onRefund(
                                            refundAmountInput.value,
                                            refundDestination.value,
                                            refundReasonInput.value
                                        )
                                    }

                                    showRefundDialog.value =
                                        false
                                },
                                enabled = !actionInProgress && refundAmountInput.value.isNotBlank()
                            ) { Text(stringResource(R.string.tx_refund_confirm)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showRefundDialog.value = false },
                            ) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }

                if (showVoidDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            if (!actionInProgress) showVoidDialog.value = false
                        },
                        title = { Text(stringResource(R.string.tx_void_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    stringResource(
                                        R.string.tx_void_confirmation,
                                        transaction.netAmount.formatDisplay(currency)
                                    )
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = voidReasonInput.value,
                                    onValueChange = { voidReasonInput.value = it },
                                    enabled = !actionInProgress,
                                    label = { Text(stringResource(R.string.tx_action_reason)) }
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    onVoid(voidReasonInput.value)
                                    showVoidDialog.value = false
                                },
                                enabled = !actionInProgress
                            ) { Text(stringResource(R.string.tx_void_confirm)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showVoidDialog.value = false },
                                enabled = !actionInProgress
                            ) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }

                if (showChangePaymentDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            if (!actionInProgress) showChangePaymentDialog.value = false
                        },
                        title = { Text(stringResource(R.string.tx_change_payment_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    stringResource(
                                        R.string.tx_change_payment_current,
                                        paymentMethodLabel(
                                            transaction.currentPaymentCategory,
                                            transaction.currentPaymentChannel
                                        )
                                    )
                                )
                                listOf(PaymentCategory.CASH, PaymentCategory.ONLINE).forEach { category ->
                                    TransactionActionChoice(
                                        selected = changePaymentDestination.value == category,
                                        label = stringResource(
                                            if (category == PaymentCategory.CASH) {
                                                R.string.tx_payment_cash
                                            } else {
                                                R.string.tx_payment_online
                                            }
                                        ),
                                        enabled = !actionInProgress &&
                                            category != transaction.currentPaymentCategory,
                                        onClick = { changePaymentDestination.value = category }
                                    )
                                }
                                androidx.compose.material3.OutlinedTextField(
                                    value = changePaymentReasonInput.value,
                                    onValueChange = { changePaymentReasonInput.value = it },
                                    enabled = !actionInProgress,
                                    label = { Text(stringResource(R.string.tx_action_reason)) }
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    onChangePaymentMethod(
                                        changePaymentDestination.value,
                                        changePaymentReasonInput.value
                                    )
                                    showChangePaymentDialog.value = false
                                },
                                enabled = !actionInProgress &&
                                    changePaymentDestination.value != transaction.currentPaymentCategory
                            ) { Text(stringResource(R.string.tx_change_payment_confirm)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showChangePaymentDialog.value = false },
                                enabled = !actionInProgress
                            ) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.tx_details_items),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (details.items.isEmpty()) {
            Text(
                text = stringResource(R.string.tx_details_no_items),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (item in details.items) {
                TransactionItemCard(
                    item = item,
                    currency = currency
                )
            }
        }

        Text(
            text = stringResource(R.string.activity_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.audit_what_happened_transaction),
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        val repaymentById =
            details.creditRepayments.associateBy { it.id }

        val timeline =
            mutableListOf<TransactionTimelineItem>()

        timeline +=
            TransactionTimelineItem.Original(
                occurredAt = transaction.transaction.createdAt,
                stableId = "original:${transaction.transaction.id}"
            )

        for (allocation in details.creditRepaymentAllocations) {
            val repayment =
                repaymentById.getValue(
                    allocation.repaymentId
                )

            timeline +=
                TransactionTimelineItem.CreditRepayment(
                    allocation = allocation,
                    repayment = repayment
                )
        }

        for (event in details.amountCorrections) {
            timeline +=
                TransactionTimelineItem.AmountCorrection(event)
        }

        for (event in details.paymentMethodChanges) {
            timeline +=
                TransactionTimelineItem.PaymentMethodChange(event)
        }

        for (event in details.refundEvents) {
            timeline +=
                TransactionTimelineItem.Refund(event)
        }

        for (event in details.voidEvents) {
            timeline +=
                TransactionTimelineItem.Void(event)
        }


        val orderedTimeline =
            sortTransactionDetailsTimeline(
                items = timeline,
                occurredAt = { it.occurredAt },
                stableId = { it.stableId }
            )

        for (item in orderedTimeline) {
            when (item) {
                is TransactionTimelineItem.Original ->
                    OriginalTransactionTimelineCard(
                        details = details,
                        currency = currency,
                        timezoneId = validatedTimezoneId
                    )

                is TransactionTimelineItem.CreditRepayment ->
                    CreditRepaymentTimelineCard(
                        allocation = item.allocation,
                        repayment = item.repayment,
                        currency = currency,
                        timezoneId = validatedTimezoneId
                    )

                is TransactionTimelineItem.AmountCorrection ->
                    AmountCorrectionTimelineCard(
                        event = item.event,
                        currency = currency,
                        timezoneId = validatedTimezoneId
                    )

                is TransactionTimelineItem.PaymentMethodChange ->
                    PaymentMethodChangeTimelineCard(
                        event = item.event,
                        timezoneId = validatedTimezoneId
                    )

                is TransactionTimelineItem.Refund ->
                    RefundTimelineCard(
                        event = item.event,
                        currency = currency,
                        timezoneId = validatedTimezoneId
                    )

                is TransactionTimelineItem.Void ->
                    VoidTimelineCard(
                        event = item.event,
                        timezoneId = validatedTimezoneId
                    )

                is TransactionTimelineItem.Settlement ->
                    SettlementTimelineCard(
                        settlement = item.event,
                        currency = currency,
                        timezoneId = validatedTimezoneId
                    )
            }
        }
    }
}

@Composable
private fun OriginalTransactionTimelineCard(
    details: TransactionDetailsReadModel,
    currency: CurrencyConfig,
    timezoneId: String
) {
    val transaction = details.financial

    TimelineCard(
        title =
            if (
                transaction.originalPaymentCategory ==
                    PaymentCategory.CREDIT
            ) {
                "Credit Sale"
            } else {
                "Sale"
            }
    ) {
        Text(
            transaction.originalAmount
                .formatDisplay(currency),
            style =
                MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            simplePaymentLabel(
                transaction.originalPaymentCategory
            )
        )

        TimelineTimestamp(
            transaction.transaction.createdAt,
            timezoneId
        )
    }
}
@Composable
private fun CreditRepaymentTimelineCard(
    allocation: CreditRepaymentAllocationEntity,
    repayment: CreditRepaymentEntity,
    currency: CurrencyConfig,
    timezoneId: String
) {
    TimelineCard(
        title = stringResource(R.string.payment_received)
    ) {
        Text(
            MoneyMinorUnits(
                allocation.allocatedAmountMinor
            ).formatDisplay(currency),
            style =
                MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            simplePaymentLabel(
                repayment.paymentCategory
            )
        )

        TimelineTimestamp(
            repayment.occurredAt,
            timezoneId
        )
    }
}
@Composable
private fun AmountCorrectionTimelineCard(
    event: AmountCorrectionEventEntity,
    currency: CurrencyConfig,
    timezoneId: String
) {
    TimelineCard(
        title = stringResource(
            R.string.tx_details_amount_correction_event
        )
    ) {
        Text(
            stringResource(
                R.string.tx_details_before,
                MoneyMinorUnits(
                    event.previousAmountMinor
                ).formatDisplay(currency)
            )
        )

        Text(
            stringResource(
                R.string.tx_details_after,
                MoneyMinorUnits(
                    event.correctedAmountMinor
                ).formatDisplay(currency)
            ),
            fontWeight = FontWeight.Bold
        )

        TimelineReason(event.reason)

        TimelineActorOperation(
            event.actorUserId,
            event.operationId
        )

        TimelineTimestamp(
            event.occurredAt,
            timezoneId
        )
    }
}

@Composable
private fun PaymentMethodChangeTimelineCard(
    event: PaymentMethodChangeEventEntity,
    timezoneId: String
) {
    TimelineCard(
        title = stringResource(
            R.string.tx_details_payment_change_event
        )
    ) {
        Text(
            stringResource(
                R.string.tx_details_before,
                paymentMethodLabel(
                    event.previousMethod,
                    event.previousChannel
                )
            )
        )

        Text(
            stringResource(
                R.string.tx_details_after,
                paymentMethodLabel(
                    event.newMethod,
                    event.newChannel
                )
            ),
            fontWeight = FontWeight.Bold
        )

        TimelineReason(event.reason)

        TimelineActorOperation(
            event.actorUserId,
            event.operationId
        )

        TimelineTimestamp(
            event.occurredAt,
            timezoneId
        )
    }
}

@Composable
private fun RefundTimelineCard(
    event: RefundEventEntity,
    currency: CurrencyConfig,
    timezoneId: String
) {
    TimelineCard(
        title = stringResource(R.string.return_title)
    ) {
        Text(
            stringResource(
                R.string.tx_details_refund_amount,
                MoneyMinorUnits(
                    event.refundAmountMinor
                ).formatDisplay(currency)
            ),
            fontWeight = FontWeight.Bold
        )

        TimelineReason(event.reason)

        TimelineActorOperation(
            event.actorUserId,
            event.operationId
        )

        TimelineTimestamp(
            event.occurredAt,
            timezoneId
        )
    }
}

@Composable
private fun VoidTimelineCard(
    event: VoidEventEntity,
    timezoneId: String
) {
    TimelineCard(
        title = stringResource(R.string.audit_transaction_voided)
    ) {
        TimelineReason(event.reason)

        TimelineActorOperation(
            event.actorUserId,
            event.operationId
        )

        TimelineTimestamp(
            event.occurredAt,
            timezoneId
        )
    }
}

@Composable
private fun SettlementTimelineCard(
    settlement: SettlementEntryEntity,
    currency: CurrencyConfig,
    timezoneId: String
) {
    TimelineCard(
        title = stringResource(
            R.string.tx_details_settlement_event
        )
    ) {
        Text(
            stringResource(
                R.string.tx_details_settlement_amount,
                MoneyMinorUnits(
                    settlement.amountSignedMinor
                ).formatDisplay(currency)
            ),
            fontWeight = FontWeight.Bold
        )

        Text(
            stringResource(
                R.string.tx_details_settlement_purpose,
                settlementPurposeLabel(
                    settlement.entryPurpose
                )
            )
        )

        Text(
            paymentMethodLabel(
                settlement.category,
                settlement.channel
            )
        )

        Text(
            stringResource(
                R.string.tx_details_confirmation,
                confirmationLabel(
                    settlement.confirmationType
                )
            )
        )

        Text(
            stringResource(
                R.string.tx_details_actor,
                settlement.actorUserId
            )
        )

        Text(
            stringResource(
                R.string.tx_details_operation,
                settlement.operationId
            )
        )

        Text(
            stringResource(
                R.string.tx_details_settlement_id,
                settlement.id
            )
        )

        val providerRef = settlement.providerRef

        if (!providerRef.isNullOrBlank()) {
            Text(
                stringResource(
                    R.string.tx_details_provider_ref,
                    providerRef
                )
            )
        }

        TimelineTimestamp(
            settlement.occurredAt,
            timezoneId
        )
    }
}

@Composable
private fun TimelineCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold
            )

            content()
        }
    }
}

@Composable
private fun TimelineReason(
    reason: String?
) {
    if (!reason.isNullOrBlank()) {
        Text(
            stringResource(
                R.string.tx_details_reason,
                reason
            )
        )
    }
}

@Composable
private fun TimelineActorOperation(
    actorUserId: String,
    operationId: String
) {
    Text(
        stringResource(
            R.string.tx_details_actor,
            actorUserId
        )
    )

    Text(
        stringResource(
            R.string.tx_details_operation,
            operationId
        )
    )
}

@Composable
private fun TimelineTimestamp(
    occurredAt: Long,
    timezoneId: String
) {
    Text(
        text = formatTransactionTimestamp(
            occurredAt,
            timezoneId
        ),
        color =
            MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun simplePaymentLabel(
    category: PaymentCategory?
): String =
    when (category) {
        PaymentCategory.CASH ->
            "Cash"

        PaymentCategory.ONLINE ->
            "Online"

        PaymentCategory.CREDIT ->
            "Credit"

        PaymentCategory.COUPON ->
            "Coupon"

        null ->
            "Payment"
    }
@Composable
private fun TransactionDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TransactionActionChoice(
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Text(label)
    }
}

@Composable
private fun TransactionItemCard(
    item: TransactionItemEntity,
    currency: CurrencyConfig
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = item.productNameSnapshot,
                fontWeight = FontWeight.SemiBold
            )

            val description =
                item.descriptionSnapshot

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                stringResource(
                    R.string.tx_details_quantity,
                    item.quantity
                )
            )

            Text(
                stringResource(
                    R.string.tx_details_unit_price,
                    MoneyMinorUnits(
                        item.unitPriceMinor
                    ).formatDisplay(currency)
                )
            )

            Text(
                stringResource(
                    R.string.tx_details_line_total,
                    MoneyMinorUnits(
                        item.lineTotalMinor
                    ).formatDisplay(currency)
                )
            )

            if (item.itemPackingChargeMinor != 0L) {
                Text(
                    stringResource(
                        R.string.tx_details_item_packing,
                        MoneyMinorUnits(
                            item.itemPackingChargeMinor
                        ).formatDisplay(currency)
                    )
                )
            }

            if (item.itemShippingChargeMinor != 0L) {
                Text(
                    stringResource(
                        R.string.tx_details_item_shipping,
                        MoneyMinorUnits(
                            item.itemShippingChargeMinor
                        ).formatDisplay(currency)
                    )
                )
            }
        }
    }
}

@Composable
private fun lifecycleLabel(
    states: Set<FinancialLifecycleState>
): String {
    if (states.isEmpty()) {
        return stringResource(
            R.string.tx_details_active
        )
    }

    val corrected =
        stringResource(
            R.string.recent_status_corrected
        )

    val paymentChanged =
        stringResource(
            R.string.recent_status_payment_method_changed
        )

    val partiallyRefunded =
        stringResource(
            R.string.recent_status_partially_refunded
        )

    val fullyRefunded =
        stringResource(
            R.string.recent_status_fully_refunded
        )

    val voided =
        stringResource(
            R.string.recent_status_voided
        )

    val labels =
        mutableListOf<String>()

    for (state in states.sortedBy { it.ordinal }) {
        labels +=
            when (state) {
                FinancialLifecycleState.CORRECTED ->
                    corrected

                FinancialLifecycleState.PAYMENT_METHOD_CHANGED ->
                    paymentChanged

                FinancialLifecycleState.PARTIALLY_REFUNDED ->
                    partiallyRefunded

                FinancialLifecycleState.FULLY_REFUNDED ->
                    fullyRefunded

                FinancialLifecycleState.VOIDED ->
                    voided
            }
    }

    return labels.joinToString(" • ")
}

@Composable
private fun paymentMethodLabel(
    category: PaymentCategory?,
    channel: PaymentChannel?
): String {
    val categoryLabel =
        when (category) {
            PaymentCategory.CASH ->
                stringResource(
                    R.string.tx_payment_cash
                )

            PaymentCategory.ONLINE ->
                stringResource(
                    R.string.tx_payment_online
                )

            PaymentCategory.CREDIT ->
                stringResource(
                    R.string.tx_payment_credit
                )

            PaymentCategory.COUPON ->
                stringResource(
                    R.string.tx_payment_coupon
                )

            null -> null
        }

    val channelLabel =
        when (channel) {
            PaymentChannel.CASH_TENDER ->
                stringResource(
                    R.string.tx_channel_cash_tender
                )

            PaymentChannel.MANUAL_QR ->
                stringResource(
                    R.string.tx_channel_manual_qr
                )

            PaymentChannel.SOFTPOS ->
                stringResource(
                    R.string.tx_channel_softpos
                )

            PaymentChannel.COUPON_WALLET ->
                stringResource(
                    R.string.tx_channel_coupon_wallet
                )

            PaymentChannel.CREDIT_ACCOUNT ->
                stringResource(
                    R.string.tx_channel_credit_account
                )

            null -> null
        }

    if (
        categoryLabel == null &&
        channelLabel == null
    ) {
        return stringResource(
            R.string.tx_details_not_available
        )
    }

    if (
        categoryLabel != null &&
        channelLabel != null
    ) {
        return "$categoryLabel • $channelLabel"
    }

    return categoryLabel
        ?: channelLabel
        ?: stringResource(
            R.string.tx_details_not_available
        )
}

@Composable
private fun settlementPurposeLabel(
    purpose: EntryPurpose
): String {
    return when (purpose) {
        EntryPurpose.SALE_FUNDING ->
            stringResource(
                R.string.tx_settlement_sale_funding
            )

        EntryPurpose.CREDIT_REPAYMENT_COLLECTION ->
            stringResource(
                R.string.tx_settlement_credit_repayment
            )

        EntryPurpose.COUPON_PURCHASE_FUNDING ->
            stringResource(
                R.string.tx_settlement_coupon_purchase
            )

        EntryPurpose.REFUND_OUTFLOW ->
            stringResource(
                R.string.tx_settlement_refund_outflow
            )

        EntryPurpose.FUNDING_CORRECTION ->
            stringResource(
                R.string.tx_settlement_funding_correction
            )

        EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION ->
            stringResource(
                R.string.tx_settlement_payment_reclassification
            )

        EntryPurpose.VOID_REVERSAL ->
            stringResource(
                R.string.tx_settlement_void_reversal
            )

        EntryPurpose.EXPENSE_OUTFLOW ->
            "Expense payment"

        EntryPurpose.EXPENSE_CORRECTION ->
            "Expense correction"
    }
}

@Composable
private fun confirmationLabel(
    confirmation: ConfirmationType
): String {
    return when (confirmation) {
        ConfirmationType.ADMIN_CONFIRMED ->
            stringResource(
                R.string.tx_confirmation_cashier
            )

        ConfirmationType.PROVIDER_APPROVED ->
            stringResource(
                R.string.tx_confirmation_provider
            )
    }
}

private fun formatTransactionTimestamp(
    epochMillis: Long,
    timezoneId: String
): String {
    return Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneId.of(timezoneId))
        .format(
            DateTimeFormatter
                .ofLocalizedDateTime(
                    FormatStyle.MEDIUM
                )
                .withLocale(
                    Locale.getDefault()
                )
        )
}

