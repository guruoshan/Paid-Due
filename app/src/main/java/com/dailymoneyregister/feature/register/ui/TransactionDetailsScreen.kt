package com.dailymoneyregister.feature.register.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.ui.transactiondetails.TransactionDetailsActions
import com.dailymoneyregister.feature.register.ui.transactiondetails.TransactionDetailsHeader
import com.dailymoneyregister.feature.register.ui.transactiondetails.TransactionDetailsItems
import com.dailymoneyregister.feature.register.ui.transactiondetails.TransactionDetailsTimeline

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
        TransactionDetailsHeader(
            details = details,
            currency = currency,
            timezoneId = validatedTimezoneId,
            onBack = onBack,
            showAuditDetails = showAuditDetails
        ) {
            TransactionDetailsActions(
                details = details,
                currency = currency,
                creditReturnRoute = creditReturnRoute,
                canCorrectAmount = canCorrectAmount,
                canRefund = canRefund,
                canVoid = canVoid,
                canChangePayment = canChangePayment,
                actionInProgress = actionInProgress,
                actionError = actionError,
                actionInfo = actionInfo,
                onCorrectAmount = onCorrectAmount,
                onRefund = onRefund,
                onRefundToCoupon = onRefundToCoupon,
                onVoid = onVoid,
                onChangePaymentMethod = onChangePaymentMethod,
                showCorrectAmountDialog = showCorrectAmountDialog,
                correctedAmountInput = correctedAmountInput,
                correctionReasonInput = correctionReasonInput,
                showRefundDialog = showRefundDialog,
                refundAmountInput = refundAmountInput,
                refundReasonInput = refundReasonInput,
                refundDestination = refundDestination,
                showVoidDialog = showVoidDialog,
                voidReasonInput = voidReasonInput,
                showChangePaymentDialog = showChangePaymentDialog,
                changePaymentReasonInput = changePaymentReasonInput,
                changePaymentDestination = changePaymentDestination
            )
        }

        TransactionDetailsItems(
            details = details,
            currency = currency
        )

        TransactionDetailsTimeline(
            details = details,
            currency = currency,
            timezoneId = validatedTimezoneId
        )
    }
}
