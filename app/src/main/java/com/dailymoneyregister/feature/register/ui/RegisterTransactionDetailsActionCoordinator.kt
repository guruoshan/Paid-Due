package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.R
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.AmountCorrectionCommand
import com.dailymoneyregister.feature.register.data.AmountCorrectionExecutionResult
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeCommand
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeExecutionResult
import com.dailymoneyregister.feature.register.data.RefundCommand
import com.dailymoneyregister.feature.register.data.RefundExecutionResult
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.data.VoidCommand
import com.dailymoneyregister.feature.register.data.VoidExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns financial actions initiated from Transaction Details.
 *
 * Existing validation, command construction, retry identity, result handling,
 * and financial-session guards are preserved. Shared session state and
 * generation ownership remain in RegisterViewModel.
 */
internal class RegisterTransactionDetailsActionCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val currentSession: () -> ActiveSession?,
    private val decimalPlaces: () -> Int,
    private val deviceId: () -> String,
    private val financialContext: () -> RegisterFinancialSessionContext?,
    private val financialSessionGeneration: () -> Long,
    private val synchronizeFinancialSession: (ActiveSession?) -> Boolean,
    private val isFinancialSessionCurrent: (RegisterFinancialSessionContext?, ActiveSession?, Long, Long) -> Boolean,
    private val loadTransactionDetails: suspend (String) -> TransactionDetailsReadModel?,
    private val loadCouponWalletDetails: suspend (String, String) -> com.dailymoneyregister.feature.register.data.RegisterRepository.CouponWalletDetails?,
    private val currencyCode: () -> String,
    private val submitAmountCorrection: suspend (AmountCorrectionCommand) -> CommandResult<AmountCorrectionExecutionResult>,
    private val submitRefund: suspend (RefundCommand) -> CommandResult<RefundExecutionResult>,
    private val submitVoid: suspend (VoidCommand) -> CommandResult<VoidExecutionResult>,
    private val submitPaymentMethodChange: suspend (PaymentMethodChangeCommand) -> CommandResult<PaymentMethodChangeExecutionResult>
) {
    fun executeAmountCorrectionFromDetails(
        correctedAmountInput: String,
        reason: String?
    ) {
        val session =
            currentSession()

        if (
            synchronizeFinancialSession(session) ||
            session == null
        ) {
            return
        }

        val state = uiState.value
        val details = state.transactionDetails ?: return

        if (state.isExecuting) {
            return
        }

        val corrected =
            MoneyMinorUnits
                .parse(
                    correctedAmountInput,
                    decimalPlaces()
                )
                .getOrNull()

        if (
            corrected == null ||
            corrected.amount < 0L ||
            corrected.amount <
                details.financial.refundedAmount.amount
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_correct_amount_invalid
                        )
                )
            return
        }

        if (
            corrected.amount ==
                details.financial.currentAmount.amount
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_correct_amount_same
                        )
                )
            return
        }

        val latest =
            details.financial.latestActivityAt

        val occurredAt =
            if (latest < Long.MAX_VALUE) {
                maxOf(
                    System.currentTimeMillis(),
                    latest + 1L
                )
            } else {
                System.currentTimeMillis()
            }

        val command =
            AmountCorrectionCommand(
                operationId =
                    OperationId.generate(),
                businessId =
                    session.business.id,
                branchId =
                    session.branch.id,
                subjectId =
                    details.financial.transaction.id,
                actorUserId =
                    session.user.id,
                deviceId =
                    deviceId(),
                occurredAt =
                    occurredAt,
                correctedAmountMinor =
                    corrected.amount,
                reason =
                    reason
                        ?.trim()
                        ?.takeIf {
                            it.isNotEmpty()
                        }
            )

        val context =
            financialContext()

        val generation =
            financialSessionGeneration()

        uiState.value =
            state.copy(
                isExecuting = true,
                errorMessage = null,
                infoMessage = null
            )

        scope.launch {

            val result =
                try {
                    submitAmountCorrection(command)
                } catch (
                    error: Exception
                ) {
                    if (
                        error is
                            kotlinx.coroutines.CancellationException
                    ) {
                        throw error
                    }

                    CommandResult.Error(error)
                }

            if (
                !isFinancialSessionCurrent(context, currentSession(), generation, financialSessionGeneration())
            ) {
                return@launch
            }

            when (result) {

                is CommandResult.Success -> {

                    val refreshed =
                        try {
                            loadTransactionDetails(
                                command.subjectId
                            )
                        } catch (
                            error: Exception
                        ) {
                            if (
                                error is
                                    kotlinx.coroutines.CancellationException
                            ) {
                                throw error
                            }

                            null
                        }

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            transactionDetails = refreshed,
                            errorMessage = null,
                            infoMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_saved
                                )
                        )
                }

                is CommandResult.PermissionDenied -> {
                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_permission
                                )
                        )
                }

                is CommandResult.Conflict -> {
                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_conflict
                                )
                        )
                }

                is CommandResult.Error -> {
                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_correct_amount_failed
                                )
                        )
                }
            }
        }
    }
    fun beginRefundToCouponFromDetails(
        amountInput: String,
        reason: String?
    ) {
        val session =
            currentSession()

        if (
            synchronizeFinancialSession(session) ||
            session == null
        ) return

        val state =
            uiState.value

        val details =
            state.transactionDetails
                ?: return

        if (state.isExecuting) return

        val amount =
            MoneyMinorUnits
                .parse(
                    amountInput,
                    decimalPlaces()
                )
                .getOrNull()

        if (
            amount == null ||
            !amount.isPositive ||
            amount >
            details.financial.netAmount
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_refund_invalid
                        )
                )

            return
        }
