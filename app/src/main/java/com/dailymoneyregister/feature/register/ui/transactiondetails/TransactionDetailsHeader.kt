package com.dailymoneyregister.feature.register.ui.transactiondetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel

@Composable
internal fun TransactionDetailsHeader(
    details: TransactionDetailsReadModel,
    currency: CurrencyConfig,
    timezoneId: String,
    onBack: () -> Unit,
    showAuditDetails: MutableState<Boolean>,
    actionsContent: @Composable () -> Unit
) {
    val transaction = details.financial

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
                        timezoneId
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
        actionsContent()
        }
        }
}
