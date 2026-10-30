package com.dailymoneyregister.feature.register.ui.transactiondetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.data.VoidBlockReason
import com.dailymoneyregister.feature.register.ui.CreditReturnUiRoute

@Composable
internal fun TransactionDetailsActions(
    details: TransactionDetailsReadModel,
    currency: CurrencyConfig,
    creditReturnRoute: CreditReturnUiRoute,
    canCorrectAmount: Boolean,
    canRefund: Boolean,
    canVoid: Boolean,
    canChangePayment: Boolean,
    actionInProgress: Boolean,
    actionError: String?,
    actionInfo: String?,
    onCorrectAmount: (String, String?) -> Unit,
    onRefund: (String, PaymentCategory, String?) -> Unit,
    onRefundToCoupon: (String, String?) -> Unit,
    onVoid: (String?) -> Unit,
    onChangePaymentMethod: (PaymentCategory, String?) -> Unit,
    showCorrectAmountDialog: MutableState<Boolean>,
    correctedAmountInput: MutableState<String>,
    correctionReasonInput: MutableState<String>,
    showRefundDialog: MutableState<Boolean>,
    refundAmountInput: MutableState<String>,
    refundReasonInput: MutableState<String>,
    refundDestination: MutableState<PaymentCategory>,
    showVoidDialog: MutableState<Boolean>,
    voidReasonInput: MutableState<String>,
    showChangePaymentDialog: MutableState<Boolean>,
    changePaymentReasonInput: MutableState<String>,
    changePaymentDestination: MutableState<PaymentCategory>
) {
    val transaction = details.financial
    val actionEligibility = details.actionEligibility

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