uiState.value =
            state.copy(
                overlay = RegisterOverlay.Coupon(CouponDialogStartMode.ENTRY),
                couponCustomers = emptyList(),
                couponSearching = false,
                couponSaleResult = null,
                couponTopUpResult = null,
                couponRedemptionResult = null,
                refundToCouponDraft =
                    RefundToCouponDraft(
                        transactionId =
                            details.financial.transaction.id,
                        amountInput =
                            amountInput.trim(),
                        amountMinor =
                            amount.amount,
                        reason =
                            normalizedReason(reason)
                    ),
                inFlightRefundToCouponCommand = null,
                refundToCouponResult = null,
                couponWalletDetails = null,
                couponWalletLoading = false,
                errorMessage = null,
                infoMessage = null,
                onlineQr = null
            )
    }

    fun confirmRefundToCoupon(
        customer: CustomerEntity?,
        name: String,
        phone: String,
        additionalUsers: List<String>
    ) {
        val session =
            currentSession()

        if (
            synchronizeFinancialSession(session) ||
            session == null
        ) return

        val state =
            uiState.value

        val draft =
            state.refundToCouponDraft
                ?: return

        val details =
            state.transactionDetails
                ?: return

        if (
            state.overlay !is RegisterOverlay.Coupon ||
            state.isExecuting ||
            state.refundToCouponResult != null
        ) return

        if (
            details.financial.transaction.id !=
            draft.transactionId
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "Refund transaction changed. Reopen Refund."
                        )
                )

            return
        }
