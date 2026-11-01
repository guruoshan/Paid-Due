package com.dailymoneyregister.feature.register.ui

import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.AmountCorrectionCommand
import com.dailymoneyregister.feature.register.data.AmountCorrectionExecutionResult
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeCommand
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeExecutionResult
import com.dailymoneyregister.feature.register.data.RefundCommand
import com.dailymoneyregister.feature.register.data.RefundExecutionResult
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.data.VoidCommand
import com.dailymoneyregister.feature.register.data.VoidExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransactionLifecycleActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val business = BusinessEntity("business", "Business", 1, 1)
    private val branch = BranchEntity(
        "branch", business.id, "Main", "MAIN", "OM", "OMR", 3,
        "OMR", "Asia/Muscat", createdAt = 1, updatedAt = 1
    )
    private val user = UserAccountEntity(
        id = "admin",
        businessId = business.id,
        displayName = "Admin",
        username = "admin",
        role = UserRole.ADMIN,
        authType = AuthType.PIN,
        credentialHash = "hash",
        credentialSalt = "salt",
        createdAt = 1,
        updatedAt = 1
    )
    private lateinit var viewModel: RegisterViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionManager.setSession(ActiveSession(business, branch, user, null))
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        SessionManager.clearSession()
        Dispatchers.resetMain()
    }

    @Test
    fun refundUsesOriginalTransactionAndRefreshesAuthoritativeDetails() = runTest(dispatcher) {
        val submitted = mutableListOf<RefundCommand>()
        var reads = 0
        viewModel = model(
            loadDetails = { reads++; details() },
            submitRefund = { command ->
                submitted += command
                CommandResult.Success(RefundExecutionResult(command.subjectId, "refund", command.refundAmountMinor))
            }
        )

        openDetails()
        viewModel.executeRefundFromDetails("2.500", PaymentCategory.CASH, "Return")
        runCurrent()

        assertEquals(1, submitted.size)
        assertEquals("transaction", submitted.single().subjectId)
        assertEquals(2_500L, submitted.single().refundAmountMinor)
        assertEquals(PaymentChannel.CASH_TENDER, submitted.single().destinationChannel)
        assertEquals(2, reads)
    }

    @Test
    fun amountCorrectionDelegatesWithTheSameTransactionAndCorrectedAmount() = runTest(dispatcher) {
        val submitted = mutableListOf<AmountCorrectionCommand>()
        viewModel = model(
            submitAmountCorrection = { command ->
                submitted += command
                CommandResult.Success(
                    AmountCorrectionExecutionResult(
                        command.subjectId, "correction", 10_000L,
                        command.correctedAmountMinor, command.correctedAmountMinor - 10_000L
                    )
                )
            }
        )

        openDetails()
        viewModel.executeAmountCorrectionFromDetails("8.000", "Correction")
        runCurrent()

        assertEquals(1, submitted.size)
        assertEquals("transaction", submitted.single().subjectId)
        assertEquals(8_000L, submitted.single().correctedAmountMinor)
        assertEquals(false, viewModel.uiState.value.isExecuting)
    }

    @Test
    fun refundToCouponPreparationAndExecutionRemainDelegated() = runTest(dispatcher) {
        val submitted = mutableListOf<RefundCommand>()
        viewModel = model(
            submitRefund = { command ->
                submitted += command
                CommandResult.Success(
                    RefundExecutionResult(command.subjectId, "refund", command.refundAmountMinor)
                )
            }
        )

        openDetails()
        viewModel.beginRefundToCouponFromDetails("2.500", "Coupon return")
        assertEquals(2_500L, viewModel.uiState.value.refundToCouponDraft?.amountMinor)
        assertEquals(RegisterOverlay.Coupon(CouponDialogStartMode.ENTRY), viewModel.uiState.value.overlay)

        viewModel.confirmRefundToCoupon(null, "New customer", "+96891234567", listOf("Additional user"))
        runCurrent()

        assertEquals(1, submitted.size)
        assertEquals("transaction", submitted.single().subjectId)
        assertEquals(PaymentCategory.COUPON, submitted.single().destinationCategory)
        assertEquals(PaymentChannel.COUPON_WALLET, submitted.single().destinationChannel)
        assertTrue(submitted.single().couponDestination?.createCustomer == true)
    }

    @Test
    fun voidDoubleSubmitRunsOneCommandWhileExecuting() = runTest(dispatcher) {
        val submitted = mutableListOf<VoidCommand>()
        val result = CompletableDeferred<CommandResult<VoidExecutionResult>>()
        viewModel = model(
            submitVoid = { command -> submitted += command; result.await() }
        )

        openDetails()
        viewModel.executeVoidFromDetails("Cancelled")
        viewModel.executeVoidFromDetails("Cancelled")
        runCurrent()

        assertEquals(1, submitted.size)
        assertTrue(viewModel.uiState.value.isExecuting)
        result.complete(CommandResult.Success(VoidExecutionResult("transaction", "void", 10_000)))
        runCurrent()
        assertEquals("transaction", submitted.single().subjectId)
    }

    @Test
    fun paymentChangeUsesLifecycleCommandWithoutReplacingOriginalPayment() = runTest(dispatcher) {
        val submitted = mutableListOf<PaymentMethodChangeCommand>()
        viewModel = model(
            submitPaymentChange = { command ->
                submitted += command
                CommandResult.Success(
                    PaymentMethodChangeExecutionResult(
                        command.subjectId, "change", PaymentCategory.CASH,
                        PaymentChannel.CASH_TENDER, command.newCategory,
                        command.newChannel, 10_000
                    )
                )
            }
        )

        openDetails()
        val original = viewModel.uiState.value.transactionDetails!!.financial.originalPaymentCategory
        viewModel.executePaymentMethodChangeFromDetails(PaymentCategory.ONLINE, "QR")
        runCurrent()

        assertEquals(PaymentCategory.CASH, original)
        assertEquals(PaymentCategory.ONLINE, submitted.single().newCategory)
        assertEquals(PaymentChannel.MANUAL_QR, submitted.single().newChannel)
        assertTrue(submitted.single().operationId.value.isNotBlank())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.openDetails() {
        viewModel.openTransactionDetails("transaction")
        runCurrent()
        assertTrue(viewModel.canRefundFromDetails)
        assertTrue(viewModel.canVoidFromDetails)
        assertTrue(viewModel.canChangePaymentMethodFromDetails)
    }

    private fun model(
        loadDetails: suspend (String) -> TransactionDetailsReadModel? = { details() },
        submitAmountCorrection: suspend (AmountCorrectionCommand) -> CommandResult<AmountCorrectionExecutionResult> = { error("Unexpected amount correction") },
        submitRefund: suspend (RefundCommand) -> CommandResult<RefundExecutionResult> = { error("Unexpected refund") },
        submitVoid: suspend (VoidCommand) -> CommandResult<VoidExecutionResult> = { error("Unexpected void") },
        submitPaymentChange: suspend (PaymentMethodChangeCommand) -> CommandResult<PaymentMethodChangeExecutionResult> = { error("Unexpected payment change") }
    ) = com.dailymoneyregister.feature.register.ui.testRegisterViewModel(
        observeToday = { _, _ -> flowOf(emptyList()) },
        executeSale = { _, _, _ -> error("Unexpected sale") },
        deviceId = { "device" },
        loadTransactionDetails = loadDetails,
        submitAmountCorrection = submitAmountCorrection,
        submitRefund = submitRefund,
        submitVoid = submitVoid,
        submitPaymentMethodChange = submitPaymentChange
    )

    private fun details(): TransactionDetailsReadModel {
        val transaction = TransactionEntity(
            id = "transaction", branchId = branch.id, type = TransactionType.SALE,
            subtotalMinor = 10_000, taxRateBps = 0, taxableBaseMinor = 10_000,
            taxAmountMinor = 0, grandTotalMinor = 10_000, currencyCode = "OMR",
            currencyDecimalPlaces = 3, createdByUserId = user.id, deviceId = "device",
            createdAt = 10, displayRef = "TX-1"
        )
        val financial = FinancialTransactionView(
            transaction = transaction,
            originalAmount = MoneyMinorUnits(10_000),
            currentAmount = MoneyMinorUnits(10_000),
            refundedAmount = MoneyMinorUnits.ZERO,
            netAmount = MoneyMinorUnits(10_000),
            settlementTotal = MoneyMinorUnits(10_000),
            isVoided = false,
            hasAmountCorrection = false,
            originalPaymentCategory = PaymentCategory.CASH,
            originalPaymentChannel = PaymentChannel.CASH_TENDER,
            currentPaymentCategory = PaymentCategory.CASH,
            currentPaymentChannel = PaymentChannel.CASH_TENDER,
            latestActivityAt = 10
        )
        return TransactionDetailsReadModel(
            financial, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )
    }
}
