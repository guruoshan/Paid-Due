package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.AuthorizedUserEventType
import com.dailymoneyregister.core.model.enums.MediaType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
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
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterCouponCoordinatorTest {
    @Test
    fun \`open and close preserve the selected coupon entry mode\`() = runTest {
        val qr = qrSelection()
        val state = MutableStateFlow(RegisterUiState())
        val coordinator = coordinator(state, backgroundScope, loadQr = { qr })

        coordinator.openCouponRedemption()
        runCurrent()

        assertEquals(RegisterOverlay.Coupon(CouponDialogStartMode.REDEMPTION), state.value.overlay)
        assertEquals(qr, state.value.onlineQr)

        coordinator.closeCouponSale()
        assertNull(state.value.overlay)
        assertNull(state.value.onlineQr)
    }

    @Test
    fun \`uncertain coupon sale retry reuses the exact command and blocks dismissal\`() = runTest {
        val state = MutableStateFlow(RegisterUiState(overlay = RegisterOverlay.Coupon(CouponDialogStartMode.ENTRY)))
        val firstAttempt = CompletableDeferred<CommandResult<CouponSaleResult>>()
        val submitted = mutableListOf<CouponSaleCommand>()
        val coordinator = coordinator(
            state,
            backgroundScope,
            submitSale = { command ->
                submitted += command
                if (submitted.size == 1) firstAttempt.await()
                else CommandResult.Error(IllegalStateException("Retry interrupted"))
            }
        )

        coordinator.confirmCouponSale(null, "Customer", "+96891234567", "5.000", PaymentCategory.CASH, emptyList())
        runCurrent()
        coordinator.confirmCouponSale(null, "Customer", "+96891234567", "5.000", PaymentCategory.CASH, emptyList())
        runCurrent()

        assertEquals(1, submitted.size)
        coordinator.closeCouponSale()
        assertEquals(RegisterOverlay.Coupon(CouponDialogStartMode.ENTRY), state.value.overlay)

        firstAttempt.complete(CommandResult.Error(IllegalStateException("Connection interrupted")))
        runCurrent()
        val frozenCommand = requireNotNull(state.value.inFlightCouponSaleCommand)

        coordinator.confirmCouponSale(null, "Customer", "+96891234567", "5.000", PaymentCategory.CASH, emptyList())
        runCurrent()

        assertEquals(2, submitted.size)
        assertEquals(frozenCommand, submitted[0])
        assertEquals(frozenCommand, submitted[1])
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun \`redemption freezes selected authorised user and partial remainder funding\`() = runTest {
        val customer = customer()
        val details = walletDetails(customer)
        val state = MutableStateFlow(
            RegisterUiState(
                overlay = RegisterOverlay.Coupon(CouponDialogStartMode.REDEMPTION),
                draftState = DraftState(manualAmount = MoneyMinorUnits(10_000)),
                couponWalletDetails = details
            )
        )
        var submitted: CouponRedemptionCommand? = null
        val coordinator = coordinator(
            state,
            backgroundScope,
            submitRedemption = {
                submitted = it
                CommandResult.Error(IllegalArgumentException("Rejected for test"))
            }
        )

        coordinator.confirmCouponRedemption(customer, "authorized-1", "5.000", PaymentCategory.CASH)
        runCurrent()

        assertNotNull(submitted)
        assertEquals(customer.id, submitted!!.customerId)
        assertEquals("wallet-1", submitted!!.walletId)
        assertEquals("authorized-1", submitted!!.authorizedUserId)
        assertEquals(5_000L, submitted!!.amountMinor)
        assertEquals(PaymentCategory.CASH, submitted!!.remainderCategory)
        assertNull(state.value.inFlightCouponRedemptionCommand)
        assertEquals(UiText.Dynamic("Rejected for test"), state.value.errorMessage)
    }

    @Test
    fun \`wallet authorisation retains the existing event semantics\`() = runTest {
        val customer = customer()
        val details = walletDetails(customer)
        val state = MutableStateFlow(RegisterUiState(couponWalletDetails = details))
        var submitted: CouponAuthorizedUserCommand? = null
        val coordinator = coordinator(
            state,
            backgroundScope,
            submitUser = {
                submitted = it
                CommandResult.PermissionDenied("Not authorised")
            }
        )

        coordinator.saveCouponUser("authorized-1", "  Updated name  ", deactivate = true)
        runCurrent()

        assertNotNull(submitted)
        assertEquals("Updated name", submitted!!.name)
        assertEquals(AuthorizedUserEventType.DEACTIVATED, submitted!!.eventType)
        assertEquals("wallet-1", submitted!!.walletId)
        assertEquals(UiText.Dynamic("Not authorised"), state.value.errorMessage)
        assertNull(state.value.inFlightCouponUserCommand)
    }

    @Test
    fun \`top up applies the authoritative result and refreshes wallet projection\`() = runTest {
        val customer = customer()
        val refreshed = walletDetails(customer)
        val state = MutableStateFlow(RegisterUiState(overlay = RegisterOverlay.Coupon(CouponDialogStartMode.ENTRY)))
        var submitted: CouponTopUpCommand? = null
        val coordinator = coordinator(
            state,
            backgroundScope,
            loadWallet = { _, _ -> refreshed },
            submitTopUp = { command ->
                submitted = command
                CommandResult.Success(CouponTopUpResult(transaction(command), command.walletId, command.amountMinor))
            }
        )

        coordinator.confirmCouponTopUp(customer, "wallet-1", "1.000", PaymentCategory.CASH)
        runCurrent()

        assertNotNull(submitted)
        assertEquals(1_000L, submitted!!.amountMinor)
        assertEquals("wallet-1", submitted!!.walletId)
        assertEquals(1_000L, state.value.couponTopUpResult?.amountMinor)
        assertEquals(refreshed, state.value.couponWalletDetails)
        assertNull(state.value.inFlightCouponTopUpCommand)
        assertNull(state.value.lastCompletedTransaction)
        assertFalse(state.value.isExecuting)
    }

    @Test
    fun \`wallet load uses the current branch and clears loading after success\`() = runTest {
        val customer = customer()
        val details = walletDetails(customer)
        var requestedCurrency: String? = null
        val state = MutableStateFlow(RegisterUiState())
        val coordinator = coordinator(
            state,
            backgroundScope,
            loadWallet = { _, currency ->
                requestedCurrency = currency
                details
            }
        )

        coordinator.openCouponWallet(customer.id)
        runCurrent()

        assertEquals("OMR", requestedCurrency)
        assertEquals(RegisterOverlay.CouponWallet, state.value.overlay)
        assertEquals(details, state.value.couponWalletDetails)
        assertFalse(state.value.couponWalletLoading)
    }

    private fun coordinator(
        state: MutableStateFlow<RegisterUiState>,
        scope: CoroutineScope,
        currentSession: () -> ActiveSession? = { session() },
        financialContext: () -> RegisterFinancialSessionContext? = { currentSession().toRegisterFinancialSessionContext() },
        generation: () -> Long = { 0L },
        isCurrent: (RegisterFinancialSessionContext?, ActiveSession?, Long, Long) -> Boolean =
            ::shouldApplyFinancialResultToRegister,
        loadQr: suspend (String) -> MerchantQrSelection? = { null },
        loadWallet: suspend (String, String) -> RegisterRepository.CouponWalletDetails? = { _, _ -> null },
        submitSale: suspend (CouponSaleCommand) -> CommandResult<CouponSaleResult> =
            { CommandResult.Error(IllegalStateException("Coupon sale not configured")) },
        submitTopUp: suspend (CouponTopUpCommand) -> CommandResult<CouponTopUpResult> =
            { CommandResult.Error(IllegalStateException("Coupon top up not configured")) },
        submitUser: suspend (CouponAuthorizedUserCommand) ->
            CommandResult<RegisterRepository.CouponAuthorizedUserDetails> =
            { CommandResult.Error(IllegalStateException("Coupon user not configured")) },
        submitRedemption: suspend (CouponRedemptionCommand) -> CommandResult<CouponRedemptionResult> =
            { CommandResult.Error(IllegalStateException("Coupon redemption not configured")) }
    ) = RegisterCouponCoordinator(
        uiState = state,
        scope = scope,
        currentSession = currentSession,
        synchronizeFinancialSession = { false },
        financialContext = financialContext,
        financialSessionGeneration = generation,
        isFinancialSessionCurrent = isCurrent,
        decimalPlaces = { 3 },
        currencyCode = { "OMR" },
        deviceId = { "test-device" },
        loadOnlineQr = loadQr,
        submitCouponSale = submitSale,
        loadCouponWalletDetails = loadWallet,
        submitCouponTopUp = submitTopUp,
        submitCouponUser = submitUser,
        submitCouponRedemption = submitRedemption
    )

    private fun customer() = CustomerEntity("customer-1", "business-1", "+96891234567", "Customer", 0L, 0L)

    private fun walletDetails(customer: CustomerEntity): RegisterRepository.CouponWalletDetails {
        val wallet = com.dailymoneyregister.core.database.entity.CouponWalletEntity(
            "wallet-1", customer.id, "OMR", isActive = true, createdAt = 1L
        )
        val authorisedUser = com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity(
            "authorized-1", wallet.id, true, true, 1L
        )
        val event = com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity(
            "event-1", authorisedUser.id, "operation-1", AuthorizedUserEventType.ADDED,
            "Customer", 1L, "user-1"
        )
        return RegisterRepository.CouponWalletDetails(
            wallet = wallet,
            balanceMinor = 10_000L,
            ledgerEvents = emptyList(),
            authorizedUsers = listOf(RegisterRepository.CouponAuthorizedUserDetails(authorisedUser, listOf(event))),
            customer = customer
        )
    }

    private fun session(): ActiveSession {
        val business = BusinessEntity("business-1", "Business", createdAt = 1L, updatedAt = 1L)
        val branch = BranchEntity(
            id = "branch-1",
            businessId = business.id,
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 1L,
            updatedAt = 1L
        )
        val user = UserAccountEntity(
            id = "user-1",
            businessId = business.id,
            displayName = "Admin",
            username = "admin",
            role = UserRole.ADMIN,
            authType = AuthType.PASSWORD,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 1L,
            updatedAt = 1L
        )
        return ActiveSession(business, branch, user, null)
    }

    private fun qrSelection() = MerchantQrSelection(
        config = com.dailymoneyregister.core.database.entity.MerchantQrConfigEntity(
            id = "qr-config",
            businessId = "business-1",
            branchId = "branch-1",
            displayLabel = "QR",
            bankAppName = "Bank",
            mediaId = "qr-media",
            isDefault = true,
            isActive = true,
            createdAt = 1L
        ),
        media = MediaAssetEntity(
            id = "qr-media",
            businessId = "business-1",
            branchId = "branch-1",
            mediaType = MediaType.MERCHANT_QR,
            localReference = "qr.png",
            createdAt = 1L
        )
    )

    private fun transaction(command: CouponTopUpCommand) = TransactionEntity(
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
}
