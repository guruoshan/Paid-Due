package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.enums.AuthorizedUserEventType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CouponAuthorizedUserCommand
import com.dailymoneyregister.feature.register.data.CouponRedemptionCommand
import com.dailymoneyregister.feature.register.data.CouponRedemptionResult
import com.dailymoneyregister.feature.register.data.CouponSaleCommand
import com.dailymoneyregister.feature.register.data.CouponSaleResult
import com.dailymoneyregister.feature.register.data.CouponTopUpCommand
import com.dailymoneyregister.feature.register.data.CouponTopUpResult
import com.dailymoneyregister.feature.register.data.OnlineSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.data.RegisterRepository.CouponWalletDetails
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns Coupon sale, redemption, top-up and wallet-authorisation UI workflows.
 *
 * RegisterViewModel remains the owner of RegisterUiState and financial-session
 * synchronization/generation. Customer search remains in
 * RegisterCustomerSelectionCoordinator. Repository and durable financial rules
 * remain in their existing owners.
 */
internal class RegisterCouponCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val currentSession: () -> ActiveSession?,
    private val synchronizeFinancialSession: (ActiveSession?) -> Boolean,
    private val financialContext: () -> RegisterFinancialSessionContext?,
    private val financialSessionGeneration: () -> Long,
    private val isFinancialSessionCurrent: (
        RegisterFinancialSessionContext?,
        ActiveSession?,
        Long,
        Long
    ) -> Boolean,
    private val decimalPlaces: () -> Int,
    private val currencyCode: () -> String,
    private val deviceId: () -> String,
    private val loadOnlineQr: suspend (String) -> MerchantQrSelection?,
    private val submitCouponSale: suspend (CouponSaleCommand) -> CommandResult<CouponSaleResult>,
    private val loadCouponWalletDetails: suspend (String, String) -> CouponWalletDetails?,
    private val submitCouponTopUp: suspend (CouponTopUpCommand) -> CommandResult<CouponTopUpResult>,
    private val submitCouponUser: suspend (CouponAuthorizedUserCommand) ->
        CommandResult<RegisterRepository.CouponAuthorizedUserDetails>,
    private val submitCouponRedemption: suspend (CouponRedemptionCommand) ->
        CommandResult<CouponRedemptionResult>
) {
    fun openCouponSale() = openCoupon(CouponDialogStartMode.ENTRY)

    fun openCouponRedemption() = openCoupon(CouponDialogStartMode.REDEMPTION)

private fun openCoupon(startMode: CouponDialogStartMode) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null || uiState.value.isExecuting) return
        if (uiState.value.lastCompletedTransaction != null) return
        uiState.value = uiState.value.copy(
            overlay = RegisterOverlay.Coupon(startMode),
            errorMessage = null,
            onlineQr = null
        )
        val generation = financialSessionGeneration()
        scope.launch {
            try {
                val qr = loadOnlineQr(session.branch.id)
                if (generation == financialSessionGeneration() && uiState.value.overlay is RegisterOverlay.Coupon &&
                    session.toRegisterFinancialSessionContext() == currentSession().toRegisterFinancialSessionContext()) {
                    uiState.value = uiState.value.copy(onlineQr = qr)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Cash remains available; an online purchase requires the configured QR.
            }
        }
    }

fun closeCouponSale() {
        val s = uiState.value

        if (
            s.overlay !is RegisterOverlay.Coupon ||
            s.isExecuting ||
            s.inFlightCouponSaleCommand != null ||
            s.inFlightCouponTopUpCommand != null ||
            s.inFlightCouponRedemptionCommand != null
        ) return

        uiState.value =
            s.copy(
                overlay = null,
                couponCustomers = emptyList(),
                couponSaleResult = null,
                couponTopUpResult = null,
                couponRedemptionResult = null,
                refundToCouponDraft = null,
                refundToCouponResult = null,
                inFlightRefundToCouponCommand = null,
                couponWalletDetails = null,
                couponWalletLoading = false,
                onlineQr = null,
                errorMessage = null
            )
    }

fun openCouponWallet(customerId: String, showDialog: Boolean = true) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null || uiState.value.couponWalletLoading) return
        uiState.value = uiState.value.copy(
            overlay = if (showDialog) RegisterOverlay.CouponWallet else uiState.value.overlay,
            couponWalletDetails = null,
            couponWalletLoading = true
        )
        val context = financialContext()
        val generation = financialSessionGeneration()
        scope.launch {
            try {
                val details = loadCouponWalletDetails(customerId, session.branch.currencyCode)
                if (isFinancialSessionCurrent(context, currentSession(),
                        generation, financialSessionGeneration())) {
                    uiState.value = uiState.value.copy(couponWalletDetails = details, couponWalletLoading = false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation == financialSessionGeneration()) uiState.value = uiState.value.copy(
                    couponWalletLoading = false, errorMessage = UiText.Dynamic(e.message ?: "Could not load coupon wallet"))
            }
        }
    }

