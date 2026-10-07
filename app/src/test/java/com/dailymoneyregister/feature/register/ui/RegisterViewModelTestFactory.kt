package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.financial.RecentFinancialActivity
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.currency.CurrencyRegistry
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.OnlineSaleCommand
import com.dailymoneyregister.feature.register.data.CouponSaleCommand
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.ExpenseCommandFactory
import com.dailymoneyregister.feature.register.data.ExpenseCommandResult
import com.dailymoneyregister.feature.register.data.CouponSaleResult
import com.dailymoneyregister.feature.register.data.CouponSaleCustomerWallet
import com.dailymoneyregister.feature.register.data.CouponTopUpCommand
import com.dailymoneyregister.feature.register.data.CouponTopUpResult
import com.dailymoneyregister.feature.register.data.CouponAuthorizedUserCommand
import com.dailymoneyregister.feature.register.data.CouponRedemptionCommand
import com.dailymoneyregister.feature.register.data.CouponRedemptionResult
import com.dailymoneyregister.feature.register.data.RegisterRepository.CouponWalletDetails
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import com.dailymoneyregister.feature.register.data.ExpenseSupportingDocument
import com.dailymoneyregister.feature.register.data.ExpenseSupportingDocumentRepository
import com.dailymoneyregister.feature.register.data.AmountCorrectionCommand
import com.dailymoneyregister.feature.register.data.AmountCorrectionExecutionResult
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeCommand
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeExecutionResult
import com.dailymoneyregister.feature.register.data.RefundCommand
import com.dailymoneyregister.feature.register.data.RefundExecutionResult
import com.dailymoneyregister.feature.register.data.VoidCommand
import com.dailymoneyregister.feature.register.data.VoidExecutionResult
import com.dailymoneyregister.feature.settings.data.MerchantQrRepository
import com.dailymoneyregister.feature.register.data.ProductRepository
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.feature.settings.data.MerchantQrSelection
import com.dailymoneyregister.feature.register.domain.CalculatorEngine
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.register.domain.KeypadKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.dailymoneyregister.core.model.tax.TaxRateParser
import com.dailymoneyregister.feature.register.data.RegisterShiftReport
import com.dailymoneyregister.feature.register.data.RegisterShiftResult
import com.dailymoneyregister.feature.register.data.CountRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.CloseRegisterShiftCommand

/**
 * BOARDLOCK Bundle D test-only construction seam.
 *
 * Optional/no-op/error defaults exist only in test source.
 * Production RegisterViewModel dependencies remain required.
 */
