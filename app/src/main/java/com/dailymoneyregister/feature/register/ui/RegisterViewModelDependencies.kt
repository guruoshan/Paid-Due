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
 * BOARDLOCK Bundle D.
 *
 * Cohesive ViewModel wiring seams only.
 * No durable financial state.
 * No financial business rules.
 * No service-locator behavior.
 */

internal class RegisterSaleDependencies(
    val observeToday: (String, String) -> Flow<List<RecentFinancialActivity>>,
    val executeSale: suspend (CashSaleCommand, UserAccountEntity, UserBranchMembershipEntity?) -> CommandResult<TransactionEntity>,
    val deviceId: () -> String,
    val findSaleCustomerByPhone: suspend (String, String) -> CustomerEntity?,
    val executeOnlineSale: suspend (OnlineSaleCommand) -> CommandResult<TransactionEntity>,
    val loadOnlineQr: suspend (String) -> MerchantQrSelection?,
    val submitCreditSale: suspend (CreditSaleCommand) -> CommandResult<TransactionEntity>,
    val loadCreditInvoice:
        suspend (CreditSaleCommand) ->
            com.dailymoneyregister.feature.register.data.RegisterRepository.CreditInvoiceDetails,
    val rememberProduct: suspend (String, String, String?, Long) -> Unit,
    val searchProducts: suspend (String, String) -> List<ProductEntity>
)

internal class RegisterCouponDependencies(
    val submitCouponSale: suspend (CouponSaleCommand) -> CommandResult<CouponSaleResult>,
    val findCouponCustomers: suspend (String) -> List<CouponSaleCustomerWallet>,
    val loadCouponWalletDetails: suspend (String, String) -> CouponWalletDetails?,
    val submitCouponTopUp: suspend (CouponTopUpCommand) -> CommandResult<CouponTopUpResult>,
    val submitCouponUser: suspend (CouponAuthorizedUserCommand) -> CommandResult<RegisterRepository.CouponAuthorizedUserDetails>,
    val submitCouponRedemption: suspend (CouponRedemptionCommand) -> CommandResult<CouponRedemptionResult>
)

internal class RegisterTransactionLifecycleDependencies(
    val loadTransactionDetails: suspend (String) -> TransactionDetailsReadModel?,
    val loadSaleItems: suspend (String) -> List<com.dailymoneyregister.core.database.entity.TransactionItemEntity>,
    val submitAmountCorrection: suspend (AmountCorrectionCommand) -> CommandResult<AmountCorrectionExecutionResult>,
    val submitRefund: suspend (RefundCommand) -> CommandResult<RefundExecutionResult>,
    val submitVoid: suspend (VoidCommand) -> CommandResult<VoidExecutionResult>,
    val submitPaymentMethodChange: suspend (PaymentMethodChangeCommand) -> CommandResult<PaymentMethodChangeExecutionResult>
)

internal class RegisterExpenseDependencies(
    val submitExpense: suspend (ExpenseCommand) -> CommandResult<ExpenseCommandResult>,
    val loadExpensesForPeriod:
        suspend (String, String, Long, Long) ->
            List<com.dailymoneyregister.core.database.entity.ExpenseEntity>,
    val loadExpenseDetails: suspend (String) -> ExpenseReadProjection?,
    val loadExpenseSupportingDocuments: suspend (String) -> List<ExpenseSupportingDocument>,
    val attachExpenseSupportingDocument: suspend (String, String) -> Unit
)

internal class RegisterShiftDependencies(
    val loadCurrentRegisterShiftReport: suspend () -> RegisterShiftReport?,
    val submitRegisterShiftCount: suspend (CountRegisterShiftCommand) -> CommandResult<RegisterShiftResult>,
    val submitRegisterShiftClose: suspend (CloseRegisterShiftCommand) -> CommandResult<RegisterShiftResult>
)