val destination =
            try {
                com.dailymoneyregister.feature.register.data
                    .RefundCouponDestination(
                        customerId =
                            customer?.id
                                ?: IdGenerator.newId(),
                        customerName =
                            customer?.name
                                ?: name.trim(),
                        canonicalPhone =
                            customer?.canonicalPhone
                                ?: phone.trim(),
                        createCustomer =
                            customer == null,
                        additionalUserNames =
                            additionalUsers
                                .map {
                                    it.trim()
                                }
                                .filter {
                                    it.isNotBlank()
                                }
                                .distinct()
                    )
            } catch (error: Exception) {
                uiState.value =
                    state.copy(
                        errorMessage =
                            UiText.Dynamic(
                                error.message
                                    ?: "Check Refund Coupon customer"
                            )
                    )

                return
            }

        val command =
            state.inFlightRefundToCouponCommand
                ?: RefundCommand(
                    operationId =
                        OperationId.generate(),
                    businessId =
                        session.business.id,
                    branchId =
                        session.branch.id,
                    subjectId =
                        draft.transactionId,
                    actorUserId =
                        session.user.id,
                    deviceId =
                        deviceId(),
                    occurredAt =
                        nextDetailsActionTime(
                            details
                        ),
                    refundAmountMinor =
                        draft.amountMinor,
                    destinationCategory =
                        PaymentCategory.COUPON,
                    destinationChannel =
                        PaymentChannel.COUPON_WALLET,
                    reason =
                        draft.reason,
                    couponDestination =
                        destination
                )

        val context =
            financialContext()

        val generation =
            financialSessionGeneration()

        uiState.value =
            state.copy(
                isExecuting = true,
                inFlightRefundToCouponCommand =
                    command,
                errorMessage = null,
                infoMessage = null
            )

        scope.launch {

            val result =
                try {
                    submitRefund(command)
                } catch (error: Exception) {
                    if (
                        error is
                        kotlinx.coroutines
                            .CancellationException
                    ) throw error

                    CommandResult.Error(error)
                }

            if (
                !isFinancialSessionCurrent(context, currentSession(), generation, financialSessionGeneration())
            ) return@launch

            when (result) {

                is CommandResult.Success -> {

                    val refreshedDetails =
                        try {
                            loadTransactionDetails(
                                draft.transactionId
                            )
                        } catch (error: Exception) {
                            if (
                                error is
                                kotlinx.coroutines
                                    .CancellationException
                            ) throw error

                            null
                        }

                    val refreshedWallet =
                        try {
                            loadCouponWalletDetails(
                                destination.customerId,
                                currencyCode()
                            )
                        } catch (error: Exception) {
                            if (
                                error is
                                kotlinx.coroutines
                                    .CancellationException
                            ) throw error

                            null
                        }

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            inFlightRefundToCouponCommand = null,
                            refundToCouponResult =
                                result.data,
                            transactionDetails =
                                refreshedDetails
                                    ?: uiState.value
                                        .transactionDetails,
                            couponWalletDetails =
                                refreshedWallet,
                            couponWalletLoading = false,
                            errorMessage = null,
                            infoMessage =
                                UiText.Resource(
                                    R.string.tx_refund_saved
                                )
                        )
                }

                is CommandResult.PermissionDenied -> {

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            inFlightRefundToCouponCommand = null,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_refund_permission
                                )
                        )
                }

                is CommandResult.Conflict -> {

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            errorMessage =
                                UiText.Resource(
                                    R.string.tx_lifecycle_conflict
                                )
                        )
                }

                is CommandResult.Error -> {

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            inFlightRefundToCouponCommand =
                                command,
                            errorMessage =
                                UiText.Dynamic(
                                    result.exception.message
                                        ?: "Refund to Coupon failed; retry the same request"
                                )
                        )
                }
            }
        }
    }

    fun executeRefundFromDetails(
        amountInput: String,
        destination: PaymentCategory,
        reason: String?
    ) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return

        val state = uiState.value
        val details = state.transactionDetails ?: return
        if (state.isExecuting) return

        val amount = MoneyMinorUnits.parse(amountInput, decimalPlaces()).getOrNull()
        if (amount == null || !amount.isPositive || amount > details.financial.netAmount) {
            uiState.value = state.copy(
                errorMessage = UiText.Resource(R.string.tx_refund_invalid)
            )
            return
        }

        val creditReturnRoute =
            creditReturnUiRoute(
                paymentCategory =
                    details.financial.currentPaymentCategory,
                paymentChannel =
                    details.financial.currentPaymentChannel,
                creditRepaymentAllocationCount =
                    details.creditRepaymentAllocations.size
            )

        if (
            creditReturnRoute ==
                CreditReturnUiRoute.INVALID_CREDIT
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Dynamic(
                            "This Credit transaction cannot be returned because its payment channel does not reconcile with the authoritative Credit account."
                        )
                )

            return
        }