internal fun testRegisterViewModel(
    observeToday: (String, String) -> Flow<List<RecentFinancialActivity>>,
    executeSale: suspend (CashSaleCommand, UserAccountEntity, UserBranchMembershipEntity?) -> CommandResult<TransactionEntity>,
    deviceId: () -> String,
    findSaleCustomerByPhone: suspend (String, String) -> CustomerEntity? = { _, _ -> null },
    executeOnlineSale: suspend (OnlineSaleCommand) -> CommandResult<TransactionEntity> = { error("Online sale is not configured") },
    loadOnlineQr: suspend (String) -> MerchantQrSelection? = { null },
    submitCreditSale: suspend (CreditSaleCommand) -> CommandResult<TransactionEntity> = { error("Credit Sale unavailable") },
    loadCreditInvoice:
        suspend (CreditSaleCommand) ->
            com.dailymoneyregister.feature.register.data.RegisterRepository.CreditInvoiceDetails =
        {
            error("Credit invoice unavailable")
        },
    submitCouponSale: suspend (CouponSaleCommand) -> CommandResult<CouponSaleResult> = { error("Coupon Sale unavailable") },
    findCouponCustomers: suspend (String) -> List<CouponSaleCustomerWallet> = { emptyList() },
    loadCouponWalletDetails: suspend (String, String) -> CouponWalletDetails? = { _, _ -> null },
    submitCouponTopUp: suspend (CouponTopUpCommand) -> CommandResult<CouponTopUpResult> = { error("Coupon Top Up unavailable") },
    submitCouponUser: suspend (CouponAuthorizedUserCommand) -> CommandResult<RegisterRepository.CouponAuthorizedUserDetails> = { error("Coupon authorization unavailable") },
    submitCouponRedemption: suspend (CouponRedemptionCommand) -> CommandResult<CouponRedemptionResult> = { error("Coupon redemption unavailable") },
    productRepository: ProductRepository? = null,
    loadTransactionDetails: suspend (String) -> TransactionDetailsReadModel? = { null },
    loadSaleItems: suspend (String) -> List<com.dailymoneyregister.core.database.entity.TransactionItemEntity> = { emptyList() },
    submitAmountCorrection: suspend (AmountCorrectionCommand) -> CommandResult<AmountCorrectionExecutionResult> = {
        error("Amount Correction unavailable")
    },
    submitRefund: suspend (RefundCommand) -> CommandResult<RefundExecutionResult> = {
        error("Refund unavailable")
    },
    submitVoid: suspend (VoidCommand) -> CommandResult<VoidExecutionResult> = {
        error("Void unavailable")
    },
    submitPaymentMethodChange: suspend (PaymentMethodChangeCommand) -> CommandResult<PaymentMethodChangeExecutionResult> = {
        error("Payment Method Change unavailable")
    },
    submitExpense: suspend (ExpenseCommand) -> CommandResult<ExpenseCommandResult> = {
        error("Expense unavailable")
    },
    loadExpensesForPeriod:
        suspend (String, String, Long, Long) ->
            List<com.dailymoneyregister.core.database.entity.ExpenseEntity> =
        { _, _, _, _ -> emptyList() },
    loadCurrentRegisterShiftReport: suspend () -> RegisterShiftReport? = { null },
    submitRegisterShiftCount: suspend (CountRegisterShiftCommand) -> CommandResult<RegisterShiftResult> = {
        error("Register Shift count unavailable")
    },
    submitRegisterShiftClose: suspend (CloseRegisterShiftCommand) -> CommandResult<RegisterShiftResult> = {
        error("Register Shift close unavailable")
    },
    loadExpenseDetails: suspend (String) -> ExpenseReadProjection? = { null },
    loadExpenseSupportingDocuments: suspend (String) -> List<ExpenseSupportingDocument> = { emptyList() },
    attachExpenseSupportingDocument: suspend (String, String) -> Unit = { _, _ -> }
): RegisterViewModel =
    RegisterViewModel(
        saleDependencies = RegisterSaleDependencies(
            observeToday = observeToday,
            executeSale = executeSale,
            deviceId = deviceId,
            findSaleCustomerByPhone = findSaleCustomerByPhone,
            executeOnlineSale = executeOnlineSale,
            loadOnlineQr = loadOnlineQr,
            submitCreditSale = submitCreditSale,
            loadCreditInvoice = loadCreditInvoice,
            rememberProduct = { branchId, name, description, unitPriceMinor -> productRepository?.remember(branchId, name, description, unitPriceMinor); Unit },
            searchProducts = { branchId, query -> productRepository?.search(branchId, query) ?: emptyList() }
        ),
        couponDependencies = RegisterCouponDependencies(
            submitCouponSale = submitCouponSale,
            findCouponCustomers = findCouponCustomers,
            loadCouponWalletDetails = loadCouponWalletDetails,
            submitCouponTopUp = submitCouponTopUp,
            submitCouponUser = submitCouponUser,
            submitCouponRedemption = submitCouponRedemption
        ),
        transactionDependencies = RegisterTransactionLifecycleDependencies(
            loadTransactionDetails = loadTransactionDetails,
            loadSaleItems = loadSaleItems,
            submitAmountCorrection = submitAmountCorrection,
            submitRefund = submitRefund,
            submitVoid = submitVoid,
            submitPaymentMethodChange = submitPaymentMethodChange
        ),
        expenseDependencies = RegisterExpenseDependencies(
            submitExpense = submitExpense,
            loadExpensesForPeriod = loadExpensesForPeriod,
            loadExpenseDetails = loadExpenseDetails,
            loadExpenseSupportingDocuments = loadExpenseSupportingDocuments,
            attachExpenseSupportingDocument = attachExpenseSupportingDocument
        ),
        shiftDependencies = RegisterShiftDependencies(
            loadCurrentRegisterShiftReport = loadCurrentRegisterShiftReport,
            submitRegisterShiftCount = submitRegisterShiftCount,
            submitRegisterShiftClose = submitRegisterShiftClose
        )
    )