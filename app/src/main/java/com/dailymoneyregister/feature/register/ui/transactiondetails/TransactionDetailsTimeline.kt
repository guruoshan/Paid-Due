package com.dailymoneyregister.feature.register.ui.transactiondetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.ui.sortTransactionDetailsTimeline

import com.dailymoneyregister.feature.register.ui.transactiondetails.paymentMethodLabel
import com.dailymoneyregister.feature.register.ui.transactiondetails.settlementPurposeLabel
import com.dailymoneyregister.feature.register.ui.transactiondetails.confirmationLabel
import com.dailymoneyregister.feature.register.ui.transactiondetails.formatTransactionTimestamp

@Composable
internal fun OriginalTransactionTimelineCard(
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
internal fun CreditRepaymentTimelineCard(
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
internal fun AmountCorrectionTimelineCard(
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
internal fun PaymentMethodChangeTimelineCard(
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
internal fun RefundTimelineCard(
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
internal fun VoidTimelineCard(
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
internal fun SettlementTimelineCard(
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


internal fun simplePaymentLabel(
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
internal fun TransactionDetailsTimeline(
    details: TransactionDetailsReadModel,
    currency: CurrencyConfig,
    timezoneId: String
) {
    val transaction = details.financial

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
