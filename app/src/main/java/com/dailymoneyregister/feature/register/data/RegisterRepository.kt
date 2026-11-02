package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.isActive
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.RecentFinancialActivity
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

data class CashSaleCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    val transactionIdVal: TransactionId,
    val draftState: DraftState,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis(),
    val customerName: String? = null,
    val customerPhone: String? = null
) : OperationCommand {
    override val operationType = OperationType.RECORD_SALE
    override val subjectType = SubjectType.TRANSACTION
    override val subjectId = transactionIdVal.value
    override val transactionId = transactionIdVal.value
    override val commandSchemaVersion = CashSalePayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() {
            val itemsDigest = draftState.items.joinToString(";") {
                "${it.id}:${it.name}:${it.quantity}:${it.unitPrice.amount}:${it.itemPackingCharge.amount}:${it.itemShippingCharge.amount}"
            }
            return "v1|biz:$businessId|br:$branchId|act:$actorUserId|tx:${transactionIdVal.value}|" +
                    "cur:$currencyCode:$currencyDecimalPlaces|sub:${draftState.subtotal.amount}|" +
                    "tax:${draftState.isTaxEnabled}:${draftState.taxRateBps}:${draftState.taxAmount.amount}|" +
                    "shp:${draftState.txShipping.amount}|pck:${draftState.txPacking.amount}|" +
                    "itemShp:${draftState.itemShippingTotal.amount}|itemPck:${draftState.itemPackingTotal.amount}|" +
                    "gnd:${draftState.grandTotal.amount}|desc:${draftState.manualDescription ?: ""}|" +
                    "items:[$itemsDigest]|time:$occurredAt" +
                    (customerPhone?.let {
                        "|customer:$it|name:${customerName.orEmpty()}"
                    } ?: "")
        }

    override val intentPayload: String
        get() = CashSalePayloadCodec.encode(this)
}

data class VoidExecutionResult(
    val transactionId: String,
    val voidEventId: String,
    val reversalAmountMinor: Long
)

data class AmountCorrectionExecutionResult(
    val transactionId: String,
    val amountCorrectionEventId: String,
    val previousAmountMinor: Long,
    val correctedAmountMinor: Long,
    val deltaMinor: Long
)
data class PaymentMethodChangeExecutionResult(
    val transactionId: String,
    val paymentMethodChangeEventId: String,
    val previousCategory: PaymentCategory,
    val previousChannel: PaymentChannel,
    val newCategory: PaymentCategory,
    val newChannel: PaymentChannel,
    val reclassificationAmountMinor: Long
)

data class CouponSaleCustomerWallet(
    val customer: CustomerEntity,
    val wallet: CouponWalletEntity?,
    val balanceMinor: Long
)