if (
            creditReturnRoute ==
                CreditReturnUiRoute.NON_CREDIT &&
            destination ==
                PaymentCategory.CREDIT
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_refund_invalid_destination
                        )
                )

            return
        }

        val channel =
            when (destination) {
                PaymentCategory.COUPON ->
                    PaymentChannel.COUPON_WALLET

                PaymentCategory.CASH ->
                    PaymentChannel.CASH_TENDER

                PaymentCategory.ONLINE ->
                    details.financial.currentPaymentChannel
                        ?.takeIf {
                            it ==
                                PaymentChannel.MANUAL_QR ||
                            it ==
                                PaymentChannel.SOFTPOS
                        }
                        ?: PaymentChannel.MANUAL_QR

                PaymentCategory.CREDIT ->
                    PaymentChannel.CREDIT_ACCOUNT
            }

        if (
            creditReturnRoute ==
                CreditReturnUiRoute.NON_CREDIT &&
            destination !=
                PaymentCategory.COUPON &&
            destination !=
                details.financial.currentPaymentCategory
        ) {
            uiState.value =
                state.copy(
                    errorMessage =
                        UiText.Resource(
                            R.string.tx_refund_invalid_destination
                        )
                )

            return
        }

        val command = RefundCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            subjectId = details.financial.transaction.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = nextDetailsActionTime(details),
            refundAmountMinor = amount.amount,
            destinationCategory = destination,
            destinationChannel = channel,
            reason = normalizedReason(reason)
        )
        executeDetailsCommand(
            subjectId = command.subjectId,
            successMessage = R.string.tx_refund_saved,
            permissionMessage = R.string.tx_refund_permission,
            conflictMessage = R.string.tx_lifecycle_conflict,
            failureMessage = R.string.tx_refund_failed
        ) { submitRefund(command) }
    }

    fun executeVoidFromDetails(reason: String?) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return

        val state = uiState.value
        val details = state.transactionDetails ?: return
        if (state.isExecuting) return

        val command = VoidCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            subjectId = details.financial.transaction.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = nextDetailsActionTime(details),
            reason = normalizedReason(reason)
        )
        executeDetailsCommand(
            subjectId = command.subjectId,
            successMessage = R.string.tx_void_saved,
            permissionMessage = R.string.tx_void_permission,
            conflictMessage = R.string.tx_lifecycle_conflict,
            failureMessage = R.string.tx_void_failed
        ) { submitVoid(command) }
    }

    fun executePaymentMethodChangeFromDetails(
        destination: PaymentCategory,
        reason: String?
    ) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return

        val state = uiState.value
        val details = state.transactionDetails ?: return
        if (state.isExecuting) return

        val channel = when (destination) {
            PaymentCategory.CASH -> PaymentChannel.CASH_TENDER
            PaymentCategory.ONLINE -> PaymentChannel.MANUAL_QR
            else -> {
                uiState.value = state.copy(
                    errorMessage = UiText.Resource(R.string.tx_change_payment_invalid)
                )
                return
            }
        }
        if (destination == details.financial.currentPaymentCategory) {
            uiState.value = state.copy(
                errorMessage = UiText.Resource(R.string.tx_change_payment_same)
            )
            return
        }

        val command = PaymentMethodChangeCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = session.branch.id,
            subjectId = details.financial.transaction.id,
            actorUserId = session.user.id,
            deviceId = deviceId(),
            occurredAt = nextDetailsActionTime(details),
            newCategory = destination,
            newChannel = channel,
            reason = normalizedReason(reason)
        )
        executeDetailsCommand(
            subjectId = command.subjectId,
            successMessage = R.string.tx_change_payment_saved,
            permissionMessage = R.string.tx_change_payment_permission,
            conflictMessage = R.string.tx_lifecycle_conflict,
            failureMessage = R.string.tx_change_payment_failed
        ) { submitPaymentMethodChange(command) }
    }

    private fun nextDetailsActionTime(details: TransactionDetailsReadModel): Long =
        if (details.financial.latestActivityAt < Long.MAX_VALUE) {
            maxOf(System.currentTimeMillis(), details.financial.latestActivityAt + 1L)
        } else {
            System.currentTimeMillis()
        }

    private fun normalizedReason(reason: String?): String? =
        reason?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> executeDetailsCommand(
        subjectId: String,
        successMessage: Int,
        permissionMessage: Int,
        conflictMessage: Int,
        failureMessage: Int,
        onSuccess: (T) -> Unit = {},
        submit: suspend () -> CommandResult<T>
    ) {
        val context = financialContext()
        val generation = financialSessionGeneration()
        uiState.value = uiState.value.copy(
            isExecuting = true,
            errorMessage = null,
            infoMessage = null
        )

        scope.launch {
            val result = try {
                submit()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                CommandResult.Error(error)
            }
            if (!isFinancialSessionCurrent(context, currentSession(), generation, financialSessionGeneration())
            ) return@launch

            when (result) {
                is CommandResult.Success -> {
                    val refreshed = try {
                        loadTransactionDetails(subjectId)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        null
                    }
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        transactionDetails = refreshed ?: uiState.value.transactionDetails,
                        errorMessage = null,
                        infoMessage = UiText.Resource(successMessage)
                    )

                    onSuccess(result.data)
                }
                is CommandResult.PermissionDenied -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Resource(permissionMessage)
                    )
                }
                is CommandResult.Conflict -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = UiText.Resource(conflictMessage)
                    )
                }
                is CommandResult.Error -> {
                    uiState.value = uiState.value.copy(
                        isExecuting = false,
                        errorMessage = result.exception.message?.let { UiText.Dynamic(it) } ?: UiText.Resource(failureMessage)
                    )
                }
            }
        }
    }


}