fun closeCouponWallet() {
        if (uiState.value.isExecuting || uiState.value.inFlightCouponUserCommand != null) return
        uiState.value = uiState.value.copy(
            overlay = if (uiState.value.overlay == RegisterOverlay.CouponWallet) null else uiState.value.overlay,
            couponWalletDetails = null,
            couponWalletLoading = false
        )
    }

fun saveCouponUser(userId: String?, name: String, deactivate: Boolean = false) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null || uiState.value.isExecuting) return
        val s = uiState.value
        val details = s.couponWalletDetails ?: return
        val command = s.inFlightCouponUserCommand ?: CouponAuthorizedUserCommand(
            OperationId.generate(), session.business.id, session.branch.id, session.user.id,
            details.wallet.customerId, details.wallet.id, userId ?: IdGenerator.newId(), name.trim(),
            when {
                userId == null -> com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.ADDED
                deactivate -> com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.DEACTIVATED
                else -> com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.NAME_CORRECTED
            }, currencyCode(), deviceId(), System.currentTimeMillis())
        val context = financialContext()
        val generation = financialSessionGeneration()
        uiState.value = s.copy(isExecuting = true, inFlightCouponUserCommand = command, errorMessage = null)
        scope.launch {
            val result = try { submitCouponUser(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!isFinancialSessionCurrent(context, currentSession(),
                    generation, financialSessionGeneration())) return@launch
            when (result) {
                is CommandResult.Success -> {
                    uiState.value = uiState.value.copy(isExecuting = false, inFlightCouponUserCommand = null)
                    openCouponWallet(details.wallet.customerId, s.overlay == RegisterOverlay.CouponWallet)
                }
                is CommandResult.PermissionDenied -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponUserCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> uiState.value = uiState.value.copy(isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponUserCommand = if (result.exception is IllegalArgumentException) null else command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Authorization failed"))
            }
        }
    }

fun confirmCouponRedemption(customer: CustomerEntity?, userId: String?, amount: String, remainder: PaymentCategory?) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return
        val s = uiState.value
        if (s.isExecuting || s.overlay !is RegisterOverlay.Coupon || s.couponRedemptionResult != null) return
        val command = try {
            s.inFlightCouponRedemptionCommand ?: run {
                val details = requireNotNull(s.couponWalletDetails)
                require(customer?.id == details.wallet.customerId)
                val user = details.authorizedUsers.single { it.user.id == userId && it.isActive }
                val value = MoneyMinorUnits.parse(amount, decimalPlaces()).getOrThrow().amount
                val funding = if (value == s.draftState.grandTotal.amount) null else remainder
                require(funding != PaymentCategory.ONLINE || s.onlineQr != null) { "Configure the merchant QR first" }
                CouponRedemptionCommand(OnlineSaleCommand(OperationId.generate(), session.business.id,
                    session.branch.id, session.user.id, TransactionId.generate(), s.draftState, currencyCode(),
                    decimalPlaces(), deviceId(), System.currentTimeMillis()), details.wallet.customerId,
                    details.wallet.id, user.user.id, user.name, value, funding).also { it.validate() }
            }
        } catch (e: Exception) {
            uiState.value = s.copy(errorMessage = UiText.Dynamic(e.message ?: "Check coupon redemption details"))
            return
        }
        val context = financialContext()
        val generation = financialSessionGeneration()
        uiState.value = s.copy(isExecuting = true, inFlightCouponRedemptionCommand = command, errorMessage = null)
        scope.launch {
            val result = try { submitCouponRedemption(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!isFinancialSessionCurrent(context, currentSession(),
                    generation, financialSessionGeneration())) return@launch
            when (result) {
                is CommandResult.Success -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponRedemptionCommand = null, couponRedemptionResult = result.data,
                    lastCompletedTransaction = result.data.transaction)
                is CommandResult.PermissionDenied -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponRedemptionCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> uiState.value = uiState.value.copy(isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponRedemptionCommand = if (result.exception is IllegalArgumentException) null else command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Coupon redemption failed; retry the same request"))
            }
        }
    }

fun confirmCouponSale(customer: CustomerEntity?, name: String, phone: String, amount: String,
                          funding: PaymentCategory, additionalUsers: List<String>) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return
        val s = uiState.value
        if (s.overlay !is RegisterOverlay.Coupon || s.isExecuting || s.couponSaleResult != null) return
        if (funding == PaymentCategory.ONLINE && s.onlineQr == null) return
        val command = try {
            s.inFlightCouponSaleCommand ?: CouponSaleCommand(
                OperationId.generate(), session.business.id, session.branch.id, session.user.id,
                TransactionId.generate(), customer?.id ?: IdGenerator.newId(), customer?.name ?: name.trim(),
                customer?.canonicalPhone ?: phone.trim(), customer == null,
                MoneyMinorUnits.parse(amount, decimalPlaces()).getOrThrow().amount,
                currencyCode(), decimalPlaces(), funding, additionalUsers.map { it.trim() }.toList(),
                deviceId(), System.currentTimeMillis()).also { it.validate() }
        } catch (e: Exception) {
            uiState.value = s.copy(errorMessage = UiText.Dynamic(e.message ?: "Check Coupon Sale details"))
            return
        }
        val context = financialContext()
        val generation = financialSessionGeneration()
        uiState.value = s.copy(isExecuting = true, inFlightCouponSaleCommand = command, errorMessage = null)
        scope.launch {
            val result = try { submitCouponSale(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!isFinancialSessionCurrent(context, currentSession(),
                    generation, financialSessionGeneration())) return@launch
            when (result) {
                is CommandResult.Success -> {
                    // BOARDLOCK_COUPON_SALE_WALLET_REFRESH
                    val walletDetails =
                        try {
                            loadCouponWalletDetails(
                                command.customerId,
                                command.currencyCode
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

                    if (
                        !isFinancialSessionCurrent(
                            context,
                            currentSession(),
                            generation,
                            financialSessionGeneration()
                        )
                    ) {
                        return@launch
                    }

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            inFlightCouponSaleCommand = null,
                            couponSaleResult = result.data,
                            couponWalletDetails = walletDetails,
                            couponWalletLoading = false
                        )
                }
                is CommandResult.PermissionDenied -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponSaleCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> uiState.value = uiState.value.copy(isExecuting = false,
                    errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> uiState.value = uiState.value.copy(isExecuting = false,
                    inFlightCouponSaleCommand = if (result.exception is com.dailymoneyregister.feature.register.data.CouponSaleRejectedException) null else command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Coupon Sale failed. Retry the same purchase."))
            }
        }
    }

fun confirmCouponTopUp(customer: CustomerEntity, walletId: String, amount: String, funding: PaymentCategory) {
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return
        val s = uiState.value
        if (s.overlay !is RegisterOverlay.Coupon || s.isExecuting || s.couponSaleResult != null || s.couponTopUpResult != null) return
        if (funding == PaymentCategory.ONLINE && s.onlineQr == null) return
        val command = try {
            s.inFlightCouponTopUpCommand ?: CouponTopUpCommand(
                OperationId.generate(), session.business.id, session.branch.id, session.user.id,
                TransactionId.generate(), walletId, customer.id, customer.name, customer.canonicalPhone,
                MoneyMinorUnits.parse(amount, decimalPlaces()).getOrThrow().amount,
                currencyCode(), decimalPlaces(), funding, deviceId(), System.currentTimeMillis()
            ).also { it.validate() }
        } catch (e: Exception) {
            uiState.value = s.copy(errorMessage = UiText.Dynamic(e.message ?: "Check Coupon Top Up details"))
            return
        }
        val context = financialContext()
        val generation = financialSessionGeneration()
        uiState.value = s.copy(isExecuting = true, inFlightCouponTopUpCommand = command, errorMessage = null)
        scope.launch {
            val result = try { submitCouponTopUp(command) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
            if (!isFinancialSessionCurrent(context, currentSession(),
                    generation, financialSessionGeneration())) return@launch
            when (result) {
                is CommandResult.Success -> {
                    // BOARDLOCK_COUPON_TOPUP_WALLET_REFRESH
                    val walletDetails =
                        try {
                            loadCouponWalletDetails(
                                command.customerId,
                                command.currencyCode
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

                    if (
                        !isFinancialSessionCurrent(
                            context,
                            currentSession(),
                            generation,
                            financialSessionGeneration()
                        )
                    ) {
                        return@launch
                    }

                    uiState.value =
                        uiState.value.copy(
                            isExecuting = false,
                            inFlightCouponTopUpCommand = null,
                            couponTopUpResult = result.data,
                            couponWalletDetails = walletDetails,
                            couponWalletLoading = false
                        )
                }
                is CommandResult.PermissionDenied -> uiState.value = uiState.value.copy(
                    isExecuting = false, inFlightCouponTopUpCommand = null, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Conflict -> uiState.value = uiState.value.copy(
                    isExecuting = false, errorMessage = UiText.Dynamic(result.message))
                is CommandResult.Error -> uiState.value = uiState.value.copy(
                    isExecuting = false,
                    inFlightCouponTopUpCommand = command,
                    errorMessage = UiText.Dynamic(result.exception.message ?: "Coupon Top Up failed. Retry the same top up."))
            }
        }
    }
}