class RegisterRepository @Inject constructor(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val deviceIdentityProvider: DeviceIdentityProvider? = null
) {
    private val transactionDao = database.transactionDao()
    private val registerQueryRepository = RegisterQueryRepository(database)
    private val ledgerDao = database.ledgerDao()
    private val businessBranchDao = database.businessBranchDao()
    private val userDao = database.userDao()
    private val cashShiftDao = database.cashShiftDao()
    private val saleFinancialWriter = SaleFinancialWriter(transactionDao)

    /*
     * BOARDLOCK GOD-file decomposition.
     *
     * RegisterRepository remains the compatibility facade.
     * Transaction Details reads are delegated to a dedicated,
     * read-only component.
     */
    private val transactionDetailsReadRepository =
        TransactionDetailsReadRepository(
            database
        )

    private val registerShiftStateReadRepository =
        RegisterShiftStateReadRepository(database)

    private val operationDao = database.operationDao()

    private val registerShiftRepository =
        RegisterShiftRepository(
            commandExecutor = commandExecutor,
            operationDao = operationDao,
            cashShiftDao = cashShiftDao,
            businessBranchDao = businessBranchDao,
            userDao = userDao,
            registerShiftStateReadRepository = registerShiftStateReadRepository
        )

    /*
     * Serializes automatic shift admission inside this process.
     *
     * This prevents two simultaneous local cashier actions from both
     * attempting to create separate shifts.
     *
     * Cross-device concurrency remains a cloud synchronization concern
     * and is not falsely claimed to be solved by this local mutex.
     */
    private val financialCashShiftResolver = FinancialCashShiftResolver(database) { command -> openRegisterShift(command) }
    private val financialMutationAuthorizer = FinancialMutationAuthorizer(database)

    private val refundFinancialRepository =
        RefundFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            authorizeFinancialMutation = {
                    actorUserId,
                    businessId,
                    branchId,
                    action,
                    actionLabel ->
                authorizeNewFinancialMutation(
                    actorUserId = actorUserId,
                    businessId = businessId,
                    branchId = branchId,
                    action = action,
                    actionLabel = actionLabel
                )
            },
            resolveFinancialCashShiftId = { command ->
                resolveFinancialCashShiftId(command)
            }
        )

    private val creditRepaymentFinancialRepository =
        CreditRepaymentFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            authorizeNewFinancialMutation = {
                    actorUserId,
                    businessId,
                    branchId,
                    action,
                    actionLabel ->
                authorizeNewFinancialMutation(
                    actorUserId = actorUserId,
                    businessId = businessId,
                    branchId = branchId,
                    action = action,
                    actionLabel = actionLabel
                )
            },
            resolveFinancialCashShiftId = { command ->
                resolveFinancialCashShiftId(command)
            }
        )

    private val amountCorrectionFinancialRepository =
        AmountCorrectionFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            authorizeNewFinancialMutation = { actorUserId, businessId, branchId, action, actionLabel ->
                authorizeNewFinancialMutation(
                    actorUserId = actorUserId,
                    businessId = businessId,
                    branchId = branchId,
                    action = action,
                    actionLabel = actionLabel
                )
            },
            resolveFinancialCashShiftId = { command ->
                resolveFinancialCashShiftId(command)
            }
        )

    private val transactionLifecycleFinancialRepository =
        TransactionLifecycleFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            operationDao = operationDao,
            transactionDao = transactionDao,
            ledgerDao = ledgerDao,
            authorizeFinancialMutation = { actorUserId, businessId, branchId, action, actionLabel ->
                authorizeNewFinancialMutation(
                    actorUserId = actorUserId,
                    businessId = businessId,
                    branchId = branchId,
                    action = action,
                    actionLabel = actionLabel
                )
            },
            resolveFinancialCashShiftId = { command ->
                resolveFinancialCashShiftId(command)
            }
        )

    /*
     * BOARDLOCK decomposition facade.
     *
     * Public RegisterRepository API remains unchanged while implementation
     * responsibilities are progressively extracted behind it.
     */
    private val couponFinancialRepository =
        CouponFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            authorizeFinancialMutation = {
                    actorUserId,
                    businessId,
                    branchId,
                    action,
                    actionLabel ->
                authorizeNewFinancialMutation(
                    actorUserId = actorUserId,
                    businessId = businessId,
                    branchId = branchId,
                    action = action,
                    actionLabel = actionLabel
                )
            }
        ,
            resolveFinancialCashShiftId = { command ->
                resolveFinancialCashShiftId(command)
            },
            saleFinancialWriter = saleFinancialWriter
        )

    /**
     * Resolves immutable cashier-shift attribution for a financial operation
     * only after that operation has passed its normal admission gates.
     *
     * Existing durable operations are never re-attributed. This is critical
     * for recovery and for historical pre-v10 operations whose shift ID is
     * intentionally NULL.
     */
    /*
     * BOARDLOCK Expense financial facade.
     *
     * Expense uses the same universal CommandExecutor, authenticated
     * financial authorization gate, and cashier-shift resolver as the
     * other authoritative Register financial operations.
     *
     * No Expense financial state is duplicated in RegisterRepository.
     */
    private val expenseFinancialRepository =
        ExpenseFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            authorizeFinancialMutation = {
                    actorUserId,
                    businessId,
                    branchId,
                    action,
                    actionLabel ->
                authorizeNewFinancialMutation(
                    actorUserId = actorUserId,
                    businessId = businessId,
                    branchId = branchId,
                    action = action,
                    actionLabel = actionLabel
                )
            },
            resolveFinancialCashShiftId = { command ->
                requireNotNull(
                    resolveFinancialCashShiftId(command)
                ) {
                    "Current financial operation requires cashier shift attribution"
                }
            }
        )

    /*
     * BOARDLOCK GOD-file decomposition.
     *
     * Expense writes remain in ExpenseFinancialRepository.
     * Expense reads are isolated behind ExpenseReadRepository.
     */
    private val expenseReadRepository =
        ExpenseReadRepository(
            database
        )
    suspend fun executeExpense(
        command: ExpenseCommand
    ): CommandResult<ExpenseCommandResult> =
        expenseFinancialRepository.executeExpense(command)

    suspend fun executeExpenseLifecycle(
        command: ExpenseLifecycleCommand
    ): CommandResult<com.dailymoneyregister.core.database.entity.ExpenseEntity> =
        expenseFinancialRepository.executeExpenseLifecycle(command)

    /**
     * Read-only Expense projection.
     *
     * Expense rows remain the authoritative source. This method does not
     * maintain a second balance or Expense state.
     */
    suspend fun getExpensesForPeriod(
        businessId: String,
        branchId: String,
        fromInclusive: Long,
        toExclusive: Long
    ): List<com.dailymoneyregister.core.database.entity.ExpenseEntity> =
        expenseReadRepository.getExpensesForPeriod(
            businessId = businessId,
            branchId = branchId,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive
        )
    private suspend fun resolveFinancialCashShiftId(
        command: OperationCommand
    ): String? =
        financialCashShiftResolver.resolveFinancialCashShiftId(command)

    suspend fun searchCouponSaleCustomers(
        query: String
    ): List<CouponSaleCustomerWallet> =
        couponFinancialRepository.searchCouponSaleCustomers(query)

    suspend fun executeCouponRedemption(c: CouponRedemptionCommand): CommandResult<CouponRedemptionResult> =
        couponFinancialRepository.executeCouponRedemption(c)

    suspend fun executeCouponSale(command: CouponSaleCommand): CommandResult<CouponSaleResult> =
        couponFinancialRepository.executeCouponSale(command)

    suspend fun executeCouponTopUp(
        command: CouponTopUpCommand
    ): CommandResult<CouponTopUpResult> =
        couponFinancialRepository.executeCouponTopUp(command)

    data class CouponAuthorizedUserDetails(
        val user: com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity,
        val events: List<com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity>
    ) {
        val name: String get() = events.lastOrNull()?.nameValue.orEmpty()
        val isActive: Boolean get() = user.isActive && events.lastOrNull()?.eventType !=
            com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.DEACTIVATED
    }

    data class CouponWalletDetails(
        val wallet: com.dailymoneyregister.core.database.entity.CouponWalletEntity,
        val balanceMinor: Long,
        val ledgerEvents: List<com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity>,
        val authorizedUsers: List<CouponAuthorizedUserDetails>,
        val customer: CustomerEntity? = null,
        val activityKinds: Map<String, CouponWalletActivityKind> = emptyMap()
    )

    enum class CouponWalletActivityKind {
        SALE, TOP_UP, BONUS, REDEMPTION, REFUND_TO_COUPON
    }

    private val couponWalletReadRepository = CouponWalletReadRepository(database)
    private val registerShiftReportReadRepository = RegisterShiftReportReadRepository(database)
    private val recentFinancialActivityReadRepository =
        RecentFinancialActivityReadRepository(
            database = database,
            localDeviceId = deviceIdentityProvider?.deviceId
        )

    suspend fun getCouponWalletDetails(customerId: String, currencyCode: String): CouponWalletDetails? = couponWalletReadRepository.getCouponWalletDetails(customerId, currencyCode)

    suspend fun executeCouponAuthorizedUser(
        command: CouponAuthorizedUserCommand
    ): CommandResult<CouponAuthorizedUserDetails> =
        couponFinancialRepository.executeCouponAuthorizedUser(command)

    suspend fun recoverPendingCouponSales() =
        couponFinancialRepository.recoverPendingCouponSales()

    /**
     * Creates the durable cashier Register Shift identity and OPENED event.
     *
     * COUNTED and CLOSED are separate append-only lifecycle operations.
     * No financial transaction is rewritten by the shift lifecycle.
     */
    suspend fun openRegisterShift(
        command: OpenRegisterShiftCommand
    ): CommandResult<RegisterShiftResult> =
        registerShiftRepository.openRegisterShift(command)

    /**
     * Read-only Day Closing projection for the authenticated cashier.
     *
     * Shift membership comes only from OperationEntity.cashShiftId.
     * Historical NULL attribution is never guessed.
     */
    suspend fun getCurrentRegisterShiftReport(): RegisterShiftReport? = registerShiftReportReadRepository.getCurrentRegisterShiftReport()

    suspend fun countRegisterShift(
        command: CountRegisterShiftCommand
    ): CommandResult<RegisterShiftResult> =
        registerShiftRepository.countRegisterShift(command)

    suspend fun closeRegisterShift(
        command: CloseRegisterShiftCommand
    ): CommandResult<RegisterShiftResult> =
        registerShiftRepository.closeRegisterShift(command)

    suspend fun recoverPendingRegisterShifts(): List<RegisterShiftResult> =
        registerShiftRepository.recoverPendingRegisterShifts()

    // Retained as a private characterization-test seam; production callers use the collaborator directly.
    private suspend fun readRegisterShift(
        shiftId: String,
        expectedBranchId: String,
        expectedBusinessId: String
    ): RegisterShiftResult =
        registerShiftRepository.readRegisterShift(
            shiftId = shiftId,
            expectedBranchId = expectedBranchId,
            expectedBusinessId = expectedBusinessId
        )

    /**
     * Authorization gate for a NEW financial lifecycle mutation.
     *
     * Durable PENDING operations bypass this helper during retry/recovery;
     * once admitted, mutable future permissions must not redefine intent.
     */
    private suspend fun authorizeNewFinancialMutation(
        actorUserId: String,
        businessId: String,
        branchId: String,
        action: PermissionAction,
        actionLabel: String
    ): CommandResult<Nothing>? =
        financialMutationAuthorizer.authorizeNewFinancialMutation(
            actorUserId = actorUserId,
            businessId = businessId,
            branchId = branchId,
            action = action,
            actionLabel = actionLabel
        )

    /*
     * BOARDLOCK GOD-file decomposition.
     *
     * Sale-customer reads are delegated here.
     * Customer identity creation remains in SaleFinancialRepository.
     */
    private val registerCustomerReadRepository =
        RegisterCustomerReadRepository(
            database
        )

    private val saleFinancialRepository =
        SaleFinancialRepository(
            database = database,
            commandExecutor = commandExecutor,
            saleFinancialWriter = saleFinancialWriter,
            financialMutationAuthorizer = financialMutationAuthorizer,
            resolveCashShiftId = { command -> resolveFinancialCashShiftId(command) },
            registerCustomerReadRepository = registerCustomerReadRepository,
            transactionDao = transactionDao,
            ledgerDao = ledgerDao,
            businessBranchDao = businessBranchDao,
            userDao = userDao,
            operationDao = operationDao
        )

    suspend fun findSaleCustomerByPhone(
        businessId: String,
        canonicalPhone: String
    ): com.dailymoneyregister.core.database.entity.CustomerEntity? =
        registerCustomerReadRepository
            .findSaleCustomerByPhone(
                businessId = businessId,
                canonicalPhone = canonicalPhone
            )

    suspend fun executeCashSale(
        command: CashSaleCommand,
        actorUser: UserAccountEntity,
        actorMembership: UserBranchMembershipEntity?
    ): CommandResult<TransactionEntity> =
        saleFinancialRepository.executeCashSale(command, actorUser, actorMembership)

    /*
     * BOARDLOCK GOD-file decomposition.
     *
     * Credit Invoice is a post-save read-only projection.
     * Credit Sale execution and evidence verification are delegated to
     * SaleFinancialRepository.
     */
    private val creditInvoiceReadRepository =
        CreditInvoiceReadRepository(
            database
        )
    data class CreditInvoiceDetails(
        val customerId: String,
        val customerName: String,
        val customerPhone: String,
        val transactionId: String,
        val purchaseOccurredAt: Long,
        val creditGivenMinor: Long,
        val purchaseOutstandingMinor: Long,
        val totalOutstandingMinor: Long,
        val currencyCode: String,
        val currencyDecimalPlaces: Int
    )

    /**
     * Read-only post-save Credit invoice evidence.
     *
     * No financial state is created here.
     *
     * Credit given:
     *   persisted Credit OBLIGATION.
     *
     * Pending for this purchase:
     *   original OBLIGATION minus authoritative FIFO allocations
     *   referencing this original transaction.
     *
     * Total credit pending:
     *   authoritative signed Credit ledger balance.
     */
    suspend fun getCreditInvoiceDetails(
        command: CreditSaleCommand
    ): CreditInvoiceDetails =
        creditInvoiceReadRepository
            .getCreditInvoiceDetails(
                command
            )

    suspend fun executeCreditSale(
        command: CreditSaleCommand
    ): CommandResult<TransactionEntity> =
        saleFinancialRepository.executeCreditSale(command)

    data class CreditRepaymentExecutionResult(
        val repayment:
            com.dailymoneyregister.core.database.entity.CreditRepaymentEntity,
        val allocations:
            List<com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity>,
        val outstandingAfterMinor: Long
    )

    suspend fun executeCouponCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data
                .CouponCreditRepaymentCommand
    ): CommandResult<CreditRepaymentExecutionResult> =
        creditRepaymentFinancialRepository.executeCouponCreditRepayment(command)

    /**
     * CREDIT_1D_B1_RECEIVE_PAYMENT
     *
     * Read-only projection of Customer Credit outstanding.
     *
     * This value is derived directly from the authoritative
     * signed Credit ledger.
     *
     * No editable Customer balance is created.
     */
    suspend fun getCustomerCreditOutstanding(
        customerId: String,
        currencyCode: String
    ): Long =
        registerQueryRepository.getCustomerCreditOutstanding(
            customerId = customerId,
            currencyCode = currencyCode
        )

    suspend fun executeCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): CommandResult<CreditRepaymentExecutionResult> =
        creditRepaymentFinancialRepository.executeCreditRepayment(command)

    suspend fun recoverPendingCreditRepayments() =
        creditRepaymentFinancialRepository.recoverPendingCreditRepayments()

    suspend fun executeOnlineSale(command: OnlineSaleCommand): CommandResult<TransactionEntity> =
        saleFinancialRepository.executeOnlineSale(command)

    suspend fun executeAmountCorrection(
        command: AmountCorrectionCommand
    ): CommandResult<AmountCorrectionExecutionResult> =
        amountCorrectionFinancialRepository.executeAmountCorrection(command)

    suspend fun recoverPendingAmountCorrections():
        List<AmountCorrectionExecutionResult> =
        amountCorrectionFinancialRepository.recoverPendingAmountCorrections()

    suspend fun executePaymentMethodChange(
        command: PaymentMethodChangeCommand
    ): CommandResult<PaymentMethodChangeExecutionResult> =
        transactionLifecycleFinancialRepository.executePaymentMethodChange(command)

    suspend fun recoverPendingPaymentMethodChanges():
        List<PaymentMethodChangeExecutionResult> =
        transactionLifecycleFinancialRepository.recoverPendingPaymentMethodChanges()

    suspend fun executeRefund(
        command: RefundCommand
    ): CommandResult<RefundExecutionResult> =
        refundFinancialRepository.executeRefund(command)

    suspend fun recoverPendingRefunds():
        List<RefundExecutionResult> =
        refundFinancialRepository.recoverPendingRefunds()
    suspend fun executeVoid(command: VoidCommand): CommandResult<VoidExecutionResult> =
        transactionLifecycleFinancialRepository.executeVoid(command)

    suspend fun recoverPendingVoids(): List<VoidExecutionResult> =
        transactionLifecycleFinancialRepository.recoverPendingVoids()
    /** Public compatibility entry points for pending sale recovery. */
    suspend fun recoverPendingCreditSales(): List<TransactionEntity> =
        saleFinancialRepository.recoverPendingCreditSales()

    suspend fun recoverPendingCashSales(): List<TransactionEntity> =
        saleFinancialRepository.recoverPendingCashSales()

    suspend fun recoverPendingOnlineSales(): List<TransactionEntity> =
        saleFinancialRepository.recoverPendingOnlineSales()

    fun observeRecentTransactions(
        branchId: String,
        limit: Int = 20
    ): Flow<List<TransactionEntity>> =
        registerQueryRepository.observeRecentTransactions(
            branchId = branchId,
            limit = limit
        )

    fun observeTodayTransactions(
        branchId: String,
        timezoneId: String
    ): Flow<List<RecentFinancialActivity>> =
        recentFinancialActivityReadRepository.observeTodayTransactions(
            branchId = branchId,
            timezoneId = timezoneId
        )

    suspend fun getFinancialTransactionById(
        transactionId: String
    ): FinancialTransactionView? =
        transactionDetailsReadRepository
            .getFinancialTransactionById(
                transactionId
            )
    suspend fun getTransactionItems(
        transactionId: String
    ): List<TransactionItemEntity> =
        transactionDetailsReadRepository
            .getTransactionItems(
                transactionId
            )
    /**
     * Read-only authoritative projection for Transaction Details.
     *
     * This method does not write financial state and does not calculate a second
     * independent transaction balance. Financial meaning is resolved from the same
     * immutable transaction/lifecycle evidence used by other financial screens.
     */
    /**
     * Read-only authoritative Expense Details projection.
     *
     * ExpenseEntity owns Expense identity/details.
     * SettlementEntry owns funding classification.
     * This method performs no financial write.
     */
    suspend fun getExpenseDetails(
        expenseId: String
    ): ExpenseReadProjection? =
        expenseReadRepository
            .getExpenseDetails(
                expenseId
            )
    suspend fun getTransactionDetails(
        transactionId: String
    ): TransactionDetailsReadModel? =
        transactionDetailsReadRepository
            .getTransactionDetails(
                transactionId
            )

}

