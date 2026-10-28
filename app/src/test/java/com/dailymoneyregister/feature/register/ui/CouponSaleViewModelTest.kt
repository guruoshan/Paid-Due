package com.dailymoneyregister.feature.register.ui

import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.R
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.*
import com.dailymoneyregister.core.model.enums.*
import com.dailymoneyregister.core.security.*
import com.dailymoneyregister.feature.register.data.*
import com.dailymoneyregister.feature.register.domain.KeypadKey
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CouponSaleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val business = BusinessEntity("biz", "Business", 0, 0)
    private val branch = BranchEntity("branch", business.id, "Main", "MAIN", "OM", "OMR", 3,
        "OMR", "Asia/Muscat", createdAt = 0, updatedAt = 0)
    private val user = UserAccountEntity(id = "user", businessId = business.id, displayName = "Cashier",
        username = "cashier", role = UserRole.ADMIN, authType = AuthType.PASSWORD,
        credentialHash = "hash", credentialSalt = "salt", createdAt = 0, updatedAt = 0)
    private lateinit var vm: RegisterViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        SessionManager.setSession(ActiveSession(business, branch, user, null))
    }
    @After fun cleanup() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        SessionManager.clearSession()
        Dispatchers.resetMain()
    }
    private fun model(
        findCustomers: suspend (String) -> List<CouponSaleCustomerWallet> = { emptyList() },
        loadWallet: suspend (String, String) -> RegisterRepository.CouponWalletDetails? = { _, _ -> null },
        submitTopUp: suspend (CouponTopUpCommand) -> CommandResult<CouponTopUpResult> = { error("Top Up must not run") },
        submitRedemption: suspend (CouponRedemptionCommand) -> CommandResult<CouponRedemptionResult> = { error("Redemption must not run") },
        loadQr: suspend (String) -> MerchantQrSelection? = { null },
        submit: suspend (CouponSaleCommand) -> CommandResult<CouponSaleResult>
    ) = com.dailymoneyregister.feature.register.ui.testRegisterViewModel(
        observeToday = { _, _ -> flowOf(emptyList()) }, executeSale = { _, _, _ -> error("Ordinary sale must not run") },
        deviceId = { "device" }, submitCouponSale = submit, findCouponCustomers = findCustomers,
        loadCouponWalletDetails = loadWallet, submitCouponTopUp = submitTopUp,
        submitCouponRedemption = submitRedemption, loadOnlineQr = loadQr).also { vm = it }
    private fun submit() = vm.confirmCouponSale(null, "Customer", "+96891234567", "5.000", PaymentCategory.CASH, emptyList())

    @Test fun duplicateTapsDoNotSubmitTwiceAndUncertainRetryKeepsIdentity() = runTest(dispatcher) {
        val calls = mutableListOf<CouponSaleCommand>()
        val completion = CompletableDeferred<CommandResult<CouponSaleResult>>()
        model { calls += it; completion.await() }
        vm.openCouponSale()
        submit(); submit()
        runCurrent()
        assertEquals(1, calls.size)
        val original = calls.single()
        vm.closeCouponSale()
        assertTrue(vm.uiState.value.overlay is RegisterOverlay.Coupon)
        completion.complete(CommandResult.Error(IllegalStateException("Connection interrupted")))
        runCurrent()
        assertEquals(original, vm.uiState.value.inFlightCouponSaleCommand)
        submit()
        runCurrent()
        assertEquals(listOf(original, original), calls)
    }
    @Test fun sessionChangeClearsCouponIntentAndIgnoresOldCompletion() = runTest(dispatcher) {
        val completion = CompletableDeferred<CommandResult<CouponSaleResult>>()
        model { completion.await() }
        vm.openCouponSale(); submit(); runCurrent()
        SessionManager.clearSession()
        runCurrent()
        completion.complete(CommandResult.Error(IllegalStateException("Old session failure")))
        runCurrent()
        assertNull(vm.uiState.value.overlay)
        assertNull(vm.uiState.value.inFlightCouponSaleCommand)
        assertNull(vm.uiState.value.errorMessage)
    }
    @Test fun provenRejectionUnlocksFormAndCancelPreservesRegisterDraft() = runTest(dispatcher) {
        model { CommandResult.Error(CouponSaleRejectedException("Invalid phone")) }
        vm.onManualDescriptionChanged("Existing bill")
        val draft = vm.uiState.value.draftState
        vm.openCouponSale(); submit(); runCurrent()
        assertNull(vm.uiState.value.inFlightCouponSaleCommand)
        vm.closeCouponSale()
        assertNull(vm.uiState.value.overlay)
        assertEquals(draft, vm.uiState.value.draftState)
    }
    @Test fun missingQrPreventsOnlineConfirmation() = runTest(dispatcher) {
        var calls = 0
        model { calls++; error("Must not submit") }
        vm.openCouponSale(); runCurrent()
        vm.confirmCouponSale(null, "Customer", "+96891234567", "5", PaymentCategory.ONLINE, emptyList())
        runCurrent()
        assertEquals(0, calls)
    }

    @Test fun customerSearchPreservesCouponWalletSummary() = runTest(dispatcher) {
        val customer = CustomerEntity("customer", business.id, "+96891234567", "Customer", 0, 0)
        val summary = CouponSaleCustomerWallet(
            customer,
            CouponWalletEntity("wallet", customer.id, "OMR", isActive = true, createdAt = 1),
            balanceMinor = 2750
        )
        model(
            submit = { error("Sale must not run during customer search") },
            findCustomers = { listOf(summary) }
        )

        vm.openCouponSale()
        vm.searchCouponCustomers("Customer")
        runCurrent()

        assertEquals(listOf(summary), vm.uiState.value.couponCustomers)
    }

    @Test fun activeSaleShowsUseCouponActionWithoutCustomerWalletSelection() = runTest(dispatcher) {
        model { error("Coupon sale must not run") }

        "6".forEach { vm.onKeypadPress(KeypadKey.Digit(it)) }
        val action = couponPaymentActionUi(vm.uiState.value)

        assertTrue(action.visible)
        assertTrue(action.enabled)
        assertEquals(R.string.coupon_use_action, action.labelRes)
        assertEquals(CouponDialogStartMode.REDEMPTION, action.dialogStartMode)
        assertFalse(action.dialogStartMode.opensSellCouponDialog)
        assertEquals(R.string.coupon_use_action, action.dialogStartMode.dialogTitleRes)
        assertEquals(R.string.coupon_use_action, action.dialogStartMode.confirmLabelRes)
    }

    @Test fun couponDialogStartModeDistinguishesRedemptionFromSellTopUpEntry() {
        assertTrue(CouponDialogStartMode.REDEMPTION.startsWithRedemption)
        assertFalse(CouponDialogStartMode.ENTRY.startsWithRedemption)
    }

    @Test fun activeSaleRendersOnlyCouponPaymentAction() = runTest(dispatcher) {
        model { error("Coupon sale must not run") }

        "6".forEach { vm.onKeypadPress(KeypadKey.Digit(it)) }
        val registerActions = registerScreenCouponActions(vm.uiState.value)
        val paymentAction = registerActions.single()

        assertEquals(RegisterCouponActionRoute.PAYMENT_COUPON, paymentAction.route)
        assertTrue(paymentAction.visible)
        assertEquals(R.string.coupon_use_action, paymentAction.labelRes)
        assertEquals(CouponDialogStartMode.REDEMPTION, paymentAction.dialogStartMode)
        assertFalse(paymentAction.dialogStartMode.opensSellCouponDialog)
    }

    @Test fun emptySaleKeepsCouponSellTopUpEntryAvailable() = runTest(dispatcher) {
        model { error("Coupon sale must not run") }

        val action = couponPaymentActionUi(vm.uiState.value)

        assertTrue(action.visible)
        assertTrue(action.enabled)
        assertEquals(R.string.coupon, action.labelRes)
        assertEquals(CouponDialogStartMode.ENTRY, action.dialogStartMode)
        assertTrue(action.dialogStartMode.opensSellCouponDialog)
        assertEquals(R.string.coupon_purchase_title, action.dialogStartMode.dialogTitleRes)
        assertEquals(R.string.coupon_purchase_confirm, action.dialogStartMode.confirmLabelRes)
    }

    @Test fun existingWalletTopUpStillUsesSameWalletAndAuthoritativeResult() = runTest(dispatcher) {
        val customer = CustomerEntity("customer", business.id, "+96891234567", "Customer", 0, 0)
        val wallet = CouponWalletEntity("wallet", customer.id, "OMR", isActive = true, createdAt = 1)
        val submitted = mutableListOf<CouponTopUpCommand>()
        model(
            submitTopUp = { command ->
                submitted += command
                assertEquals(customer.id, command.customerId)
                assertEquals(wallet.id, command.walletId)
                assertEquals(PaymentCategory.CASH, command.fundingCategory)
                CommandResult.Success(CouponTopUpResult(topUpTransaction(command), wallet.id, command.amountMinor))
            },
            submit = { error("Coupon sale must not run") }
        )
        vm.openCouponSale()
        vm.confirmCouponTopUp(customer, wallet.id, "1.000", PaymentCategory.CASH)
        runCurrent()

        assertEquals(1, submitted.size)
        assertEquals(wallet.id, vm.uiState.value.couponTopUpResult?.walletId)
        assertEquals(1000L, vm.uiState.value.couponTopUpResult?.amountMinor)
        assertNull(vm.uiState.value.lastCompletedTransaction)
    }

    private fun walletDetails(
        customer: CustomerEntity,
        wallet: CouponWalletEntity,
        balance: Long,
        user: CouponAuthorizedUserEntity = CouponAuthorizedUserEntity("auth", wallet.id, true, true, 1),
        event: CouponAuthorizedUserEventEntity = CouponAuthorizedUserEventEntity(
            "event", "auth", "op", AuthorizedUserEventType.ADDED, "Customer", 1, this.user.id
        )
    ) = RegisterRepository.CouponWalletDetails(
        wallet = wallet,
        balanceMinor = balance,
        ledgerEvents = emptyList(),
        authorizedUsers = listOf(RegisterRepository.CouponAuthorizedUserDetails(user, listOf(event))),
        customer = customer
    )

    private fun topUpTransaction(command: CouponTopUpCommand) = TransactionEntity(
        id = command.transactionIdVal.value,
        branchId = command.branchId,
        type = TransactionType.COUPON_PURCHASE,
        customerId = command.customerId,
        subtotalMinor = command.amountMinor,
        taxRateBps = 0,
        taxableBaseMinor = command.amountMinor,
        taxAmountMinor = 0,
        txShippingMinor = 0,
        txPackingMinor = 0,
        grandTotalMinor = command.amountMinor,
        currencyCode = command.currencyCode,
        currencyDecimalPlaces = command.currencyDecimalPlaces,
        createdByUserId = command.actorUserId,
        deviceId = command.deviceId,
        createdAt = command.occurredAt,
        description = "Coupon Top Up",
        displayRef = "CPN-test"
    )

    private fun topUpTransaction(command: OnlineSaleCommand) = TransactionEntity(
        id = command.transactionIdVal.value,
        branchId = command.branchId,
        type = TransactionType.SALE,
        customerId = null,
        subtotalMinor = command.draftState.subtotal.amount,
        taxRateBps = 0,
        taxableBaseMinor = command.draftState.subtotal.amount,
        taxAmountMinor = 0,
        txShippingMinor = 0,
        txPackingMinor = 0,
        grandTotalMinor = command.draftState.grandTotal.amount,
        currencyCode = command.currencyCode,
        currencyDecimalPlaces = command.currencyDecimalPlaces,
        createdByUserId = command.actorUserId,
        deviceId = command.deviceId,
        createdAt = command.occurredAt,
        displayRef = "SAL-test"
    )

    private fun qrSelection() = MerchantQrSelection(
        config = MerchantQrConfigEntity(
            id = "qr-config",
            businessId = business.id,
            branchId = branch.id,
            displayLabel = "QR",
            bankAppName = "Bank",
            mediaId = "qr-media",
            isDefault = true,
            isActive = true,
            createdAt = 1
        ),
        media = MediaAssetEntity(
            id = "qr-media",
            businessId = business.id,
            branchId = branch.id,
            mediaType = MediaType.MERCHANT_QR,
            localReference = "qr.png",
            createdAt = 1
        )
    )
}
