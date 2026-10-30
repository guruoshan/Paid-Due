package com.dailymoneyregister.feature.register.ui.transactiondetails

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


@Composable
internal fun lifecycleLabel(
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
internal fun paymentMethodLabel(
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
internal fun settlementPurposeLabel(
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
internal fun confirmationLabel(
    confirmation: ConfirmationType
): String {
    return when (confirmation) {
        ConfirmationType.CASHIER_CONFIRMED ->
            stringResource(
                R.string.tx_confirmation_cashier
            )

        ConfirmationType.PROVIDER_APPROVED ->
            stringResource(
                R.string.tx_confirmation_provider
            )
    }
}


internal fun formatTransactionTimestamp(
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
