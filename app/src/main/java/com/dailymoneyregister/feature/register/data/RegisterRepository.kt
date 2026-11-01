package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.financial.RecentActivityReadModelResolver
import com.dailymoneyregister.core.model.financial.RecentFinancialActivity
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.financial.FinancialFundingPart
import com.dailymoneyregister.core.model.financial.RecentActivityStatus
import com.dailymoneyregister.core.model.financial.RecentActivitySourceType
import com.dailymoneyregister.core.model.financial.RecentActivityType
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.model.currency.CurrencyInvariant
import androidx.room.withTransaction
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.timezone.TimezoneRegistry
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.Instant
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
                requireNotNull(
                    resolveFinancialCashShiftId(command)
                ) {
                    "Current financial operation requires cashier shift attribution"
                }
            })

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

    suspend fun executeCouponRedemption(c: CouponRedemptionCommand): CommandResult<CouponRedemptionResult> = try {
        c.validate()
        val existing = operationDao.getOperationById(c.operationId.value)
        if (existing != null) {
            if (existing.requestFingerprint != c.requestFingerprint) CommandResult.Conflict("Coupon redemption request changed")
            else executeAdmittedCouponRedemption(CouponRedemptionCommand.decode(existing))
        } else {
            val actions = listOfNotNull(PermissionAction.CREATE_SALE, PermissionAction.USE_COUPON,
                when (c.remainderCategory) {
                    PaymentCategory.CASH -> PermissionAction.ACCEPT_CASH
                    PaymentCategory.ONLINE -> PermissionAction.ACCEPT_ONLINE
                    PaymentCategory.CREDIT -> PermissionAction.CREATE_CREDIT_SALE
                    else -> null
                })
            var denied: CommandResult<Nothing>? = null
            for (action in actions) {
                val result = authorizeNewFinancialMutation(c.actorUserId, c.businessId, c.branchId, action, "Use Coupon")
                if (result != null) { denied = result; break }
            }
            denied ?: executeAdmittedCouponRedemption(c)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        CommandResult.Error(e)
    }

    private fun redemptionEvent(c: CouponRedemptionCommand) =
        com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity(
            "${c.operationId.value}:redemption", c.operationId.value, c.walletId,
            com.dailymoneyregister.core.model.enums.CouponLedgerType.REDEMPTION, Math.negateExact(c.amountMinor),
            c.sale.currencyCode, c.subjectId, c.authorizedUserId, c.authorizedUserName, c.occurredAt)

    private fun couponCreditRemainder(c: CouponRedemptionCommand) =
        com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity(
            "${c.operationId.value}:credit", c.operationId.value, c.customerId,
            com.dailymoneyregister.core.model.enums.CreditLedgerType.OBLIGATION,
            Math.subtractExact(c.sale.draftState.grandTotal.amount, c.amountMinor), c.sale.currencyCode,
            c.subjectId, occurredAt = c.occurredAt)

    private suspend fun redemptionEvidence(c: CouponRedemptionCommand): CouponRedemptionResult {
        val transaction = saleFinancialWriter.loadVerified(c.writerIntent())
        val wallet = checkNotNull(ledgerDao.getCouponWallet(c.customerId, c.sale.currencyCode))
        check(wallet.id == c.walletId)
        check(ledgerDao.getCouponEventsForOperation(c.operationId.value) == listOf(redemptionEvent(c)))
        val credits = ledgerDao.getCreditLedgerForCustomer(c.customerId).filter { it.operationId == c.operationId.value }
        check(credits == if (c.remainderCategory == PaymentCategory.CREDIT) listOf(couponCreditRemainder(c)) else emptyList())
        val balance = ledgerDao.getCouponBalanceAfterEvent(wallet.id, redemptionEvent(c).id)
        check(balance >= 0)
        return CouponRedemptionResult(transaction, wallet.id, c.amountMinor, c.authorizedUserName, balance)
    }

    private suspend fun executeAdmittedCouponRedemption(c: CouponRedemptionCommand): CommandResult<CouponRedemptionResult> =
        commandExecutor.execute(
            command = c,
            cashShiftId = resolveFinancialCashShiftId(c),
            replayResolver = { redemptionEvidence(c) }
        ) {
            val branch = checkNotNull(businessBranchDao.getBranchById(c.branchId))
            require(branch.businessId == c.businessId && branch.currencyCode == c.sale.currencyCode &&
                branch.currencyDecimalPlaces == c.sale.currencyDecimalPlaces)
            val customer = checkNotNull(database.customerDao().getCustomerById(c.customerId))
            require(customer.businessId == c.businessId)
            val wallet = checkNotNull(ledgerDao.getCouponWallet(c.customerId, c.sale.currencyCode))
            require(wallet.id == c.walletId && wallet.isActive)
            val user = ledgerDao.getActiveAuthorizedUsers(wallet.id).singleOrNull { it.id == c.authorizedUserId }
            requireNotNull(user) { "Select an authorized user" }
            val details = CouponAuthorizedUserDetails(user, ledgerDao.getAuthorizedUserEvents(user.id))
            require(details.isActive && details.name == c.authorizedUserName) { "Authorized user changed; select again" }
            require(ledgerDao.getCouponBalance(wallet.id) >= c.amountMinor) { "Insufficient coupon balance" }
            if (transactionDao.getTransactionById(c.subjectId) != null ||
                transactionDao.getSettlementsForCashSaleRecovery(c.operationId.value, c.subjectId).isNotEmpty() ||
                ledgerDao.getCouponEventsForOperation(c.operationId.value).isNotEmpty()) {
                throw CommandRequiresReconciliationException("Unexpected Coupon redemption evidence")
            }
            if (c.remainderCategory == PaymentCategory.CREDIT) {
                Math.addExact(ledgerDao.getCreditBalance(c.customerId, c.sale.currencyCode), couponCreditRemainder(c).amountSignedMinor)
                ledgerDao.insertCreditEvent(couponCreditRemainder(c))
            }
            saleFinancialWriter.materialize(c.writerIntent())
            ledgerDao.insertCouponEvent(redemptionEvent(c))
            redemptionEvidence(c) to ("TRANSACTION" to c.subjectId)
        }

    suspend fun executeCouponSale(command: CouponSaleCommand): CommandResult<CouponSaleResult> {
        val result = executeCouponSaleRequest(command)
        if (result is CommandResult.Error || result is CommandResult.Conflict) {
            val op = operationDao.getOperationById(command.operationId.value)
            if (op == null || (op.status == OperationStatus.FAILED && op.requestFingerprint == command.requestFingerprint)) {
                val message = when (result) {
                    is CommandResult.Error -> result.exception.message
                    is CommandResult.Conflict -> result.message
                    else -> null
                }
                return CommandResult.Error(CouponSaleRejectedException(message ?: "Coupon Sale rejected"))
            }
        }
        return result
    }

    suspend fun executeCouponTopUp(
        command: CouponTopUpCommand
    ): CommandResult<CouponTopUpResult> =
        couponFinancialRepository.executeCouponTopUp(command)

    /*
     * Recovery compatibility facade.
     *
     * recoverPendingCouponSales remains in RegisterRepository during
     * this extraction phase and delegates admitted Top Up replay to
     * the extracted Coupon financial repository.
     */
    private suspend fun executeAdmittedCouponTopUp(
        command: CouponTopUpCommand,
        recovering: Boolean = false
    ): CommandResult<CouponTopUpResult> =
        couponFinancialRepository.executeAdmittedCouponTopUp(
            command = command,
            recovering = recovering
        )
    private suspend fun executeCouponSaleRequest(command: CouponSaleCommand): CommandResult<CouponSaleResult> {
        try { command.validate() } catch (e: IllegalArgumentException) { return CommandResult.Error(e) }
        val existing = operationDao.getOperationById(command.operationId.value)
        if (existing != null) {
            if (existing.requestFingerprint != command.requestFingerprint || existing.intentPayload != command.intentPayload)
                return CommandResult.Conflict("Coupon Sale operation already belongs to a different request")
            return try { executeAdmittedCouponSale(CouponSalePayloadCodec.decode(existing)) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CommandResult.Error(e)
            }
        }
        for (action in listOf(PermissionAction.CREATE_SALE, PermissionAction.USE_COUPON,
                if (command.fundingCategory == PaymentCategory.CASH) PermissionAction.ACCEPT_CASH else PermissionAction.ACCEPT_ONLINE)) {
            authorizeNewFinancialMutation(command.actorUserId, command.businessId, command.branchId,
                action, "Coupon Sale")?.let { return it }
        }
        return executeAdmittedCouponSale(command)
    }

    private suspend fun executeAdmittedCouponSale(c: CouponSaleCommand, recovering: Boolean = false): CommandResult<CouponSaleResult> =
        commandExecutor.execute(
            command = c,
            cashShiftId = resolveFinancialCashShiftId(c),
            replayResolver = { loadCouponSaleEvidence(c) }
        ) {
            try {
            // All customer/wallet/authorized-user/ledger/funding writes share the existing command transaction.
            val branch = checkNotNull(businessBranchDao.getBranchById(c.branchId))
            require(branch.businessId == c.businessId && branch.currencyCode == c.currencyCode &&
                branch.currencyDecimalPlaces == c.currencyDecimalPlaces) { "Coupon Sale branch/currency mismatch" }
            val customerDao = database.customerDao()
            var customer = customerDao.getCustomerById(c.customerId)
            if (customer == null) {
                require(c.createCustomer) { "Select an existing customer" }
                require(customerDao.getCustomerByPhone(c.businessId, c.canonicalPhone) == null) {
                    "This phone already belongs to a customer. Search and select that customer."
                }
                val phoneUtil = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
                val phone = phoneUtil.parse(c.canonicalPhone, "ZZ")
                require(phoneUtil.isValidNumber(phone) &&
                    phoneUtil.format(phone, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164) == c.canonicalPhone) {
                    "Enter a valid international phone number"
                }
                customer = com.dailymoneyregister.core.database.entity.CustomerEntity(
                    c.customerId, c.businessId, c.canonicalPhone, c.customerName, c.occurredAt, c.occurredAt)
                customerDao.insertCustomer(customer)
            }
            require(customer.businessId == c.businessId && customer.canonicalPhone == c.canonicalPhone && customer.name == c.customerName) {
                "Customer identity changed. Select the customer again."
            }
            val oldWallet = ledgerDao.getCouponWallet(c.customerId, c.currencyCode)
            require(oldWallet == null || oldWallet.isActive) { "Coupon wallet is inactive" }
            if (transactionDao.getTransactionById(c.subjectId) != null) {
                // A complete existing materialization may be replayed; partial evidence is never repaired by guessing.
                val result = try { loadCouponSaleEvidence(c) } catch (e: Exception) {
                    throw CommandRequiresReconciliationException("Coupon purchase evidence mismatch: ${e.message}")
                }
                return@execute result to ("TRANSACTION" to c.subjectId)
            }
            if (transactionDao.getSettlementsForCashSaleRecovery(c.operationId.value, c.subjectId).isNotEmpty() ||
                transactionDao.getItemsForTransaction(c.subjectId).isNotEmpty()) {
                throw CommandRequiresReconciliationException("Partial Coupon Sale financial evidence")
            }
            // Selling the initial value is this feature. Top-up remains a separate workflow.
            require(oldWallet == null || ledgerDao.getCouponLedgerForWallet(oldWallet.id).isEmpty()) {
                "Customer already has a funded coupon wallet. Use Coupon Top-up when available."
            }
            val wallet = oldWallet ?: com.dailymoneyregister.core.database.entity.CouponWalletEntity(
                couponEvidenceId(c, "wallet"), c.customerId, c.currencyCode, true, c.occurredAt).also {
                ledgerDao.insertCouponWallet(it)
            }
            require(ledgerDao.getActiveAuthorizedUsers(wallet.id).isEmpty()) { "Coupon wallet already has authorized users" }
            (listOf(c.customerName) + c.additionalUserNames).forEachIndexed { index, name ->
                val userId = couponEvidenceId(c, "user:$index")
                ledgerDao.insertAuthorizedUser(com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity(
                    userId, wallet.id, index == 0, true, c.occurredAt))
                ledgerDao.insertAuthorizedUserEvent(com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity(
                    couponEvidenceId(c, "user-event:$index"), userId, c.operationId.value,
                    com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.ADDED, name, c.occurredAt, c.actorUserId))
            }
            saleFinancialWriter.materialize(c.writerIntent())
            ledgerDao.insertCouponEvent(com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity(
                id = couponEvidenceId(c, "purchase"), operationId = c.operationId.value, walletId = wallet.id,
                type = com.dailymoneyregister.core.model.enums.CouponLedgerType.PURCHASE, amountSignedMinor = c.amountMinor,
                currencyCode = c.currencyCode, transactionId = c.subjectId, occurredAt = c.occurredAt))
            loadCouponSaleEvidence(c) to ("TRANSACTION" to c.subjectId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (recovering) throw CommandRequiresReconciliationException("Coupon Sale recovery evidence mismatch: ${e.message}")
                throw e
            }
        }

    private fun couponEvidenceId(c: CouponSaleCommand, suffix: String): String =
        java.util.UUID.nameUUIDFromBytes("coupon:${c.operationId.value}:$suffix".toByteArray(Charsets.UTF_8)).toString()

    private suspend fun loadCouponSaleEvidence(c: CouponSaleCommand): CouponSaleResult {
        val transaction = saleFinancialWriter.loadVerified(c.writerIntent())
        val wallet = checkNotNull(ledgerDao.getCouponWallet(c.customerId, c.currencyCode))
        val expected = com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity(
            id = couponEvidenceId(c, "purchase"), operationId = c.operationId.value, walletId = wallet.id,
            type = com.dailymoneyregister.core.model.enums.CouponLedgerType.PURCHASE, amountSignedMinor = c.amountMinor,
            currencyCode = c.currencyCode, transactionId = c.subjectId, occurredAt = c.occurredAt)
        check(ledgerDao.getCouponLedgerForWallet(wallet.id).filter { it.operationId == c.operationId.value } == listOf(expected)) {
            "Coupon purchase ledger evidence mismatch"
        }
        (listOf(c.customerName) + c.additionalUserNames).forEachIndexed { index, name ->
            val expectedEvent = com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity(
                couponEvidenceId(c, "user-event:$index"), couponEvidenceId(c, "user:$index"), c.operationId.value,
                com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.ADDED, name, c.occurredAt, c.actorUserId)
            check(ledgerDao.getAuthorizedUserEvents(expectedEvent.authorizedUserId).filter { it.operationId == c.operationId.value } == listOf(expectedEvent)) {
                "Coupon purchaser/authorized-user audit mismatch"
            }
        }
        return CouponSaleResult(transaction, wallet.id, c.amountMinor)
        }

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

    suspend fun executeCouponAuthorizedUser(command: CouponAuthorizedUserCommand): CommandResult<CouponAuthorizedUserDetails> {
        return try {
            command.validate()
            val existing = operationDao.getOperationById(command.operationId.value)
            if (existing != null) {
                if (existing.requestFingerprint != command.requestFingerprint) return CommandResult.Conflict("Authorization request changed")
                executeAdmittedCouponAuthorizedUser(CouponAuthorizedUserCommand.decode(existing))
            } else {
                authorizeNewFinancialMutation(command.actorUserId, command.businessId, command.branchId,
                    PermissionAction.USE_COUPON, "Coupon authorized users")?.let { return it }
                executeAdmittedCouponAuthorizedUser(command)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CommandResult.Error(e)
        }
    }

    private fun authorizationEvent(c: CouponAuthorizedUserCommand) =
        com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity(
            "${c.operationId.value}:authorization", c.userId, c.operationId.value, c.eventType,
            c.name, c.occurredAt, c.actorUserId)

    private suspend fun authorizationEvidence(c: CouponAuthorizedUserCommand): CouponAuthorizedUserDetails {
        val wallet = checkNotNull(ledgerDao.getCouponWallet(c.customerId, c.currencyCode))
        check(wallet.id == c.walletId)
        val user = ledgerDao.getActiveAuthorizedUsers(wallet.id).single { it.id == c.userId }
        val events = ledgerDao.getAuthorizedUserEvents(user.id)
        check(events.filter { it.operationId == c.operationId.value } == listOf(authorizationEvent(c)))
        return CouponAuthorizedUserDetails(user, events)
    }

    private suspend fun executeAdmittedCouponAuthorizedUser(c: CouponAuthorizedUserCommand): CommandResult<CouponAuthorizedUserDetails> =
        commandExecutor.execute(c, replayResolver = { authorizationEvidence(c) }) {
            val customer = checkNotNull(database.customerDao().getCustomerById(c.customerId))
            require(customer.businessId == c.businessId)
            val branch = checkNotNull(businessBranchDao.getBranchById(c.branchId))
            require(branch.businessId == c.businessId && branch.currencyCode == c.currencyCode)
            val wallet = checkNotNull(ledgerDao.getCouponWallet(c.customerId, c.currencyCode))
            require(wallet.id == c.walletId && wallet.isActive)
            val users = ledgerDao.getActiveAuthorizedUsers(wallet.id)
            val existing = users.singleOrNull { it.id == c.userId }
            if (c.eventType == com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.ADDED) {
                require(existing == null && users.size < 21)
                ledgerDao.insertAuthorizedUser(com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity(
                    c.userId, wallet.id, false, true, c.occurredAt))
            } else {
                requireNotNull(existing)
                val events = ledgerDao.getAuthorizedUserEvents(existing.id)
                require(CouponAuthorizedUserDetails(existing, events).isActive) { "Authorized user is inactive" }
                require(c.occurredAt >= (events.lastOrNull()?.occurredAt ?: existing.createdAt))
                require(!existing.isPurchaser || c.eventType != com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.DEACTIVATED) {
                    "Purchaser must remain authorized"
                }
            }
            ledgerDao.insertAuthorizedUserEvent(authorizationEvent(c))
            authorizationEvidence(c) to ("COUPON_AUTHORIZED_USER" to c.userId)
        }

    suspend fun recoverPendingCouponSales() {
        val ops = operationDao
        check(ops.getSaleOperationsRequiringReconciliation().none { it.intentPayload.startsWith("REDEEM|") })
        for (op in commandExecutor.getPendingOperations().filter { it.intentPayload.startsWith("REDEEM|") }) {
            try {
                check(executeAdmittedCouponRedemption(CouponRedemptionCommand.decode(op)) is CommandResult.Success)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ops.markRequiresReconciliationIfPending(op.id, op.requestFingerprint, "Coupon redemption recovery mismatch")
                throw IllegalStateException("Coupon redemption requires reconciliation", e)
            }
        }
        check(ops.getCouponOperationsRequiringReconciliation().isEmpty()) { "Coupon Sale requires reconciliation" }
        for (op in commandExecutor.getPendingOperations().filter { it.operationType == OperationType.RECORD_COUPON_PURCHASE }) {
            try {
                if (op.intentPayload.startsWith("USERS|")) {
                    check(executeAdmittedCouponAuthorizedUser(CouponAuthorizedUserCommand.decode(op)) is CommandResult.Success)
                } else if (op.intentPayload.startsWith("TOPUP|")) {
                    val c = CouponTopUpPayloadCodec.decode(op)
                    check(executeAdmittedCouponTopUp(c, recovering = true) is CommandResult.Success) { "Coupon Top Up recovery failed" }
                } else {
                    val c = CouponSalePayloadCodec.decode(op)
                    check(executeAdmittedCouponSale(c, recovering = true) is CommandResult.Success) { "Coupon Sale recovery failed" }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ops.markRequiresReconciliationIfPending(op.id, op.requestFingerprint, "Coupon Sale recovery: ${e.message}")
                throw IllegalStateException("Coupon Sale requires reconciliation", e)
            }
        }
    }

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
     * Customer creation remains in resolveOptionalSaleCustomerId().
     */
    private val registerCustomerReadRepository =
        RegisterCustomerReadRepository(
            database
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
    private suspend fun resolveOptionalSaleCustomerId(
        businessId: String,
        customerPhone: String?,
        customerName: String?,
        occurredAt: Long
    ): String? {
        val phone = customerPhone?.trim().orEmpty()
        val name = customerName?.trim().orEmpty()

        if (phone.isEmpty()) {
            require(name.isEmpty()) {
                "Customer phone is required when customer name is provided"
            }
            return null
        }

        return com.dailymoneyregister.feature.customers.data.CustomerIdentity
            .resolveOrCreate(
                customerDao = database.customerDao(),
                businessId = businessId,
                rawPhone = phone,
                rawName = name,
                now = occurredAt
            )
            .customer
            .id
    }
    private suspend fun existingOptionalSaleCustomerId(
        businessId: String,
        customerPhone: String?
    ): String? =
        registerCustomerReadRepository
            .existingOptionalSaleCustomerId(
                businessId = businessId,
                customerPhone = customerPhone
            )
    suspend fun executeCashSale(
        command: CashSaleCommand,
        actorUser: UserAccountEntity,
        actorMembership: UserBranchMembershipEntity?
    ): CommandResult<TransactionEntity> {
        // 1. Require a live authenticated session before accepting a new cash sale.
        // Caller-supplied actor and membership objects are not proof of login.
        val session = SessionManager.currentSession.value
            ?: return CommandResult.PermissionDenied(
                "Cash sale requires an authenticated session"
            )

        if (session.user.id != actorUser.id ||
            session.user.id != command.actorUserId
        ) {
            return CommandResult.PermissionDenied(
                "Cash sale actor does not match the authenticated session"
            )
        }

        if (session.business.id != command.businessId ||
            session.branch.id != command.branchId
        ) {
            return CommandResult.PermissionDenied(
                "Cash sale business/branch does not match the authenticated session"
            )
        }

        session.membership?.let { sessionMembership ->
            if (sessionMembership.userId != actorUser.id ||
                sessionMembership.branchId != command.branchId
            ) {
                return CommandResult.PermissionDenied(
                    "Cash sale membership does not match the authenticated session"
                )
            }
        }

        // 2. BOARDLOCK: authoritative identity, branch, permission, currency and money validation.
        // Never trust only the UI/caller for financial truth.

        if (session.requiresPinChange) {
            return CommandResult.PermissionDenied("Cashier must create a new PIN before using the register")
        }

        val authoritativeActor = userDao.getUserById(command.actorUserId)
            ?: return CommandResult.PermissionDenied(
                "Cash sale actor does not exist"
            )

        if (authoritativeActor.mustChangePin) {
            return CommandResult.PermissionDenied(
                "Cashier must create a new PIN before using the register"
            )
        }

        if (!authoritativeActor.isActive) {
            return CommandResult.PermissionDenied(
                "Inactive user cannot create a Cash sale"
            )
        }

        if (authoritativeActor.businessId != command.businessId ||
            actorUser.businessId != command.businessId
        ) {
            return CommandResult.PermissionDenied(
                "Cash sale business does not match the authenticated user"
            )
        }

        val authoritativeBranch = businessBranchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(
                IllegalArgumentException("Cash sale branch does not exist")
            )

        if (authoritativeBranch.businessId != command.businessId) {
            return CommandResult.PermissionDenied(
                "Cash sale branch does not belong to the command business"
            )
        }

        val authoritativeMembership =
            userDao.getMembership(authoritativeActor.id, authoritativeBranch.id)
                ?: return CommandResult.PermissionDenied(
                    "User is not a member of the Cash sale branch"
                )

        if (actorMembership != null &&
            (actorMembership.userId != authoritativeActor.id ||
             actorMembership.branchId != authoritativeBranch.id)
        ) {
            return CommandResult.PermissionDenied(
                "Cash sale membership does not match the authenticated user/branch"
            )
        }

        try {
            PermissionChecker.assertPermitted(
                authoritativeActor,
                authoritativeMembership,
                PermissionAction.CREATE_SALE
            )
            PermissionChecker.assertPermitted(
                authoritativeActor,
                authoritativeMembership,
                PermissionAction.ACCEPT_CASH
            )
        } catch (e: PermissionDeniedException) {
            return CommandResult.PermissionDenied(
                e.message ?: "Permission denied for Cash sale"
            )
        }

        try {
            CurrencyInvariant.assertSameCurrency(
                command.currencyCode,
                authoritativeBranch.currencyCode,
                "Cash sale"
            )
        } catch (e: IllegalStateException) {
            return CommandResult.Error(e)
        }

        if (command.currencyDecimalPlaces != authoritativeBranch.currencyDecimalPlaces) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Cash sale currency precision does not match branch configuration"
                )
            )
        }

        val validatedDraft = command.draftState

        if (validatedDraft.taxRateBps !in 0..10_000) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Cash sale tax rate must be between 0 and 10,000 basis points"
                )
            )
        }

        if (validatedDraft.items.map { it.id }.toSet().size != validatedDraft.items.size) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Cash sale item IDs must be unique"
                )
            )
        }

        if (validatedDraft.subtotal.amount <= 0L) {
            return CommandResult.Error(
                IllegalArgumentException("Cash sale subtotal must be greater than zero")
            )
        }

        if (validatedDraft.grandTotal.amount <= 0L) {
            return CommandResult.Error(
                IllegalArgumentException("Cash sale grand total must be greater than zero")
            )
        }

        if (validatedDraft.txShipping.amount < 0L ||
            validatedDraft.txPacking.amount < 0L ||
            validatedDraft.itemShippingTotal.amount < 0L ||
            validatedDraft.itemPackingTotal.amount < 0L
        ) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Shipping and packing charges cannot be negative"
                )
            )
        }

        if (validatedDraft.taxAmount.amount < 0L) {
            return CommandResult.Error(
                IllegalArgumentException("Tax values cannot be negative")
            )
        }

        if (validatedDraft.isTaxEnabled && validatedDraft.taxRateBps <= 0) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Enabled tax must have a positive tax rate"
                )
            )
        }

        if (!validatedDraft.isTaxEnabled &&
            validatedDraft.taxAmount.amount != 0L
        ) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Disabled tax must not create a tax amount"
                )
            )
        }

        for (item in validatedDraft.items) {
            if (item.quantity <= 0) {
                return CommandResult.Error(
                    IllegalArgumentException(
                        "Item quantity must be greater than zero"
                    )
                )
            }

            if (item.unitPrice.amount < 0L ||
                item.itemPackingCharge.amount < 0L ||
                item.itemShippingCharge.amount < 0L ||
                item.lineTotal.amount < 0L
            ) {
                return CommandResult.Error(
                    IllegalArgumentException(
                        "Item prices and charges cannot be negative"
                    )
                )
            }
        }

        // 2. Execute via universal CommandExecutor with type-safe replay resolver
        return commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = { op ->
                val txId = op.resultReferenceId ?: command.transactionIdVal.value
                transactionDao.getTransactionById(txId)
                    ?: throw IllegalStateException("Replay failed: Transaction entity for '$txId' not found in database")
            }
        ) {
            val customerId = resolveOptionalSaleCustomerId(
                businessId = command.businessId,
                customerPhone = command.customerPhone,
                customerName = command.customerName,
                occurredAt = command.occurredAt
            )

            saleFinancialWriter.materialize(
                SaleFinancialWriter.Intent(
                    operationId = command.operationId.value,
                    transactionId = command.transactionIdVal.value,
                    branchId = command.branchId,
                    actorUserId = command.actorUserId,
                    deviceId = command.deviceId,
                    occurredAt = command.occurredAt,
                    currencyCode = command.currencyCode,
                    currencyDecimalPlaces = command.currencyDecimalPlaces,
                    draft = command.draftState,
                    funding = SaleFinancialWriter.FundingIdentity(
                        PaymentCategory.CASH,
                        PaymentChannel.CASH_TENDER,
                        ConfirmationType.CASHIER_CONFIRMED
                    ),
                    customerId = customerId
                )
            )
        }
    }

    private fun creditSaleIntent(
        command: CreditSaleCommand,
        customerId: String
    ) = SaleFinancialWriter.Intent(
        operationId = command.operationId.value,
        transactionId = command.transactionIdVal.value,
        branchId = command.branchId,
        actorUserId = command.actorUserId,
        deviceId = command.deviceId,
        occurredAt = command.occurredAt,
        currencyCode = command.currencyCode,
        currencyDecimalPlaces = command.currencyDecimalPlaces,
        draft = command.draftState,
        funding = SaleFinancialWriter.FundingIdentity(
            PaymentCategory.CREDIT,
            PaymentChannel.CREDIT_ACCOUNT,
            ConfirmationType.CASHIER_CONFIRMED
        ),
        customerId = customerId
    )

    private fun creditSaleObligation(
        command: CreditSaleCommand,
        customerId: String
    ) = com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity(
        id = "${command.operationId.value}:credit",
        operationId = command.operationId.value,
        customerId = customerId,
        type = com.dailymoneyregister.core.model.enums.CreditLedgerType.OBLIGATION,
        amountSignedMinor = command.draftState.grandTotal.amount,
        currencyCode = command.currencyCode,
        transactionId = command.transactionIdVal.value,
        occurredAt = command.occurredAt
    )

    /*
     * BOARDLOCK GOD-file decomposition.
     *
     * Credit Invoice is a post-save read-only projection.
     * Credit Sale execution and evidence verification remain in the
     * existing authoritative financial path.
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
    private suspend fun loadCreditSaleEvidence(
        command: CreditSaleCommand
    ): TransactionEntity {
        val transaction = checkNotNull(
            transactionDao.getTransactionById(command.transactionIdVal.value)
        ) { "Credit Sale transaction evidence is missing" }

        val customerId = checkNotNull(transaction.customerId) {
            "Credit Sale transaction customer is missing"
        }

        val customer = checkNotNull(
            database.customerDao().getCustomerById(customerId)
        ) { "Credit Sale customer evidence is missing" }

        check(customer.businessId == command.businessId) {
            "Credit Sale customer business mismatch"
        }

        check(
            customer.canonicalPhone ==
                com.dailymoneyregister.feature.customers.data.CustomerIdentity
                    .canonicalPhone(command.customerPhone)
        ) {
            "Credit Sale customer identity mismatch"
        }

        saleFinancialWriter.loadVerified(
            creditSaleIntent(command, customerId)
        )

        val expected =
            creditSaleObligation(command, customerId)

        val actual =
            ledgerDao.getCreditLedgerForCustomer(customerId)
                .filter { it.operationId == command.operationId.value }

        check(actual == listOf(expected)) {
            "Credit Sale obligation evidence mismatch"
        }

        return transaction
    }

    suspend fun executeCreditSale(
        command: CreditSaleCommand
    ): CommandResult<TransactionEntity> {
        return try {
            // Validate identity before admitting a new financial operation.
            com.dailymoneyregister.feature.customers.data.CustomerIdentity
                .canonicalPhone(command.customerPhone)

            require(command.customerName.trim().isNotEmpty()) {
                "Enter the customer name"
            }

            val existing =
                operationDao
                    .getOperationById(command.operationId.value)

            if (existing != null) {
                if (
                    existing.requestFingerprint != command.requestFingerprint ||
                    existing.intentPayload != command.intentPayload
                ) {
                    return CommandResult.Conflict(
                        "Credit Sale operation already belongs to a different request"
                    )
                }

                return executeAdmittedCreditSale(
                    CreditSalePayloadCodec.decode(existing)
                )
            }

            authorizeNewFinancialMutation(
                command.actorUserId,
                command.businessId,
                command.branchId,
                PermissionAction.CREATE_SALE,
                "Credit Sale"
            )?.let { return it }

            authorizeNewFinancialMutation(
                command.actorUserId,
                command.businessId,
                command.branchId,
                PermissionAction.CREATE_CREDIT_SALE,
                "Credit Sale"
            )?.let { return it }

            executeAdmittedCreditSale(command)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    private suspend fun executeAdmittedCreditSale(
        command: CreditSaleCommand
    ): CommandResult<TransactionEntity> =
        commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = {
                loadCreditSaleEvidence(command)
            }
        ) {
            val branch = checkNotNull(
                businessBranchDao.getBranchById(command.branchId)
            ) { "Credit Sale branch does not exist" }

            require(
                branch.businessId == command.businessId &&
                    branch.currencyCode == command.currencyCode &&
                    branch.currencyDecimalPlaces ==
                        command.currencyDecimalPlaces
            ) {
                "Credit Sale branch/currency mismatch"
            }

            val resolved =
                com.dailymoneyregister.feature.customers.data.CustomerIdentity
                    .resolveOrCreate(
                        customerDao = database.customerDao(),
                        businessId = command.businessId,
                        rawPhone = command.customerPhone,
                        rawName = command.customerName,
                        now = command.occurredAt
                    )

            val customer = resolved.customer
            val txId = command.transactionIdVal.value

            val existingTransaction =
                transactionDao.getTransactionById(txId)

            val existingItems =
                transactionDao.getItemsForTransaction(txId)

            val existingSettlements =
                transactionDao.getSettlementsForCashSaleRecovery(
                    command.operationId.value,
                    txId
                )

            val existingCredit =
                ledgerDao.getCreditLedgerForCustomer(customer.id)
                    .filter {
                        it.operationId == command.operationId.value
                    }

            if (
                existingTransaction != null ||
                existingItems.isNotEmpty() ||
                existingSettlements.isNotEmpty() ||
                existingCredit.isNotEmpty()
            ) {
                throw CommandRequiresReconciliationException(
                    "Unexpected existing Credit Sale financial evidence"
                )
            }

            Math.addExact(
                ledgerDao.getCreditBalance(
                    customer.id,
                    command.currencyCode
                ),
                command.draftState.grandTotal.amount
            )

            saleFinancialWriter.materialize(
                creditSaleIntent(command, customer.id)
            )

            ledgerDao.insertCreditEvent(
                creditSaleObligation(command, customer.id)
            )

            val transaction =
                loadCreditSaleEvidence(command)

            transaction to ("TRANSACTION" to txId)
        }


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

    suspend fun executeOnlineSale(command: OnlineSaleCommand): CommandResult<TransactionEntity> {
        val operationDao = operationDao
        val existing = operationDao.getOperationById(command.operationId.value)

        if (existing != null) {
            if (existing.requestFingerprint != command.requestFingerprint) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '${command.operationId.value}' has already been used with a different request fingerprint."
                )
            }
            if (existing.intentPayload != command.intentPayload ||
                existing.commandSchemaVersion != command.commandSchemaVersion
            ) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '${command.operationId.value}' has different durable intent."
                )
            }
            val durable = try {
                OnlineSalePayloadCodec.decode(existing)
            } catch (error: RuntimeException) {
                if (existing.status == OperationStatus.PENDING) {
                    operationDao.markRequiresReconciliationIfPending(
                        existing.id,
                        existing.requestFingerprint,
                        "Invalid durable Online Sale intent: ${error.message ?: "decode failure"}"
                    )
                    return CommandResult.Conflict("Online Sale requires financial reconciliation")
                }
                return CommandResult.Error(error)
            }
            return executeAdmittedOnlineSale(durable)
        }

        authorizeNewFinancialMutation(
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            action = PermissionAction.CREATE_SALE,
            actionLabel = "Online sale"
        )?.let { return it }
        authorizeNewFinancialMutation(
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            action = PermissionAction.ACCEPT_ONLINE,
            actionLabel = "Online sale"
        )?.let { return it }

        return executeAdmittedOnlineSale(command)
    }

    private suspend fun executeAdmittedOnlineSale(
        command: OnlineSaleCommand
    ): CommandResult<TransactionEntity> {
        val validation = validateOnlineSale(command)
        if (validation != null) return validation

        return try {
            commandExecutor.execute(
                command = command,
                cashShiftId = resolveFinancialCashShiftId(command),
                replayResolver = {
                    val customerId = existingOptionalSaleCustomerId(
                        businessId = command.businessId,
                        customerPhone = command.customerPhone
                    )

                    saleFinancialWriter.loadVerified(
                        SaleFinancialWriter.Intent(
                            operationId = command.operationId.value,
                            transactionId = command.transactionIdVal.value,
                            branchId = command.branchId,
                            actorUserId = command.actorUserId,
                            deviceId = command.deviceId,
                            occurredAt = command.occurredAt,
                            currencyCode = command.currencyCode,
                            currencyDecimalPlaces = command.currencyDecimalPlaces,
                            draft = command.draftState,
                            funding = onlineFunding(),
                            customerId = customerId
                        )
                    )
                }
            ) {
                val customerId = resolveOptionalSaleCustomerId(
                    businessId = command.businessId,
                    customerPhone = command.customerPhone,
                    customerName = command.customerName,
                    occurredAt = command.occurredAt
                )

                saleFinancialWriter.materialize(
                    SaleFinancialWriter.Intent(
                        operationId = command.operationId.value,
                        transactionId = command.transactionIdVal.value,
                        branchId = command.branchId,
                        actorUserId = command.actorUserId,
                        deviceId = command.deviceId,
                        occurredAt = command.occurredAt,
                        currencyCode = command.currencyCode,
                        currencyDecimalPlaces = command.currencyDecimalPlaces,
                        draft = command.draftState,
                        funding = onlineFunding(),
                        customerId = customerId
                    )
                )
            }
        } catch (error: RuntimeException) {
            CommandResult.Error(error)
        }
    }

    private fun onlineFunding() = SaleFinancialWriter.FundingIdentity(
        PaymentCategory.ONLINE,
        PaymentChannel.MANUAL_QR,
        ConfirmationType.CASHIER_CONFIRMED
    )

    private suspend fun validateOnlineSale(
        command: OnlineSaleCommand
    ): CommandResult<Nothing>? {
        val branch = businessBranchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(IllegalArgumentException("Online sale branch does not exist"))
        try {
            CurrencyInvariant.assertSameCurrency(command.currencyCode, branch.currencyCode, "Online sale")
        } catch (error: IllegalStateException) {
            return CommandResult.Error(error)
        }
        if (command.currencyDecimalPlaces != branch.currencyDecimalPlaces) {
            return CommandResult.Error(IllegalArgumentException("Online sale currency precision does not match branch configuration"))
        }
        val draft = command.draftState
        if (draft.taxRateBps !in 0..10_000) return CommandResult.Error(IllegalArgumentException("Online sale tax rate is invalid"))
        if (draft.items.map { it.id }.toSet().size != draft.items.size) return CommandResult.Error(IllegalArgumentException("Online sale item IDs must be unique"))
        if (draft.subtotal.amount <= 0L) return CommandResult.Error(IllegalArgumentException("Online sale subtotal must be greater than zero"))
        if (draft.grandTotal.amount <= 0L) return CommandResult.Error(IllegalArgumentException("Online sale grand total must be greater than zero"))
        if (draft.txShipping.amount < 0L || draft.txPacking.amount < 0L || draft.itemShippingTotal.amount < 0L || draft.itemPackingTotal.amount < 0L) {
            return CommandResult.Error(IllegalArgumentException("Shipping and packing charges cannot be negative"))
        }
        if (draft.taxAmount.amount < 0L || (draft.isTaxEnabled && draft.taxRateBps <= 0) || (!draft.isTaxEnabled && draft.taxAmount.amount != 0L)) {
            return CommandResult.Error(IllegalArgumentException("Online sale tax values are invalid"))
        }
        for (item in draft.items) {
            if (item.quantity <= 0 || item.unitPrice.amount < 0L || item.itemPackingCharge.amount < 0L || item.itemShippingCharge.amount < 0L || item.lineTotal.amount < 0L) {
                return CommandResult.Error(IllegalArgumentException("Online sale item values are invalid"))
            }
        }
        return null
    }

    suspend fun executeAmountCorrection(
        command: AmountCorrectionCommand
    ): CommandResult<AmountCorrectionExecutionResult> =
        amountCorrectionFinancialRepository.executeAmountCorrection(command)

    suspend fun recoverPendingAmountCorrections():
        List<AmountCorrectionExecutionResult> =
        amountCorrectionFinancialRepository.recoverPendingAmountCorrections()

    suspend fun executePaymentMethodChange(
        command: PaymentMethodChangeCommand
    ): CommandResult<PaymentMethodChangeExecutionResult> {
        val operationDao = operationDao
        val existingOperation = operationDao.getOperationById(command.operationId.value)

        if (existingOperation != null) {
            if (existingOperation.requestFingerprint != command.requestFingerprint) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '${command.operationId.value}' has already been used " +
                        "with a different request fingerprint."
                )
            }

            val durableCommand = try {
                decodePersistedPaymentMethodChangeCommand(existingOperation)
            } catch (error: RuntimeException) {
                if (existingOperation.status == OperationStatus.PENDING) {
                    return reconcilePendingPaymentMethodChange(
                        existingOperation,
                        "Invalid durable Payment Method Change intent: ${error.message ?: "decode failure"}"
                    )
                }
                return CommandResult.Error(error)
            }

            if (
                existingOperation.status == OperationStatus.PENDING &&
                durableCommand.financialPrecondition == null
            ) {
                return reconcilePendingPaymentMethodChange(
                    existingOperation,
                    "PENDING Payment Method Change lacks a financial-state precondition"
                )
            }

            return executeAdmittedPaymentMethodChange(durableCommand)
        }

        authorizeNewFinancialMutation(
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            action = PermissionAction.CHANGE_PAYMENT_METHOD,
            actionLabel = "Payment Method Change"
        )?.let { return it }

        val admittedCommand = try {
            preparePaymentMethodChangeAdmission(command)
        } catch (error: RuntimeException) {
            return CommandResult.Error(error)
        }

        return executeAdmittedPaymentMethodChange(admittedCommand)
    }

    private suspend fun preparePaymentMethodChangeAdmission(
        command: PaymentMethodChangeCommand
    ): PaymentMethodChangeCommand {
        val record = transactionDao.getFinancialRecordById(command.subjectId)
            ?: throw IllegalArgumentException(
                "Transaction '${command.subjectId}' was not found"
            )

        if (record.transaction.branchId != command.branchId) {
            throw IllegalStateException("Transaction belongs to another branch")
        }

        PaymentMethodChangeFinancialIntegrity.requireChronologicalAppend(
            record = record,
            occurredAt = command.occurredAt
        )

        val allocations = ledgerDao.getCreditRepaymentAllocationsForTransaction(command.subjectId)
        val validated = PaymentMethodChangeFinancialIntegrity.validate(
            record = record,
            creditAllocations = allocations
        )

        if (
            validated.currentCategory == command.newCategory &&
            validated.currentChannel == command.newChannel
        ) {
            throw PaymentMethodChangeNoOpException(command.subjectId)
        }

        val fingerprint = PaymentMethodChangeFinancialIntegrity.fingerprint(
            transactionId = command.subjectId,
            record = record,
            creditAllocations = allocations
        )

        return command.copy(
            financialPrecondition = PaymentMethodChangeFinancialPrecondition(
                stateFingerprint = fingerprint,
                expectedCurrentCategory = validated.currentCategory,
                expectedCurrentChannel = validated.currentChannel,
                expectedReclassificationAmountMinor = validated.reclassificationAmountMinor
            )
        )
    }

    private fun decodePersistedPaymentMethodChangeCommand(
        operation: OperationEntity
    ): PaymentMethodChangeCommand {
        check(operation.operationType == OperationType.RECORD_PAYMENT_METHOD_CHANGE) {
            "Durable Payment Method Change operation type mismatch"
        }
        check(operation.subjectType == SubjectType.TRANSACTION) {
            "Durable Payment Method Change subject type mismatch"
        }
        check(operation.transactionId == operation.subjectId) {
            "Durable Payment Method Change transaction identity mismatch"
        }

        val branchId = operation.branchId
            ?: throw IllegalStateException("Durable Payment Method Change has no branch")
        val payload = PaymentMethodChangePayloadCodec.decode(operation.intentPayload)

        check(operation.commandSchemaVersion == payload.schemaVersion) {
            "Durable Payment Method Change schema version mismatch"
        }

        val command = PaymentMethodChangeCommand(
            operationId = OperationId(operation.id),
            businessId = operation.businessId,
            branchId = branchId,
            subjectId = operation.subjectId,
            actorUserId = operation.actorUserId,
            deviceId = operation.deviceId,
            occurredAt = operation.occurredAt,
            newCategory = payload.newCategory,
            newChannel = payload.newChannel,
            reason = payload.reason,
            financialPrecondition = payload.financialPrecondition
        )

        check(command.requestFingerprint == operation.requestFingerprint) {
            "Durable Payment Method Change request fingerprint mismatch"
        }

        if (operation.status == OperationStatus.PENDING) {
            check(
                operation.resultReferenceType == null &&
                    operation.resultReferenceId == null
            ) {
                "Pending Payment Method Change already has a result reference"
            }
        }

        return command
    }

    private suspend fun reconcilePendingPaymentMethodChange(
        operation: OperationEntity,
        reason: String
    ): CommandResult<PaymentMethodChangeExecutionResult> = database.withTransaction {
        val latest = operationDao.getOperationById(operation.id)
            ?: return@withTransaction CommandResult.Error(
                IllegalStateException(
                    "Pending Payment Method Change operation '${operation.id}' disappeared"
                )
            )

        if (latest.requestFingerprint != operation.requestFingerprint) {
            return@withTransaction CommandResult.Conflict(
                "Conflict: Payment Method Change intent changed during reconciliation."
            )
        }

        when (latest.status) {
            OperationStatus.SUCCEEDED -> CommandResult.Success(
                data = resolvePaymentMethodChangeReplay(latest, latest.subjectId),
                isIdempotentReplay = true
            )

            OperationStatus.FAILED -> CommandResult.Conflict(
                "Conflict: Operation ID '${latest.id}' is already FAILED."
            )

            OperationStatus.REQUIRES_RECONCILIATION -> CommandResult.Conflict(
                "Conflict: Operation ID '${latest.id}' requires reconciliation."
            )

            OperationStatus.PENDING -> {
                val changed = operationDao.markRequiresReconciliationIfPending(
                    operationId = latest.id,
                    requestFingerprint = latest.requestFingerprint,
                    reason = reason
                )
                check(changed == 1) {
                    "Payment Method Change reconciliation transition failed"
                }
                CommandResult.Conflict(
                    "Conflict: Operation ID '${latest.id}' requires reconciliation: $reason"
                )
            }
        }
    }

    private suspend fun executeAdmittedPaymentMethodChange(
        command: PaymentMethodChangeCommand
    ): CommandResult<PaymentMethodChangeExecutionResult> {
        return commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = { operation ->
                resolvePaymentMethodChangeReplay(operation, command.subjectId)
            }
        ) {
            val precondition = command.financialPrecondition
                ?: throw CommandRequiresReconciliationException(
                    "PENDING Payment Method Change lacks a financial-state precondition"
                )

            val record = transactionDao.getFinancialRecordById(command.subjectId)
            val allocations = ledgerDao.getCreditRepaymentAllocationsForTransaction(command.subjectId)
            val currentFingerprint = PaymentMethodChangeFinancialIntegrity.fingerprint(
                transactionId = command.subjectId,
                record = record,
                creditAllocations = allocations
            )

            if (currentFingerprint != precondition.stateFingerprint) {
                throw CommandRequiresReconciliationException(
                    "Payment Method Change financial state changed after durable admission"
                )
            }

            val authoritativeRecord = record
                ?: throw IllegalArgumentException(
                    "Transaction '${command.subjectId}' was not found"
                )
            val transaction = authoritativeRecord.transaction

            PaymentMethodChangeFinancialIntegrity.requireChronologicalAppend(
                record = authoritativeRecord,
                occurredAt = command.occurredAt
            )

            if (transaction.branchId != command.branchId) {
                throw IllegalStateException("Transaction belongs to another branch")
            }

            val validated = PaymentMethodChangeFinancialIntegrity.validate(
                record = authoritativeRecord,
                creditAllocations = allocations
            )

            if (
                validated.currentCategory != precondition.expectedCurrentCategory ||
                validated.currentChannel != precondition.expectedCurrentChannel ||
                validated.reclassificationAmountMinor != precondition.expectedReclassificationAmountMinor
            ) {
                throw CommandRequiresReconciliationException(
                    "Payment Method Change financial expectation no longer matches durable intent"
                )
            }

            if (
                validated.currentCategory == command.newCategory &&
                validated.currentChannel == command.newChannel
            ) {
                throw PaymentMethodChangeNoOpException(transaction.id)
            }

            val amount = validated.reclassificationAmountMinor
            val event = PaymentMethodChangeEventEntity(
                id = IdGenerator.newId(),
                transactionId = transaction.id,
                operationId = command.operationId.value,
                previousMethod = validated.currentCategory,
                previousChannel = validated.currentChannel,
                newMethod = command.newCategory,
                newChannel = command.newChannel,
                reason = command.reason,
                actorUserId = command.actorUserId,
                occurredAt = command.occurredAt
            )

            val debit = SettlementEntryEntity(
                id = IdGenerator.newId(),
                operationId = command.operationId.value,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transaction.id,
                transactionId = transaction.id,
                entryPurpose = EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION,
                category = validated.currentCategory,
                channel = validated.currentChannel,
                amountSignedMinor = Math.negateExact(amount),
                currencyCode = transaction.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = command.occurredAt,
                actorUserId = command.actorUserId
            )

            val credit = SettlementEntryEntity(
                id = IdGenerator.newId(),
                operationId = command.operationId.value,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transaction.id,
                transactionId = transaction.id,
                entryPurpose = EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION,
                category = command.newCategory,
                channel = command.newChannel,
                amountSignedMinor = amount,
                currencyCode = transaction.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = command.occurredAt,
                actorUserId = command.actorUserId
            )

            transactionDao.insertPaymentMethodChange(event)
            transactionDao.insertSettlements(listOf(debit, credit))

            Pair(
                PaymentMethodChangeExecutionResult(
                    transactionId = transaction.id,
                    paymentMethodChangeEventId = event.id,
                    previousCategory = validated.currentCategory,
                    previousChannel = validated.currentChannel,
                    newCategory = command.newCategory,
                    newChannel = command.newChannel,
                    reclassificationAmountMinor = amount
                ),
                Pair("PAYMENT_METHOD_CHANGE_EVENT", event.id)
            )
        }
    }

    private suspend fun resolvePaymentMethodChangeReplay(
        operation: OperationEntity,
        expectedTransactionId: String
    ): PaymentMethodChangeExecutionResult {
        check(operation.operationType == OperationType.RECORD_PAYMENT_METHOD_CHANGE) {
            "Payment Method Change replay operation type mismatch"
        }
        check(operation.subjectType == SubjectType.TRANSACTION) {
            "Payment Method Change replay subject type mismatch"
        }
        check(
            operation.subjectId == expectedTransactionId &&
                operation.transactionId == expectedTransactionId
        ) {
            "Payment Method Change replay transaction identity mismatch"
        }

        val payload = PaymentMethodChangePayloadCodec.decode(operation.intentPayload)
        check(payload.schemaVersion == operation.commandSchemaVersion) {
            "Payment Method Change replay payload version mismatch"
        }
        val precondition = payload.financialPrecondition
            ?: throw IllegalStateException(
                "Succeeded Payment Method Change lacks durable financial expectation"
            )

        val event = transactionDao.getPaymentMethodChanges(expectedTransactionId)
            .singleOrNull { it.operationId == operation.id }
            ?: throw IllegalStateException("Payment Method Change replay event is missing or ambiguous")

        check(
            event.transactionId == expectedTransactionId &&
                event.operationId == operation.id
        ) {
            "Payment Method Change replay event identity mismatch"
        }
        check(
            event.previousMethod == precondition.expectedCurrentCategory &&
                event.previousChannel == precondition.expectedCurrentChannel
        ) {
            "Payment Method Change replay previous identity mismatch"
        }
        check(
            event.newMethod == payload.newCategory &&
                event.newChannel == payload.newChannel
        ) {
            "Payment Method Change replay destination identity mismatch"
        }
        check(
            event.reason == payload.reason &&
                event.actorUserId == operation.actorUserId &&
                event.occurredAt == operation.occurredAt
        ) {
            "Payment Method Change replay event audit fields mismatch"
        }
        check(
            operation.resultReferenceType == "PAYMENT_METHOD_CHANGE_EVENT" &&
                operation.resultReferenceId == event.id
        ) {
            "Payment Method Change replay result reference mismatch"
        }

        val transaction = transactionDao.getTransactionById(expectedTransactionId)
            ?: throw IllegalStateException("Payment Method Change replay transaction is missing")
        val settlements = transactionDao.getSettlementsForOperation(operation.id)
        check(settlements.size == 2) {
            "Payment Method Change replay settlement count mismatch"
        }

        settlements.forEach { settlement ->
            check(
                settlement.subjectType == SubjectType.TRANSACTION &&
                    settlement.subjectId == expectedTransactionId &&
                    settlement.transactionId == expectedTransactionId &&
                    settlement.entryPurpose == EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION &&
                    settlement.currencyCode == transaction.currencyCode &&
                    settlement.confirmationType == ConfirmationType.CASHIER_CONFIRMED &&
                    settlement.actorUserId == event.actorUserId &&
                    settlement.occurredAt == event.occurredAt
            ) {
                "Payment Method Change replay settlement audit mismatch"
            }
        }

        val amount = precondition.expectedReclassificationAmountMinor
        val debit = settlements.singleOrNull {
            it.amountSignedMinor == Math.negateExact(amount) &&
                it.category == precondition.expectedCurrentCategory &&
                it.channel == precondition.expectedCurrentChannel
        }
        val credit = settlements.singleOrNull {
            it.amountSignedMinor == amount &&
                it.category == payload.newCategory &&
                it.channel == payload.newChannel
        }
        check(debit != null && credit != null) {
            "Payment Method Change replay settlement identity/amount mismatch"
        }

        return PaymentMethodChangeExecutionResult(
            transactionId = expectedTransactionId,
            paymentMethodChangeEventId = event.id,
            previousCategory = precondition.expectedCurrentCategory,
            previousChannel = precondition.expectedCurrentChannel,
            newCategory = payload.newCategory,
            newChannel = payload.newChannel,
            reclassificationAmountMinor = amount
        )
    }

    suspend fun recoverPendingPaymentMethodChanges():
        List<PaymentMethodChangeExecutionResult> {
        val operationDao = operationDao
        val recovered = mutableListOf<PaymentMethodChangeExecutionResult>()
        val pendingOperations = commandExecutor.getPendingOperations()
            .filter { it.operationType == OperationType.RECORD_PAYMENT_METHOD_CHANGE }

        for (candidate in pendingOperations) {
            val current = operationDao.getOperationById(candidate.id)
                ?: throw IllegalStateException(
                    "Pending Payment Method Change operation unavailable during recovery"
                )

            if (current.status == OperationStatus.SUCCEEDED) {
                recovered += resolvePaymentMethodChangeReplay(current, current.subjectId)
                continue
            }

            check(current.status == OperationStatus.PENDING) {
                "Payment Method Change operation is no longer pending"
            }

            val command = try {
                decodePersistedPaymentMethodChangeCommand(current)
            } catch (error: RuntimeException) {
                val reconciled = reconcilePendingPaymentMethodChange(
                    current,
                    "Invalid durable Payment Method Change intent: ${error.message ?: "decode failure"}"
                )
                throw IllegalStateException(
                    (reconciled as? CommandResult.Conflict)?.message
                        ?: "Payment Method Change requires financial reconciliation",
                    error
                )
            }

            if (command.financialPrecondition == null) {
                val reconciled = reconcilePendingPaymentMethodChange(
                    current,
                    "PENDING Payment Method Change lacks a financial-state precondition"
                )
                throw IllegalStateException(
                    (reconciled as? CommandResult.Conflict)?.message
                        ?: "Payment Method Change requires financial reconciliation"
                )
            }

            when (val result = executeAdmittedPaymentMethodChange(command)) {
                is CommandResult.Success -> recovered += result.data
                is CommandResult.Conflict -> throw IllegalStateException(result.message)
                is CommandResult.PermissionDenied -> throw IllegalStateException(
                    "Admitted Payment Method Change recovery unexpectedly requested permission"
                )
                is CommandResult.Error -> throw result.exception
            }
        }

        return recovered
    }

    suspend fun executeRefund(
        command: RefundCommand
    ): CommandResult<RefundExecutionResult> =
        refundFinancialRepository.executeRefund(command)

    suspend fun recoverPendingRefunds():
        List<RefundExecutionResult> =
        refundFinancialRepository.recoverPendingRefunds()
    suspend fun executeVoid(command: VoidCommand): CommandResult<VoidExecutionResult> {
        val operationDao = operationDao
        val existingOperation =
            operationDao.getOperationById(command.operationId.value)

        if (existingOperation != null) {
            if (existingOperation.requestFingerprint != command.requestFingerprint) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '${command.operationId.value}' has already been used " +
                        "with a different request fingerprint."
                )
            }

            val durableCommand = try {
                decodePersistedVoidCommand(existingOperation)
            } catch (error: RuntimeException) {
                if (existingOperation.status == OperationStatus.PENDING) {
                    return reconcilePendingVoid(
                        existingOperation,
                        "Invalid durable Void intent: ${error.message ?: "decode failure"}"
                    )
                }
                return CommandResult.Error(error)
            }

            /*
             * PENDING means this exact operation was already admitted.
             * Current mutable permissions must not redefine that intent.
             *
             * Legacy v1 PENDING Void operations have no financial-state
             * precondition, so they are not safe to guess/replay.
             */
            if (
                existingOperation.status == OperationStatus.PENDING &&
                durableCommand.financialPrecondition == null
            ) {
                return reconcilePendingVoid(
                    existingOperation,
                    "Legacy PENDING Void lacks a financial-state precondition"
                )
            }

            return executeAdmittedVoid(durableCommand)
        }

        /*
         * Only a NEW Void operation consults current authorization.
         */
        authorizeNewFinancialMutation(
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            action = PermissionAction.VOID,
            actionLabel = "Void"
        )?.let { return it }

        /*
         * Bind the admitted operation to the exact authoritative financial
         * history that existed after authorization and before durable intent
         * is persisted. executeAdmittedVoid() verifies the same fingerprint
         * again inside CommandExecutor's financial transaction.
         */
        val admittedCommand = prepareVoidAdmission(command)
        return executeAdmittedVoid(admittedCommand)
    }

    private suspend fun prepareVoidAdmission(
        command: VoidCommand
    ): VoidCommand {
        val record = transactionDao.getFinancialRecordById(command.subjectId)
        val allocations =
            ledgerDao.getCreditRepaymentAllocationsForTransaction(
                command.subjectId
            )

        val stateFingerprint =
            VoidFinancialIntegrity.fingerprint(
                transactionId = command.subjectId,
                record = record,
                creditAllocations = allocations
            )

        /*
         * Preserve the existing behavior that financially invalid Void
         * attempts become durable FAILED operations after admission.
         *
         * Therefore admission always stores the raw-state fingerprint, while
         * the executable amount/channel expectation is populated only when the
         * current state already validates cleanly.
         */
        val validated =
            if (
                record != null &&
                record.transaction.branchId == command.branchId
            ) {
                try {
                    VoidFinancialIntegrity.validate(
                        record = record,
                        creditAllocations = allocations
                    )
                } catch (_: RuntimeException) {
                    null
                }
            } else {
                null
            }

        return command.copy(
            financialPrecondition = VoidFinancialPrecondition(
                stateFingerprint = stateFingerprint,
                expectedReversalAmountMinor =
                    validated?.reversibleAmountMinor,
                fundingCategory = validated?.fundingCategory,
                fundingChannel = validated?.fundingChannel
            )
        )
    }

    private fun decodePersistedVoidCommand(
        operation: OperationEntity
    ): VoidCommand {
        check(operation.operationType == OperationType.RECORD_VOID) {
            "Durable Void operation type mismatch"
        }
        check(operation.subjectType == SubjectType.TRANSACTION) {
            "Durable Void subject type mismatch"
        }
        check(operation.transactionId == operation.subjectId) {
            "Durable Void transaction identity mismatch"
        }

        val branchId = operation.branchId
            ?: throw IllegalStateException("Durable Void has no branch")

        val payload = VoidPayloadCodec.decode(operation.intentPayload)

        check(operation.commandSchemaVersion == payload.schemaVersion) {
            "Durable Void schema version mismatch"
        }

        val command = VoidCommand(
            operationId = OperationId(operation.id),
            businessId = operation.businessId,
            branchId = branchId,
            subjectId = operation.subjectId,
            actorUserId = operation.actorUserId,
            deviceId = operation.deviceId,
            occurredAt = operation.occurredAt,
            reason = payload.reason,
            financialPrecondition = payload.financialPrecondition
        )

        check(command.requestFingerprint == operation.requestFingerprint) {
            "Durable Void request fingerprint mismatch"
        }

        if (operation.status == OperationStatus.PENDING) {
            check(
                operation.resultReferenceType == null &&
                    operation.resultReferenceId == null
            ) {
                "Pending Void already has a result reference"
            }
        }

        return command
    }

    private suspend fun reconcilePendingVoid(
        operation: OperationEntity,
        reason: String
    ): CommandResult<VoidExecutionResult> {
        return database.withTransaction {
            val latest =
                operationDao.getOperationById(operation.id)
                    ?: return@withTransaction CommandResult.Error(
                        IllegalStateException(
                            "Pending Void operation '${operation.id}' disappeared"
                        )
                    )

            if (latest.requestFingerprint != operation.requestFingerprint) {
                return@withTransaction CommandResult.Conflict(
                    "Conflict: Void intent changed during reconciliation."
                )
            }

            when (latest.status) {
                OperationStatus.SUCCEEDED -> {
                    CommandResult.Success(
                        data = resolveVoidReplay(
                            operation = latest,
                            expectedTransactionId = latest.subjectId
                        ),
                        isIdempotentReplay = true
                    )
                }

                OperationStatus.FAILED -> {
                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' is already FAILED."
                    )
                }

                OperationStatus.REQUIRES_RECONCILIATION -> {
                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' requires reconciliation."
                    )
                }

                OperationStatus.PENDING -> {
                    val changed =
                        operationDao
                            .markRequiresReconciliationIfPending(
                                operationId = latest.id,
                                requestFingerprint = latest.requestFingerprint,
                                reason = reason
                            )

                    check(changed == 1) {
                        "Void reconciliation transition failed"
                    }

                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' requires reconciliation: $reason"
                    )
                }
            }
        }
    }

    /**
     * Executes a Void whose durable intent is already admitted.
     *
     * Authorization of a NEW operation happens before this method.
     * PENDING retry/recovery uses the immutable persisted intent instead of
     * re-authorizing against mutable current permissions.
     */
    private suspend fun executeAdmittedVoid(
        command: VoidCommand
    ): CommandResult<VoidExecutionResult> {
        return commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = { operation ->
                resolveVoidReplay(
                    operation = operation,
                    expectedTransactionId = command.subjectId
                )
            }
        ) {
            val precondition = command.financialPrecondition
                ?: throw CommandRequiresReconciliationException(
                    "PENDING Void lacks a financial-state precondition"
                )

            val record =
                transactionDao.getFinancialRecordById(command.subjectId)

            val allocations =
                ledgerDao.getCreditRepaymentAllocationsForTransaction(
                    command.subjectId
                )

            val currentStateFingerprint =
                VoidFinancialIntegrity.fingerprint(
                    transactionId = command.subjectId,
                    record = record,
                    creditAllocations = allocations
                )

            if (currentStateFingerprint != precondition.stateFingerprint) {
                throw CommandRequiresReconciliationException(
                    "Void financial state changed after durable admission"
                )
            }

            val authoritativeRecord =
                record ?: throw IllegalArgumentException(
                    "Transaction '${command.subjectId}' was not found"
                )

            val transaction = authoritativeRecord.transaction

            if (transaction.branchId != command.branchId) {
                throw IllegalStateException(
                    "Transaction belongs to another branch"
                )
            }

            /*
             * Require every persisted payment-method change to have its
             * operation-linked reclassification pair before reversal amount
             * or destination resolution can proceed.
             */
            if (authoritativeRecord.paymentMethodChanges.isNotEmpty()) {
                try {
                    PaymentMethodChangeFinancialIntegrity.validate(
                        record = authoritativeRecord,
                        creditAllocations = allocations
                    )
                } catch (_: PaymentMethodChangeAmbiguousSettlementException) {
                    throw VoidAmbiguousSettlementException(transaction.id)
                }
            }

            val validated =
                VoidFinancialIntegrity.validate(
                    record = authoritativeRecord,
                    creditAllocations = allocations
                )

            val expectedAmount = precondition.expectedReversalAmountMinor
            val expectedCategory = precondition.fundingCategory
            val expectedChannel = precondition.fundingChannel

            if (
                expectedAmount == null ||
                expectedCategory == null ||
                expectedChannel == null
            ) {
                throw CommandRequiresReconciliationException(
                    "Void durable intent lacks an executable financial expectation"
                )
            }

            if (
                validated.reversibleAmountMinor != expectedAmount ||
                validated.fundingCategory != expectedCategory ||
                validated.fundingChannel != expectedChannel
            ) {
                throw CommandRequiresReconciliationException(
                    "Void financial expectation no longer matches durable intent"
                )
            }

            val voidEvent = VoidEventEntity(
                id = IdGenerator.newId(),
                transactionId = transaction.id,
                operationId = command.operationId.value,
                reason = command.reason,
                actorUserId = command.actorUserId,
                occurredAt = command.occurredAt
            )

            val reversal = SettlementEntryEntity(
                id = IdGenerator.newId(),
                operationId = command.operationId.value,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transaction.id,
                transactionId = transaction.id,
                entryPurpose = EntryPurpose.VOID_REVERSAL,
                category = validated.fundingCategory,
                channel = validated.fundingChannel,
                amountSignedMinor = Math.negateExact(
                    validated.reversibleAmountMinor
                ),
                currencyCode = transaction.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = command.occurredAt,
                actorUserId = command.actorUserId
            )

            /*
             * CommandExecutor executes this block inside one Room transaction.
             * If either insert fails, neither financial record nor SUCCEEDED
             * operation state may commit.
             */
            transactionDao.insertVoid(voidEvent)
            transactionDao.insertSettlement(reversal)

            Pair(
                VoidExecutionResult(
                    transactionId = transaction.id,
                    voidEventId = voidEvent.id,
                    reversalAmountMinor = validated.reversibleAmountMinor
                ),
                Pair("VOID_EVENT", voidEvent.id)
            )
        }
    }

    /**
     * A successful replay is accepted only when every durable piece agrees on
     * operation identity, transaction identity and the admitted v2 expectation.
     */
    private suspend fun resolveVoidReplay(
        operation: OperationEntity,
        expectedTransactionId: String
    ): VoidExecutionResult {
        check(operation.operationType == OperationType.RECORD_VOID) {
            "Void replay operation type mismatch"
        }

        check(operation.subjectType == SubjectType.TRANSACTION) {
            "Void replay subject type mismatch"
        }

        check(operation.subjectId == expectedTransactionId) {
            "Void replay subject identity mismatch"
        }

        check(operation.transactionId == expectedTransactionId) {
            "Void replay transaction identity mismatch"
        }

        val payload = VoidPayloadCodec.decode(operation.intentPayload)
        check(payload.schemaVersion == operation.commandSchemaVersion) {
            "Void replay payload version mismatch"
        }

        val event =
            transactionDao.getVoidEventByOperationId(operation.id)
                ?: throw IllegalStateException(
                    "Void replay event is missing"
                )

        check(event.transactionId == expectedTransactionId) {
            "Void replay event transaction mismatch"
        }

        check(event.operationId == operation.id) {
            "Void replay event operation mismatch"
        }

        check(event.actorUserId == operation.actorUserId) {
            "Void replay actor mismatch"
        }

        check(event.occurredAt == operation.occurredAt) {
            "Void replay timestamp mismatch"
        }

        check(event.reason == payload.reason) {
            "Void replay reason mismatch"
        }

        check(operation.resultReferenceType == "VOID_EVENT") {
            "Void replay result type mismatch"
        }

        check(operation.resultReferenceId == event.id) {
            "Void replay result reference mismatch"
        }

        val transaction =
            transactionDao.getTransactionById(expectedTransactionId)
                ?: throw IllegalStateException(
                    "Void replay transaction is missing"
                )

        val operationSettlements =
            transactionDao.getSettlementsForOperation(operation.id)

        check(operationSettlements.size == 1) {
            "Void replay settlement count mismatch"
        }

        val reversal = operationSettlements.single()

        check(reversal.entryPurpose == EntryPurpose.VOID_REVERSAL) {
            "Void replay settlement purpose mismatch"
        }

        check(reversal.subjectType == SubjectType.TRANSACTION) {
            "Void replay settlement subject type mismatch"
        }

        check(reversal.subjectId == expectedTransactionId) {
            "Void replay settlement subject mismatch"
        }

        check(reversal.transactionId == expectedTransactionId) {
            "Void replay settlement transaction mismatch"
        }

        check(reversal.amountSignedMinor < 0L) {
            "Void replay reversal amount is not negative"
        }

        check(reversal.currencyCode == transaction.currencyCode) {
            "Void replay currency mismatch"
        }

        check(reversal.actorUserId == event.actorUserId) {
            "Void replay settlement actor mismatch"
        }

        check(reversal.occurredAt == event.occurredAt) {
            "Void replay settlement timestamp mismatch"
        }

        val reversalAmount =
            Math.negateExact(reversal.amountSignedMinor)

        payload.financialPrecondition?.let { precondition ->
            val expectedAmount = precondition.expectedReversalAmountMinor
                ?: throw IllegalStateException(
                    "Succeeded Void v2 is missing expected reversal amount"
                )
            val expectedCategory = precondition.fundingCategory
                ?: throw IllegalStateException(
                    "Succeeded Void v2 is missing expected funding category"
                )
            val expectedChannel = precondition.fundingChannel
                ?: throw IllegalStateException(
                    "Succeeded Void v2 is missing expected funding channel"
                )

            check(reversalAmount == expectedAmount) {
                "Void replay reversal amount differs from durable intent"
            }
            check(reversal.category == expectedCategory) {
                "Void replay funding category differs from durable intent"
            }
            check(reversal.channel == expectedChannel) {
                "Void replay funding channel differs from durable intent"
            }
        }

        return VoidExecutionResult(
            transactionId = expectedTransactionId,
            voidEventId = event.id,
            reversalAmountMinor = reversalAmount
        )
    }

    suspend fun recoverPendingVoids(): List<VoidExecutionResult> {
        val operationDao = operationDao
        val recovered = mutableListOf<VoidExecutionResult>()

        val pendingOperations =
            commandExecutor.getPendingOperations()
                .filter {
                    it.operationType == OperationType.RECORD_VOID
                }

        for (candidate in pendingOperations) {
            val current =
                operationDao.getOperationById(candidate.id)
                    ?: throw IllegalStateException(
                        "Pending Void operation unavailable during recovery"
                    )

            if (current.status == OperationStatus.SUCCEEDED) {
                recovered += resolveVoidReplay(
                    operation = current,
                    expectedTransactionId = current.subjectId
                )
                continue
            }

            check(current.status == OperationStatus.PENDING) {
                "Void operation is no longer pending"
            }

            val command = try {
                decodePersistedVoidCommand(current)
            } catch (error: RuntimeException) {
                val reconciled = reconcilePendingVoid(
                    current,
                    "Invalid durable Void intent: ${error.message ?: "decode failure"}"
                )
                throw IllegalStateException(
                    (reconciled as? CommandResult.Conflict)?.message
                        ?: "Void requires financial reconciliation",
                    error
                )
            }

            if (command.financialPrecondition == null) {
                val reconciled = reconcilePendingVoid(
                    current,
                    "Legacy PENDING Void lacks a financial-state precondition"
                )
                throw IllegalStateException(
                    (reconciled as? CommandResult.Conflict)?.message
                        ?: "Void requires financial reconciliation"
                )
            }

            /*
             * Do NOT call executeVoid() here.
             * This operation was already admitted. Recovery validates the
             * immutable v2 financial precondition and completes only that same
             * durable intent without consulting today's mutable permission.
             */
            when (
                val result = executeAdmittedVoid(command)
            ) {
                is CommandResult.Success -> {
                    recovered += result.data
                }

                is CommandResult.Conflict -> {
                    throw IllegalStateException(result.message)
                }

                is CommandResult.PermissionDenied -> {
                    throw IllegalStateException(
                        "Admitted Void recovery unexpectedly requested permission"
                    )
                }

                is CommandResult.Error -> {
                    throw result.exception
                }
            }
        }

        return recovered
    }
    /**
     * Completes admitted durable intent without consulting current session permissions.
     * Existing financial evidence is verified, never repaired or overwritten.
     */
    /**
     * Completes already-admitted durable Credit Sale intent.
     *
     * Recovery never asks current UI/session permission again.
     * The persisted operation + durable Credit Sale payload are authoritative.
     * Normal execution and recovery share executeAdmittedCreditSale(), so
     * transaction/settlement/credit-obligation semantics cannot drift.
     */
    suspend fun recoverPendingCreditSales(): List<TransactionEntity> {
        val pending =
            commandExecutor.getPendingOperations()
                .filter {
                    it.operationType == OperationType.RECORD_SALE &&
                        it.intentPayload
                            .lineSequence()
                            .any { line ->
                                line.trim() == "commandType=CREDIT_SALE"
                            }
                }

        val recovered = mutableListOf<TransactionEntity>()

        for (operation in pending) {
            val command =
                try {
                    CreditSalePayloadCodec.decode(operation)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        throw e
                    }

                    throw IllegalStateException(
                        "Invalid durable Credit Sale intent for operation ${operation.id}",
                        e
                    )
                }

            when (
                val result =
                    executeAdmittedCreditSale(command)
            ) {
                is CommandResult.Success -> {
                    recovered += result.data
                }

                is CommandResult.Conflict -> {
                    throw IllegalStateException(
                        "Credit Sale recovery conflict: ${result.message}"
                    )
                }

                is CommandResult.PermissionDenied -> {
                    throw IllegalStateException(
                        "Admitted Credit Sale recovery unexpectedly requested permission"
                    )
                }

                is CommandResult.Error -> {
                    throw result.exception
                }
            }
        }

        return recovered
    }
    suspend fun recoverPendingCashSales(): List<TransactionEntity> {
        val operationDao = operationDao
        suspend fun requireNoReconciliation() {
            check(operationDao.getSaleOperationsRequiringReconciliation().isEmpty()) {
                "Cash Sale requires financial reconciliation"
            }
        }
        requireNoReconciliation()
        val pendingOps = commandExecutor.getPendingOperations()
            .filter {
                it.operationType == OperationType.RECORD_SALE &&
                    it.intentPayload
                        .lineSequence()
                        .none { line ->
                            val value = line.trim()
                            value == "commandType=ONLINE_SALE" ||
                                value == "commandType=CREDIT_SALE"
                        }
            }
        val recovered = mutableListOf<TransactionEntity>()

        for (candidate in pendingOps) {
            // Null means reconciliation or an operation completed by a competing caller.
            val result = try {
                database.withTransaction {
                    requireNoReconciliation()
                    val op = checkNotNull(operationDao.getOperationById(candidate.id)) {
                        "Cash Sale operation unavailable during recovery"
                    }
                    if (op.status == OperationStatus.SUCCEEDED) {
                        // A competing execution already completed; never write financial rows again.
                        return@withTransaction null
                    }
                    check(op.status == OperationStatus.PENDING) {
                        "Cash Sale operation is no longer pending"
                    }

                    suspend fun reconcile(reason: String): TransactionEntity? {
                        check(operationDao.markRequiresReconciliationIfPending(
                            op.id, op.requestFingerprint, reason
                        ) == 1) { "Cash Sale reconciliation transition failed" }
                        return null
                    }

                    val command = try {
                        CashSalePayloadCodec.decode(op)
                    } catch (_: IllegalArgumentException) {
                        return@withTransaction reconcile("Invalid durable Cash Sale intent")
                    } catch (_: ArithmeticException) {
                        return@withTransaction reconcile("Cash Sale amount cannot be represented")
                    }
                    val txId = command.transactionIdVal.value
                    if (op.subjectType != SubjectType.TRANSACTION || op.subjectId != txId ||
                        (op.transactionId != null && op.transactionId != txId) ||
                        (op.resultReferenceType != null && op.resultReferenceType != "TRANSACTION") ||
                        (op.resultReferenceId != null && op.resultReferenceId != txId)
                    ) {
                        return@withTransaction reconcile("Cash Sale identity mismatch")
                    }

                    val existing = transactionDao.getTransactionById(txId)
                    val items = transactionDao.getItemsForTransaction(txId)
                    val settlements = transactionDao.getSettlementsForCashSaleRecovery(op.id, txId)

                    val customerId =
                        if (existing != null || items.isNotEmpty() || settlements.isNotEmpty()) {
                            existingOptionalSaleCustomerId(
                                businessId = command.businessId,
                                customerPhone = command.customerPhone
                            )
                        } else {
                            resolveOptionalSaleCustomerId(
                                businessId = command.businessId,
                                customerPhone = command.customerPhone,
                                customerName = command.customerName,
                                occurredAt = command.occurredAt
                            )
                        }

                    val expected = try {
                        expectedRecoveryResult(command, customerId)
                    } catch (_: ArithmeticException) {
                        return@withTransaction reconcile("Cash Sale amount cannot be represented")
                    }

                    if (expected.items.map { it.id }.distinct().size != expected.items.size) {
                        return@withTransaction reconcile("Ambiguous Cash Sale item identity")
                    }

                    if (existing != null) {
                        val mismatch = recoveryMismatch(expected, existing, items, settlements)
                        if (mismatch != null) return@withTransaction reconcile(mismatch)
                    } else {
                        if (items.isNotEmpty() || settlements.isNotEmpty()) {
                            return@withTransaction reconcile("Partial Cash Sale financial records")
                        }
                        saleFinancialWriter.materialize(
                            SaleFinancialWriter.Intent(
                                operationId = command.operationId.value,
                                transactionId = command.transactionIdVal.value,
                                branchId = command.branchId,
                                actorUserId = command.actorUserId,
                                deviceId = command.deviceId,
                                occurredAt = command.occurredAt,
                                currencyCode = command.currencyCode,
                                currencyDecimalPlaces = command.currencyDecimalPlaces,
                                draft = command.draftState,
                                funding = SaleFinancialWriter.FundingIdentity(
                                    PaymentCategory.CASH,
                                    PaymentChannel.CASH_TENDER,
                                    ConfirmationType.CASHIER_CONFIRMED
                                ),
                                customerId = customerId
                            )
                        )
                        // A failed post-write check throws, rolling back ALL new financial rows.
                        // It must not commit partial writes alongside a reconciliation marker.
                        if (recoveryMismatch(
                            expected,
                            transactionDao.getTransactionById(txId),
                            transactionDao.getItemsForTransaction(txId),
                            transactionDao.getSettlementsForCashSaleRecovery(op.id, txId)
                        ) != null) {
                            throw RecoveryWriteVerificationException(op.id, op.requestFingerprint)
                        }
                    }
                    check(operationDao.markSucceededIfPending(
                        op.id, op.requestFingerprint, "TRANSACTION", txId
                    ) == 1) { "Cash Sale success transition failed" }
                    expected.transaction
                }
            } catch (error: RecoveryWriteVerificationException) {
                // The failed materialization has rolled back. Persist only the hold now.
                database.withTransaction {
                    val latest = checkNotNull(operationDao.getOperationById(error.operationId))
                    check(latest.requestFingerprint == error.fingerprint) {
                        "Cash Sale intent changed during recovery"
                    }
                    if (latest.status == OperationStatus.PENDING) {
                        check(operationDao.markRequiresReconciliationIfPending(
                            latest.id, latest.requestFingerprint,
                            "Cash Sale materialization verification failed"
                        ) == 1)
                    } else {
                        check(latest.status == OperationStatus.SUCCEEDED ||
                            latest.status == OperationStatus.REQUIRES_RECONCILIATION)
                    }
                }
                null
            }
            // This check is deliberately OUTSIDE withTransaction: reconciliation must persist.
            requireNoReconciliation()
            if (result != null) recovered.add(result)
        }
        requireNoReconciliation()
        return recovered
    }

    suspend fun recoverPendingOnlineSales(): List<TransactionEntity> {
        val operationDao = operationDao
        val recovered = mutableListOf<TransactionEntity>()
        val pending = commandExecutor.getPendingOperations()
            .filter {
                it.operationType == OperationType.RECORD_SALE &&
                    it.intentPayload.lineSequence().any { line -> line == "commandType=ONLINE_SALE" }
            }

        for (candidate in pending) {
            try {
                database.withTransaction {
                    val op = checkNotNull(operationDao.getOperationById(candidate.id))
                    if (op.status == OperationStatus.SUCCEEDED) return@withTransaction
                    check(op.status == OperationStatus.PENDING) { "Online Sale operation is no longer pending" }

                    val command = try {
                        OnlineSalePayloadCodec.decode(op)
                    } catch (error: RuntimeException) {
                        operationDao.markRequiresReconciliationIfPending(
                            op.id,
                            op.requestFingerprint,
                            "Invalid durable Online Sale intent: ${error.message ?: "decode failure"}"
                        )
                        return@withTransaction
                    }
                    val validation = validateOnlineSale(command)
                    if (validation != null) {
                        operationDao.markRequiresReconciliationIfPending(
                            op.id,
                            op.requestFingerprint,
                            "Invalid durable Online Sale financial intent"
                        )
                        return@withTransaction
                    }
                    val txId = command.transactionIdVal.value
                    val existing = transactionDao.getTransactionById(txId)
                    val items = transactionDao.getItemsForTransaction(txId)
                    val settlements = transactionDao.getSettlementsForCashSaleRecovery(op.id, txId)

                    val customerId =
                        if (existing != null || items.isNotEmpty() || settlements.isNotEmpty()) {
                            existingOptionalSaleCustomerId(
                                businessId = command.businessId,
                                customerPhone = command.customerPhone
                            )
                        } else {
                            resolveOptionalSaleCustomerId(
                                businessId = command.businessId,
                                customerPhone = command.customerPhone,
                                customerName = command.customerName,
                                occurredAt = command.occurredAt
                            )
                        }

                    val intent = SaleFinancialWriter.Intent(
                        operationId = command.operationId.value,
                        transactionId = command.transactionIdVal.value,
                        branchId = command.branchId,
                        actorUserId = command.actorUserId,
                        deviceId = command.deviceId,
                        occurredAt = command.occurredAt,
                        currencyCode = command.currencyCode,
                        currencyDecimalPlaces = command.currencyDecimalPlaces,
                        draft = command.draftState,
                        funding = onlineFunding(),
                        customerId = customerId
                    )

                    val expected = saleFinancialWriter.expected(intent)

                    if (existing != null) {
                        val mismatch = saleFinancialWriter.mismatch(expected, existing, items, settlements)
                        if (mismatch != null) {
                            operationDao.markRequiresReconciliationIfPending(op.id, op.requestFingerprint, mismatch)
                            return@withTransaction
                        }
                    } else {
                        if (items.isNotEmpty() || settlements.isNotEmpty()) {
                            operationDao.markRequiresReconciliationIfPending(op.id, op.requestFingerprint, "Partial Online Sale financial records")
                            return@withTransaction
                        }
                        saleFinancialWriter.materialize(intent)
                        check(
                            saleFinancialWriter.mismatch(
                                expected,
                                transactionDao.getTransactionById(txId),
                                transactionDao.getItemsForTransaction(txId),
                                transactionDao.getSettlementsForCashSaleRecovery(op.id, txId)
                            ) == null
                        ) { "Online Sale materialization verification failed" }
                    }
                    check(operationDao.markSucceededIfPending(op.id, op.requestFingerprint, "TRANSACTION", txId) == 1)
                    recovered += expected.transaction
                }
            } catch (error: RuntimeException) {
                database.withTransaction {
                    val latest = operationDao.getOperationById(candidate.id)
                    if (latest != null && latest.status == OperationStatus.PENDING) {
                        operationDao.markRequiresReconciliationIfPending(
                            latest.id,
                            latest.requestFingerprint,
                            "Online Sale recovery failed: ${error.message ?: "financial evidence mismatch"}"
                        )
                    }
                }
            }
        }
        return recovered
    }

    private data class RecoveryResult(
        val transaction: TransactionEntity,
        val items: List<TransactionItemEntity>,
        val settlement: SettlementEntryEntity
    )

    private class RecoveryWriteVerificationException(
        val operationId: String,
        val fingerprint: String
    ) : IllegalStateException("Cash Sale materialization verification failed")

    private fun expectedRecoveryResult(
        command: CashSaleCommand,
        customerId: String?
    ): RecoveryResult {
        val expected = saleFinancialWriter.expected(
            SaleFinancialWriter.Intent(
                operationId = command.operationId.value,
                transactionId = command.transactionIdVal.value,
                branchId = command.branchId,
                actorUserId = command.actorUserId,
                deviceId = command.deviceId,
                occurredAt = command.occurredAt,
                currencyCode = command.currencyCode,
                currencyDecimalPlaces = command.currencyDecimalPlaces,
                draft = command.draftState,
                funding = SaleFinancialWriter.FundingIdentity(
                    PaymentCategory.CASH,
                    PaymentChannel.CASH_TENDER,
                    ConfirmationType.CASHIER_CONFIRMED
                ),
                customerId = customerId
            )
        )
        return RecoveryResult(
            transaction = expected.transaction,
            items = expected.items,
            settlement = expected.settlement
        )
    }

    private fun recoveryMismatch(
        expected: RecoveryResult,
        transaction: TransactionEntity?,
        items: List<TransactionItemEntity>,
        settlements: List<SettlementEntryEntity>
    ): String? = when {
        transaction != expected.transaction -> "Cash Sale transaction mismatch"
        items.sortedBy { it.id } != expected.items.sortedBy { it.id } -> "Cash Sale items mismatch"
        settlements.size != 1 -> "Cash Sale settlement count mismatch"
        settlements.single().id.isBlank() ||
            settlements.single().copy(id = "") != expected.settlement -> "Cash Sale settlement mismatch"
        else -> null
    }

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






