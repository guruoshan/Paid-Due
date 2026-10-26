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
import com.dailymoneyregister.core.database.entity.RefundEventEntity
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

data class RefundExecutionResult(
    val transactionId: String,
    val refundEventId: String,
    val refundAmountMinor: Long,
    val couponWalletId: String? = null,
    val couponBalanceAfterMinor: Long? = null
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
        val existing = database.operationDao().getOperationById(c.operationId.value)
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
            val op = database.operationDao().getOperationById(command.operationId.value)
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
        val existing = database.operationDao().getOperationById(command.operationId.value)
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
    private val registerShiftReportReadRepository = RegisterShiftReportReadRepository(database, ::readRegisterShift)
    private val recentFinancialActivityReadRepository =
        RecentFinancialActivityReadRepository(
            database = database,
            localDeviceId = deviceIdentityProvider?.deviceId
        )

    suspend fun getCouponWalletDetails(customerId: String, currencyCode: String): CouponWalletDetails? = couponWalletReadRepository.getCouponWalletDetails(customerId, currencyCode)

    suspend fun executeCouponAuthorizedUser(command: CouponAuthorizedUserCommand): CommandResult<CouponAuthorizedUserDetails> {
        return try {
            command.validate()
            val existing = database.operationDao().getOperationById(command.operationId.value)
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
        val ops = database.operationDao()
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
    ): CommandResult<RegisterShiftResult> {
        val branch = businessBranchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(
                IllegalArgumentException("Register Shift branch does not exist")
            )
        if (branch.businessId != command.businessId) {
            return CommandResult.PermissionDenied(
                "Register Shift branch does not belong to the requested business"
            )
        }
        if (userDao.getUserByIdInBusiness(command.actorUserId, command.businessId) == null) {
            return CommandResult.PermissionDenied(
                "Register Shift actor does not belong to the requested business"
            )
        }

        return commandExecutor.execute(
            command = command,
            replayResolver = { operation ->
                readRegisterShift(
                    shiftId = operation.subjectId,
                    expectedBranchId = command.branchId,
                    expectedBusinessId = command.businessId
                )
            }
        ) {
            val result = materializeRegisterShift(command)
            Pair(result, "CASH_SHIFT" to command.shiftId.value)
        }
    }

    /**
     * Appends immutable cash-count evidence to the cashier's OPEN shift.
     *
     * The counted value is reconciliation evidence. It is not sales revenue
     * and does not mutate authoritative settlements.
     */
    /**
     * Read-only Day Closing projection for the authenticated cashier.
     *
     * Shift membership comes only from OperationEntity.cashShiftId.
     * Historical NULL attribution is never guessed.
     */
    suspend fun getCurrentRegisterShiftReport(): RegisterShiftReport? = registerShiftReportReadRepository.getCurrentRegisterShiftReport()
    suspend fun countRegisterShift(
        command: CountRegisterShiftCommand
    ): CommandResult<RegisterShiftResult> {
        val shift = cashShiftDao.getShiftById(command.shiftId.value)
            ?: return CommandResult.Error(
                IllegalArgumentException("Register Shift does not exist")
            )

        if (shift.branchId != command.branchId) {
            return CommandResult.PermissionDenied(
                "Register Shift does not belong to the requested branch"
            )
        }

        val branch = businessBranchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(
                IllegalArgumentException("Register Shift branch does not exist")
            )

        if (branch.businessId != command.businessId) {
            return CommandResult.PermissionDenied(
                "Register Shift branch does not belong to the requested business"
            )
        }

        authorizeNewRegisterShiftLifecycle(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            shiftOwnerUserId = shift.userId,
            actionLabel = "Register Shift count"
        )?.let { return it }

        if (userDao.getUserByIdInBusiness(
                command.actorUserId,
                command.businessId
            ) == null
        ) {
            return CommandResult.PermissionDenied(
                "Register Shift actor does not belong to the requested business"
            )
        }

        return commandExecutor.execute(
            command = command,
            replayResolver = { operation ->
                readRegisterShift(
                    shiftId = operation.subjectId,
                    expectedBranchId = command.branchId,
                    expectedBusinessId = command.businessId
                )
            }
        ) {
            val result = materializeRegisterShiftCount(command)
            Pair(result, "CASH_SHIFT" to command.shiftId.value)
        }
    }

    /**
     * Appends the immutable CLOSED event.
     *
     * Closing a shift never transfers, deletes, edits, or recreates any
     * transaction belonging to the cashier.
     */
    suspend fun closeRegisterShift(
        command: CloseRegisterShiftCommand
    ): CommandResult<RegisterShiftResult> {
        val shift = cashShiftDao.getShiftById(command.shiftId.value)
            ?: return CommandResult.Error(
                IllegalArgumentException("Register Shift does not exist")
            )

        if (shift.branchId != command.branchId) {
            return CommandResult.PermissionDenied(
                "Register Shift does not belong to the requested branch"
            )
        }

        val branch = businessBranchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(
                IllegalArgumentException("Register Shift branch does not exist")
            )

        if (branch.businessId != command.businessId) {
            return CommandResult.PermissionDenied(
                "Register Shift branch does not belong to the requested business"
            )
        }

        authorizeNewRegisterShiftLifecycle(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            shiftOwnerUserId = shift.userId,
            actionLabel = "Register Shift close"
        )?.let { return it }

        if (userDao.getUserByIdInBusiness(
                command.actorUserId,
                command.businessId
            ) == null
        ) {
            return CommandResult.PermissionDenied(
                "Register Shift actor does not belong to the requested business"
            )
        }

        return commandExecutor.execute(
            command = command,
            replayResolver = { operation ->
                readRegisterShift(
                    shiftId = operation.subjectId,
                    expectedBranchId = command.branchId,
                    expectedBusinessId = command.businessId
                )
            }
        ) {
            val result = materializeRegisterShiftClose(command)
            Pair(result, "CASH_SHIFT" to command.shiftId.value)
        }
    }

    /**
     * BOARDLOCK Register Shift lifecycle authorization.
     *
     * NEW intent:
     * - authenticated actor must match command actor
     * - business + branch must match active session
     * - actor must be active
     * - Cashier may act only on their own shift
     * - Admin may act on another cashier's shift
     *
     * EXISTING durable intent:
     * authorization was already admitted, so retry/recovery must not
     * be redefined by later session or permission changes.
     *
     * CashShiftEntity.userId remains the immutable shift owner.
     * CashShiftEventEntity.actorUserId records the lifecycle actor.
     */
    private suspend fun authorizeNewRegisterShiftLifecycle(
        operationId: OperationId,
        actorUserId: String,
        businessId: String,
        branchId: String,
        shiftOwnerUserId: String,
        actionLabel: String
    ): CommandResult<Nothing>? {

        val existingOperation =
            database.operationDao()
                .getOperationById(operationId.value)

        if (existingOperation != null) {
            return null
        }

        val session =
            SessionManager.currentSession.value
                ?: return CommandResult.PermissionDenied(
                    "$actionLabel requires an authenticated session"
                )

        if (session.user.id != actorUserId) {
            return CommandResult.PermissionDenied(
                "$actionLabel actor does not match the authenticated user"
            )
        }

        if (
            session.business.id != businessId ||
            session.branch.id != branchId
        ) {
            return CommandResult.PermissionDenied(
                "$actionLabel is outside the authenticated business or branch"
            )
        }

        if (session.requiresPinChange) {
            return CommandResult.PermissionDenied(
                "$actionLabel is unavailable until the cashier PIN is changed"
            )
        }

        val actor =
            userDao.getUserByIdInBusiness(
                actorUserId,
                businessId
            )
                ?: return CommandResult.PermissionDenied(
                    "$actionLabel actor does not belong to the requested business"
                )

        if (!actor.isActive) {
            return CommandResult.PermissionDenied(
                "$actionLabel actor is inactive"
            )
        }

        if (actor.role == UserRole.ADMIN) {
            return null
        }

        val membership =
            userDao.getMembership(
                actor.id,
                branchId
            )
                ?: return CommandResult.PermissionDenied(
                    "$actionLabel requires membership in the requested branch"
                )

        if (membership.userId != actor.id) {
            return CommandResult.PermissionDenied(
                "$actionLabel branch membership identity mismatch"
            )
        }

        if (shiftOwnerUserId != actor.id) {
            return CommandResult.PermissionDenied(
                "Cashier can $actionLabel only for their own Register Shift"
            )
        }

        return null
    }
    private suspend fun materializeRegisterShiftCount(
        command: CountRegisterShiftCommand
    ): RegisterShiftResult {
        val shift = cashShiftDao.getShiftById(command.shiftId.value)
            ?: error("Register Shift does not exist")

        /*
         * shift.userId remains the immutable shift owner.
         * command.actorUserId is the authenticated user performing
         * this COUNTED lifecycle action.
         */
        check(shift.branchId == command.branchId) {
            "Register Shift count branch identity mismatch"
        }

        val events = cashShiftDao.getEventsForShift(shift.id)

        check(events.any { it.type == CashShiftEventType.OPENED }) {
            "Register Shift has no OPENED event"
        }

        check(events.none { it.type == CashShiftEventType.CLOSED }) {
            "Closed Register Shift cannot be counted"
        }

        val existing =
            cashShiftDao.getEventByOperationId(command.operationId.value)

        if (existing == null) {
            cashShiftDao.insertShiftEvent(
                CashShiftEventEntity(
                    id = "${command.operationId.value}:COUNTED",
                    shiftId = shift.id,
                    operationId = command.operationId.value,
                    type = CashShiftEventType.COUNTED,
                    amountMinor = command.countedCashMinor,
                    occurredAt = command.occurredAt,
                    actorUserId = command.actorUserId
                )
            )
        } else {
            check(
                existing.shiftId == shift.id &&
                    existing.type == CashShiftEventType.COUNTED &&
                    existing.amountMinor == command.countedCashMinor &&
                    existing.occurredAt == command.occurredAt &&
                    existing.actorUserId == command.actorUserId
            ) {
                "Register Shift COUNTED event conflicts with durable intent"
            }
        }

        return readRegisterShift(
            shiftId = shift.id,
            expectedBranchId = command.branchId,
            expectedBusinessId = command.businessId
        )
    }

    private suspend fun materializeRegisterShiftClose(
        command: CloseRegisterShiftCommand
    ): RegisterShiftResult {
        val shift = cashShiftDao.getShiftById(command.shiftId.value)
            ?: error("Register Shift does not exist")

        /*
         * The CashShiftEntity still identifies the original cashier.
         * CLOSED.actorUserId identifies who actually closed it.
         * This allows an authenticated Admin override without
         * rewriting cashier ownership.
         */
        check(shift.branchId == command.branchId) {
            "Register Shift close branch identity mismatch"
        }

        val events = cashShiftDao.getEventsForShift(shift.id)

        check(events.any { it.type == CashShiftEventType.OPENED }) {
            "Register Shift has no OPENED event"
        }

        val existing =
            cashShiftDao.getEventByOperationId(command.operationId.value)

        if (existing == null) {
            check(events.none { it.type == CashShiftEventType.CLOSED }) {
                "Register Shift is already closed"
            }

            check(events.any { it.type == CashShiftEventType.COUNTED }) {
                "Register Shift must be counted before closing"
            }

            cashShiftDao.insertShiftEvent(
                CashShiftEventEntity(
                    id = "${command.operationId.value}:CLOSED",
                    shiftId = shift.id,
                    operationId = command.operationId.value,
                    type = CashShiftEventType.CLOSED,
                    amountMinor = 0L,
                    occurredAt = command.occurredAt,
                    actorUserId = command.actorUserId
                )
            )
        } else {
            check(
                existing.shiftId == shift.id &&
                    existing.type == CashShiftEventType.CLOSED &&
                    existing.amountMinor == 0L &&
                    existing.occurredAt == command.occurredAt &&
                    existing.actorUserId == command.actorUserId
            ) {
                "Register Shift CLOSED event conflicts with durable intent"
            }
        }

        return readRegisterShift(
            shiftId = shift.id,
            expectedBranchId = command.branchId,
            expectedBusinessId = command.businessId
        )
    }
    /**
     * Replays pending OPEN, COUNT and CLOSE shift lifecycle operations.
     *
     * Recovery replays the already-admitted durable intent. It does not
     * re-authorize the historical action using future mutable permissions.
     */
    suspend fun recoverPendingRegisterShifts(): List<RegisterShiftResult> {
        val operationDao = database.operationDao()
        val recovered = mutableListOf<RegisterShiftResult>()

        val pending = commandExecutor.getPendingOperations()
            .filter {
                it.operationType == OperationType.OPEN_CASH_SHIFT ||
                    it.operationType == OperationType.COUNT_CASH_SHIFT ||
                    it.operationType == OperationType.CLOSE_CASH_SHIFT
            }

        for (candidate in pending) {
            try {
                val result: CommandResult<RegisterShiftResult> =
                    when (candidate.operationType) {
                        OperationType.OPEN_CASH_SHIFT ->
                            openRegisterShift(
                                RegisterShiftPayloadCodec.decode(candidate)
                            )

                        OperationType.COUNT_CASH_SHIFT ->
                            countRegisterShift(
                                RegisterShiftLifecyclePayloadCodec.decodeCount(candidate)
                            )

                        OperationType.CLOSE_CASH_SHIFT ->
                            closeRegisterShift(
                                RegisterShiftLifecyclePayloadCodec.decodeClose(candidate)
                            )

                        else ->
                            error("Unsupported Register Shift recovery operation")
                    }

                when (result) {
                    is CommandResult.Success ->
                        recovered += result.data

                    is CommandResult.Conflict ->
                        error(result.message)

                    is CommandResult.PermissionDenied ->
                        error(result.message)

                    is CommandResult.Error ->
                        throw result.exception
                }
            } catch (error: RuntimeException) {
                operationDao.markRequiresReconciliationIfPending(
                    candidate.id,
                    candidate.requestFingerprint,
                    "Invalid durable Register Shift lifecycle intent: " +
                        (error.message ?: "recovery failure")
                )
            }
        }

        return recovered
    }
    private suspend fun materializeRegisterShift(
        command: OpenRegisterShiftCommand
    ): RegisterShiftResult {
        val existing = cashShiftDao.getShiftById(command.shiftId.value)
        if (existing != null) {
            check(existing.branchId == command.branchId && existing.userId == command.actorUserId) {
                "Register Shift identity conflicts with existing shift"
            }
        } else {
            val latest = cashShiftDao.getLatestShiftForCashier(
            branchId = command.branchId,
            userId = command.actorUserId
        )
            if (latest != null) {
                val latestEvents = cashShiftDao.getEventsForShift(latest.id)
                check(latestEvents.lastOrNull()?.type == CashShiftEventType.CLOSED) {
                    "A Register Shift is already open for this cashier"
                }
            }
            cashShiftDao.insertShift(
                CashShiftEntity(
                    id = command.shiftId.value,
                    branchId = command.branchId,
                    userId = command.actorUserId,
                    createdAt = command.occurredAt
                )
            )
        }

        val existingEvent = cashShiftDao.getEventByOperationId(command.operationId.value)
        if (existingEvent == null) {
            val events = cashShiftDao.getEventsForShift(command.shiftId.value)
            check(events.none { it.type == CashShiftEventType.OPENED }) {
                "Register Shift already has an OPENED event for another operation"
            }
            cashShiftDao.insertShiftEvent(
                CashShiftEventEntity(
                    id = "${command.operationId.value}:OPENED",
                    shiftId = command.shiftId.value,
                    operationId = command.operationId.value,
                    type = CashShiftEventType.OPENED,
                    amountMinor = 0L,
                    occurredAt = command.occurredAt,
                    actorUserId = command.actorUserId
                )
            )
        } else {
            check(
                existingEvent.shiftId == command.shiftId.value &&
                    existingEvent.type == CashShiftEventType.OPENED &&
                    existingEvent.amountMinor == 0L &&
                    existingEvent.occurredAt == command.occurredAt &&
                    existingEvent.actorUserId == command.actorUserId
            ) { "Register Shift OPENED event conflicts with durable intent" }
        }

        return readRegisterShift(
            shiftId = command.shiftId.value,
            expectedBranchId = command.branchId,
            expectedBusinessId = command.businessId
        )
    }

    private suspend fun readRegisterShift(
        shiftId: String,
        expectedBranchId: String,
        expectedBusinessId: String
    ): RegisterShiftResult {
        val shift = cashShiftDao.getShiftById(shiftId)
            ?: error("Register Shift '$shiftId' does not exist")
        check(shift.branchId == expectedBranchId) {
            "Register Shift branch identity mismatch"
        }
        check(businessBranchDao.getBranchById(shift.branchId)?.businessId == expectedBusinessId) {
            "Register Shift business identity mismatch"
        }
        val events = cashShiftDao.getEventsForShift(shift.id)
        val opened = events.firstOrNull { it.type == CashShiftEventType.OPENED }
            ?: error("Register Shift '$shiftId' has no OPENED event")
        val counted = events
            .filter { it.type == CashShiftEventType.COUNTED }
            .maxByOrNull { it.occurredAt }

        val closed = events
            .firstOrNull { it.type == CashShiftEventType.CLOSED }

        return RegisterShiftResult(
            shiftId = CashShiftId(shift.id),
            branchId = shift.branchId,
            actorUserId = shift.userId,
            openedAt = opened.occurredAt,
            isOpen = closed == null,
            countedCashMinor = counted?.amountMinor,
            countedAt = counted?.occurredAt,
            closedAt = closed?.occurredAt
        )
    }

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
        // 1. BOARDLOCK: authoritative identity, branch, permission, currency and money validation.
        // Never trust only the UI/caller for financial truth.

        if (actorUser.id != command.actorUserId) {
            return CommandResult.PermissionDenied(
                "Cash sale actor does not match the authenticated user"
            )
        }

        if (SessionManager.currentSession.value?.user?.id == actorUser.id &&
            SessionManager.currentSession.value?.requiresPinChange == true
        ) {
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
                database.operationDao()
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


    // ============================================================
    // BOARDLOCK CREDIT PHASE 1C
    // AUTHORITATIVE CASH / ONLINE CREDIT REPAYMENT
    // ============================================================

    /**
     * Repayment result is evidence from the authoritative Credit lifecycle.
     *
     * outstandingAfterMinor is derived from the Credit ledger. It is not a
     * separately persisted customer balance.
     */
    /**
     * BOARDLOCK Credit Phase 1C-C.
     *
     * Coupon-funded repayment is one parent financial operation:
     *
     * Coupon REDEMPTION (-)
     * + Credit repayment record
     * + repayment funding evidence
     * + Credit REPAYMENT (-)
     * + FIFO allocations
     *
     * All child writes execute inside the existing CommandExecutor Room
     * transaction. There is no sale and no new sales revenue.
     */
    private fun couponCreditRepaymentEvent(
        command:
            com.dailymoneyregister.feature.customers.data
                .CouponCreditRepaymentCommand
    ): com.dailymoneyregister.core.database.entity
        .CouponLedgerEventEntity =
        com.dailymoneyregister.core.database.entity
            .CouponLedgerEventEntity(
                id =
                    "${command.operationId.value}:coupon-credit-repayment",
                operationId =
                    command.operationId.value,
                walletId =
                    command.walletId,
                type =
                    com.dailymoneyregister.core.model.enums
                        .CouponLedgerType.REDEMPTION,
                amountSignedMinor =
                    Math.negateExact(command.amountMinor),
                currencyCode =
                    command.currencyCode,

                /*
                 * Credit repayment is not a TransactionEntity.
                 * Parent operation + repayment evidence provide the linkage.
                 */
                transactionId = null,

                authorizedUserId =
                    command.authorizedUserId,

                authorizedUserNameSnapshot =
                    command.authorizedUserName,

                occurredAt =
                    command.occurredAt
            )

    private suspend fun loadCouponCreditRepaymentEvidence(
        command:
            com.dailymoneyregister.feature.customers.data
                .CouponCreditRepaymentCommand
    ): CreditRepaymentExecutionResult {

        val base =
            command.repaymentEvidenceCommand()

        /*
         * Reuse the strict 1C-B evidence verifier for:
         * repayment row, funding evidence, Credit ledger and FIFO.
         */
        val creditEvidence =
            loadCreditRepaymentEvidence(base)

        val expectedCouponEvent =
            couponCreditRepaymentEvent(command)

        val couponEvents =
            ledgerDao.getCouponEventsForOperation(
                command.operationId.value
            )

        check(
            couponEvents ==
                listOf(expectedCouponEvent)
        ) {
            "Coupon Credit repayment ledger evidence mismatch."
        }

        val wallet =
            checkNotNull(
                ledgerDao.getCouponWallet(
                    command.customerId,
                    command.currencyCode
                )
            ) {
                "Coupon Credit repayment wallet is missing."
            }

        check(
            wallet.id == command.walletId
        ) {
            "Coupon Credit repayment wallet identity mismatch."
        }

        val balanceAfter =
            ledgerDao.getCouponBalanceAfterEvent(
                command.walletId,
                expectedCouponEvent.id
            )

        check(balanceAfter >= 0L) {
            "Coupon Credit repayment produced negative Coupon balance."
        }

        return creditEvidence
    }

    suspend fun executeCouponCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data
                .CouponCreditRepaymentCommand
    ): CommandResult<CreditRepaymentExecutionResult> {

        return try {

            val existing =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )

            if (existing != null) {

                if (
                    existing.requestFingerprint !=
                        command.requestFingerprint ||
                    existing.intentPayload !=
                        command.intentPayload ||
                    existing.commandSchemaVersion !=
                        command.commandSchemaVersion
                ) {
                    return CommandResult.Conflict(
                        "Coupon Credit repayment operation already belongs to a different request."
                    )
                }

                val durable =
                    com.dailymoneyregister.feature.customers.data
                        .CouponCreditRepaymentPayloadCodec
                        .decode(existing)

                return executeAdmittedCouponCreditRepayment(
                    durable
                )
            }

            /*
             * Coupon-funded repayment spends Coupon value.
             * It does NOT require ACCEPT_CASH or ACCEPT_ONLINE.
             */
            authorizeNewFinancialMutation(
                actorUserId =
                    command.actorUserId,
                businessId =
                    command.businessId,
                branchId =
                    command.branchId,
                action =
                    PermissionAction.USE_COUPON,
                actionLabel =
                    "Coupon Credit repayment"
            )?.let {
                return it
            }

            executeAdmittedCouponCreditRepayment(
                command
            )

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {
            throw e

        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    private suspend fun executeAdmittedCouponCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data
                .CouponCreditRepaymentCommand
    ): CommandResult<CreditRepaymentExecutionResult> =

        commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = {
                loadCouponCreditRepaymentEvidence(
                    command
                )
            }
        ) {

            val base =
                command.repaymentEvidenceCommand()

            val branch =
                checkNotNull(
                    businessBranchDao.getBranchById(
                        command.branchId
                    )
                ) {
                    "Coupon Credit repayment branch does not exist."
                }

            require(
                branch.businessId ==
                    command.businessId
            ) {
                "Coupon Credit repayment branch/business mismatch."
            }

            require(
                branch.currencyCode ==
                    command.currencyCode
            ) {
                "Coupon Credit repayment branch/currency mismatch."
            }

            val customer =
                checkNotNull(
                    database.customerDao()
                        .getCustomerById(
                            command.customerId
                        )
                ) {
                    "Coupon Credit repayment customer does not exist."
                }

            require(
                customer.businessId ==
                    command.businessId
            ) {
                "Coupon Credit repayment customer/business mismatch."
            }

            val wallet =
                checkNotNull(
                    ledgerDao.getCouponWallet(
                        command.customerId,
                        command.currencyCode
                    )
                ) {
                    "Customer does not have a Coupon wallet."
                }

            require(
                wallet.id ==
                    command.walletId
            ) {
                "Coupon wallet does not belong to this customer."
            }

            require(wallet.isActive) {
                "Coupon wallet is inactive."
            }

            /*
             * Same authorized-user validation contract already used by
             * ordinary Coupon redemption.
             */
            val authorizedUser =
                ledgerDao
                    .getActiveAuthorizedUsers(
                        wallet.id
                    )
                    .singleOrNull {
                        it.id ==
                            command.authorizedUserId
                    }

            requireNotNull(
                authorizedUser
            ) {
                "Select an authorized Coupon user."
            }

            val authorizedUserDetails =
                CouponAuthorizedUserDetails(
                    authorizedUser,
                    ledgerDao.getAuthorizedUserEvents(
                        authorizedUser.id
                    )
                )

            require(
                authorizedUserDetails.isActive &&
                    authorizedUserDetails.name ==
                        command.authorizedUserName
            ) {
                "Authorized Coupon user changed; select again."
            }

            val couponBalanceBefore =
                ledgerDao.getCouponBalance(
                    wallet.id
                )

            require(
                couponBalanceBefore >=
                    command.amountMinor
            ) {
                "Insufficient Coupon balance."
            }

            val creditOutstandingBefore =
                ledgerDao.getCreditBalance(
                    command.customerId,
                    command.currencyCode
                )

            require(
                creditOutstandingBefore > 0L
            ) {
                "Customer has no outstanding Credit balance."
            }

            require(
                command.amountMinor <=
                    creditOutstandingBefore
            ) {
                "Credit repayment cannot exceed outstanding balance."
            }

            /*
             * Reject partial evidence before any new child write.
             */
            check(
                ledgerDao
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            ) {
                "Unexpected Coupon Credit repayment evidence exists."
            }

            check(
                transactionDao
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            ) {
                "Unexpected Coupon Credit repayment collection evidence exists."
            }

            check(
                ledgerDao
                    .getCreditLedgerForCustomer(
                        command.customerId
                    )
                    .none {
                        it.operationId ==
                            command.operationId.value
                    }
            ) {
                "Unexpected Coupon Credit repayment Credit ledger evidence exists."
            }

            check(
                ledgerDao
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .isEmpty()
            ) {
                "Unexpected Coupon Credit repayment allocation evidence exists."
            }

            check(
                ledgerDao
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            ) {
                "Unexpected Coupon Credit repayment Coupon ledger evidence exists."
            }

            val obligations =
                creditRepaymentOutstandingObligations(
                    base
                )

            val plan =
                com.dailymoneyregister.feature.customers.data
                    .CreditRepaymentFifoAllocator
                    .allocate(
                        repaymentAmountMinor =
                            command.amountMinor,
                        obligations =
                            obligations
                    )

            check(plan.isNotEmpty()) {
                "Coupon Credit repayment produced no FIFO allocations."
            }

            val repayment =
                creditRepaymentRecord(base)

            val collection =
                creditRepaymentCollection(base)

            val creditLedgerEvent =
                creditRepaymentLedgerEvent(base)

            val couponLedgerEvent =
                couponCreditRepaymentEvent(
                    command
                )

            val allocations =
                plan.map { allocation ->

                    com.dailymoneyregister.core.database.entity
                        .CreditRepaymentAllocationEntity(
                            id =
                                creditRepaymentAllocationId(
                                    command.operationId.value,
                                    allocation.transactionId
                                ),

                            repaymentId =
                                command.repaymentId,

                            originalTransactionId =
                                allocation.transactionId,

                            allocatedAmountMinor =
                                allocation.amountMinor,

                            occurredAt =
                                command.occurredAt
                        )
                }

            /*
             * ATOMIC COMPOSITION.
             *
             * CommandExecutor owns the Room transaction surrounding every
             * write below. Any failure rolls the entire composition back.
             */
            ledgerDao.insertCreditRepayment(
                repayment
            )

            transactionDao.insertSettlement(
                collection
            )

            ledgerDao.insertCreditEvent(
                creditLedgerEvent
            )

            ledgerDao.insertCreditRepaymentAllocations(
                allocations
            )

            ledgerDao.insertCouponEvent(
                couponLedgerEvent
            )

            val evidence =
                loadCouponCreditRepaymentEvidence(
                    command
                )

            val expectedCreditAfter =
                Math.subtractExact(
                    creditOutstandingBefore,
                    command.amountMinor
                )

            check(
                evidence.outstandingAfterMinor ==
                    expectedCreditAfter
            ) {
                "Coupon Credit repayment resulting Credit outstanding mismatch."
            }

            val couponBalanceAfter =
                ledgerDao.getCouponBalance(
                    wallet.id
                )

            val expectedCouponAfter =
                Math.subtractExact(
                    couponBalanceBefore,
                    command.amountMinor
                )

            check(
                couponBalanceAfter ==
                    expectedCouponAfter
            ) {
                "Coupon Credit repayment resulting Coupon balance mismatch."
            }

            evidence to
                (
                    "CREDIT_REPAYMENT" to
                        command.repaymentId
                )
        }

    data class CreditRepaymentExecutionResult(
        val repayment:
            com.dailymoneyregister.core.database.entity.CreditRepaymentEntity,
        val allocations:
            List<com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity>,
        val outstandingAfterMinor: Long
    )

    private fun creditRepaymentCollection(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): com.dailymoneyregister.core.database.entity.SettlementEntryEntity =
        com.dailymoneyregister.core.database.entity.SettlementEntryEntity(
            id = "${command.operationId.value}:collection",
            operationId = command.operationId.value,
            subjectType =
                com.dailymoneyregister.core.model.enums.SubjectType.REPAYMENT,
            subjectId = command.repaymentId,
            transactionId = null,
            entryPurpose =
                com.dailymoneyregister.core.model.enums.EntryPurpose
                    .CREDIT_REPAYMENT_COLLECTION,
            category = command.paymentCategory,
            channel = command.paymentChannel,
            amountSignedMinor = command.amountMinor,
            currencyCode = command.currencyCode,
            confirmationType =
                com.dailymoneyregister.core.model.enums.ConfirmationType
                    .CASHIER_CONFIRMED,
            occurredAt = command.occurredAt,
            actorUserId = command.actorUserId
        )

    private fun creditRepaymentRecord(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): com.dailymoneyregister.core.database.entity.CreditRepaymentEntity =
        com.dailymoneyregister.core.database.entity.CreditRepaymentEntity(
            id = command.repaymentId,
            operationId = command.operationId.value,
            customerId = command.customerId,
            amountMinor = command.amountMinor,
            currencyCode = command.currencyCode,
            paymentCategory = command.paymentCategory,
            occurredAt = command.occurredAt,
            actorUserId = command.actorUserId
        )

    private fun creditRepaymentLedgerEvent(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity =
        com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity(
            id = "${command.operationId.value}:credit-repayment",
            operationId = command.operationId.value,
            customerId = command.customerId,
            type =
                com.dailymoneyregister.core.model.enums.CreditLedgerType
                    .REPAYMENT,
            amountSignedMinor =
                Math.negateExact(command.amountMinor),
            currencyCode = command.currencyCode,
            transactionId = null,
            repaymentId = command.repaymentId,
            occurredAt = command.occurredAt
        )

    private fun creditRepaymentAllocationId(
        operationId: String,
        transactionId: String
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-repayment:$operationId:$transactionId"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    /**
     * Reconstructs the remaining balance of each original Credit obligation.
     *
     * The Credit ledger remains the overall source of truth.
     * Allocation rows only explain which original Credit sales were paid.
     */
    private suspend fun creditRepaymentOutstandingObligations(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): List<
        com.dailymoneyregister.feature.customers.data
            .CreditRepaymentFifoAllocator.OutstandingObligation
        > {

        val ledger =
            ledgerDao.getCreditLedgerForCustomer(command.customerId)

        val relevant =
            ledger.filter {
                it.currencyCode == command.currencyCode
            }

        val obligations =
            relevant.filter {
                it.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType.OBLIGATION
            }

        /*
         * Every FIFO obligation must identify the immutable original sale.
         * Otherwise the debt cannot safely be allocated.
         */
        check(
            obligations.all {
                !it.transactionId.isNullOrBlank() &&
                    it.amountSignedMinor > 0L
            }
        ) {
            "Credit repayment cannot allocate malformed obligation evidence."
        }

        val result =
            mutableListOf<
                com.dailymoneyregister.feature.customers.data
                    .CreditRepaymentFifoAllocator.OutstandingObligation
                >()

        for (obligation in obligations) {

            val transactionId =
                checkNotNull(obligation.transactionId)

            /*
             * Verify that the obligation still points to the correct
             * authoritative customer's original transaction.
             */
            val transaction =
                checkNotNull(
                    transactionDao.getTransactionById(transactionId)
                ) {
                    "Credit obligation transaction evidence is missing."
                }

            check(transaction.customerId == command.customerId) {
                "Credit obligation customer mismatch."
            }

            check(transaction.currencyCode == command.currencyCode) {
                "Credit obligation currency mismatch."
            }

            val previousAllocations =
                ledgerDao.getCreditRepaymentAllocationsForTransaction(
                    transactionId
                )

            var allocated = 0L

            for (allocation in previousAllocations) {
                check(allocation.allocatedAmountMinor > 0L) {
                    "Credit repayment allocation must be positive."
                }

                allocated =
                    Math.addExact(
                        allocated,
                        allocation.allocatedAmountMinor
                    )
            }

            /*
             * BOARDLOCK - authoritative Credit debt reconstruction.
             *
             * Repayment allocations extinguish debt, but they are not the
             * only authoritative evidence that can reduce a Credit sale.
             *
             * Credit Returns append negative ADJUSTMENT ledger events linked
             * to the original transaction. Those events reduce the customer's
             * authoritative Credit balance and must therefore also reduce the
             * remaining FIFO obligation for that transaction.
             *
             * Positive adjustments are deliberately not interpreted as return
             * debt reduction here.
             */
            val transactionAdjustments =
                relevant.filter {
                    it.type == com.dailymoneyregister.core.model.enums.CreditLedgerType.ADJUSTMENT &&
                        it.transactionId == transactionId
                }

            val creditReturnDebtReductionMinor =
                transactionAdjustments
                    .asSequence()
                    .filter { it.amountSignedMinor < 0L }
                    .fold(0L) { total, adjustment ->
                        Math.addExact(
                            total,
                            Math.negateExact(
                                adjustment.amountSignedMinor
                            )
                        )
                    }

            var creditCorrectionIncreaseMinor = 0L
            for (adjustment in transactionAdjustments.filter { it.amountSignedMinor > 0L }) {
                val adjustmentOperation =
                    database.operationDao().getOperationById(
                        adjustment.operationId
                    )
                if (
                    adjustmentOperation?.operationType ==
                    OperationType.RECORD_AMOUNT_CORRECTION
                ) {
                    creditCorrectionIncreaseMinor =
                        Math.addExact(
                            creditCorrectionIncreaseMinor,
                            adjustment.amountSignedMinor
                        )
                }
            }

            val effectiveObligationMinor =
                Math.addExact(
                    obligation.amountSignedMinor,
                    creditCorrectionIncreaseMinor
                )

            val totalDebtExtinguishedMinor =
                Math.addExact(
                    allocated,
                    creditReturnDebtReductionMinor
                )

            check(
                totalDebtExtinguishedMinor <=
                    effectiveObligationMinor
            ) {
                "Credit debt-extinguishment evidence exceeds corrected obligation."
            }

            val remaining =
                Math.subtractExact(
                    effectiveObligationMinor,
                    totalDebtExtinguishedMinor
                )

            check(remaining >= 0L) {
                "Credit repayment allocations exceed original obligation."
            }

            if (remaining > 0L) {
                result +=
                    com.dailymoneyregister.feature.customers.data
                        .CreditRepaymentFifoAllocator.OutstandingObligation(
                            transactionId = transactionId,
                            occurredAt = obligation.occurredAt,
                            remainingMinor = remaining
                        )
            }
        }

        /*
         * BOARDLOCK consistency check:
         *
         * The ledger-derived outstanding and the sum of FIFO remaining
         * obligations must agree before accepting a repayment.
         *
         * This intentionally fails closed if historical ADJUSTMENT evidence
         * has changed the customer balance without a corresponding allocatable
         * sale lifecycle. We do not silently invent an allocation target.
         */
        val ledgerOutstanding =
            ledgerDao.getCreditBalance(
                command.customerId,
                command.currencyCode
            )

        var fifoOutstanding = 0L

        for (item in result) {
            fifoOutstanding =
                Math.addExact(
                    fifoOutstanding,
                    item.remainingMinor
                )
        }

        check(ledgerOutstanding == fifoOutstanding) {
            "Credit outstanding cannot be reconciled to FIFO obligations."
        }

        check(ledgerOutstanding >= 0L) {
            "Credit outstanding cannot be negative."
        }

        return result
    }

    private suspend fun loadCreditRepaymentEvidence(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): CreditRepaymentExecutionResult {

        val expectedRepayment =
            creditRepaymentRecord(command)

        val repayment =
            checkNotNull(
                ledgerDao.getCreditRepaymentById(
                    command.repaymentId
                )
            ) {
                "Credit repayment evidence is missing."
            }

        check(repayment == expectedRepayment) {
            "Credit repayment evidence mismatch."
        }

        val operationRepayments =
            ledgerDao.getCreditRepaymentsForOperation(
                command.operationId.value
            )

        check(operationRepayments == listOf(expectedRepayment)) {
            "Credit repayment operation contains unexpected repayment evidence."
        }

        val expectedCollection =
            creditRepaymentCollection(command)

        val settlements =
            transactionDao.getSettlementsForOperation(
                command.operationId.value
            )

        check(settlements.size == 1) {
            "Credit repayment collection evidence count mismatch."
        }

        check(
            settlements.single().copy(id = "") ==
                expectedCollection.copy(id = "")
        ) {
            "Credit repayment collection evidence mismatch."
        }

        /*
         * The collection ID itself is deterministic too.
         */
        check(settlements.single().id == expectedCollection.id) {
            "Credit repayment collection identity mismatch."
        }

        val expectedLedgerEvent =
            creditRepaymentLedgerEvent(command)

        val operationCreditEvents =
            ledgerDao
                .getCreditLedgerForCustomer(command.customerId)
                .filter {
                    it.operationId == command.operationId.value
                }

        check(operationCreditEvents == listOf(expectedLedgerEvent)) {
            "Credit repayment ledger evidence mismatch."
        }

        val allocations =
            ledgerDao.getCreditRepaymentAllocations(
                command.repaymentId
            )

        check(allocations.isNotEmpty()) {
            "Credit repayment FIFO allocation evidence is missing."
        }

        var allocatedTotal = 0L

        for (allocation in allocations) {
            check(
                allocation.repaymentId ==
                    command.repaymentId
            ) {
                "Credit repayment allocation identity mismatch."
            }

            check(allocation.allocatedAmountMinor > 0L) {
                "Credit repayment allocation amount is invalid."
            }

            check(
                allocation.occurredAt ==
                    command.occurredAt
            ) {
                "Credit repayment allocation timestamp mismatch."
            }

            check(
                allocation.id ==
                    creditRepaymentAllocationId(
                        command.operationId.value,
                        allocation.originalTransactionId
                    )
            ) {
                "Credit repayment allocation deterministic identity mismatch."
            }

            allocatedTotal =
                Math.addExact(
                    allocatedTotal,
                    allocation.allocatedAmountMinor
                )
        }

        check(allocatedTotal == command.amountMinor) {
            "Credit repayment allocation total mismatch."
        }

        val outstandingAfter =
            ledgerDao.getCreditBalance(
                command.customerId,
                command.currencyCode
            )

        check(outstandingAfter >= 0L) {
            "Credit repayment produced negative outstanding."
        }

        return CreditRepaymentExecutionResult(
            repayment = repayment,
            allocations = allocations,
            outstandingAfterMinor = outstandingAfter
        )
    }

    /**
     * Public authoritative Cash/Online repayment command.
     *
     * Coupon repayment is intentionally excluded here. Coupon requires an
     * atomic Coupon-wallet debit and will be composed separately.
     */
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
    ): Long {
        val session =
            checkNotNull(
                SessionManager.currentSession.value
            ) {
                "No active session."
            }

        val customer =
            checkNotNull(
                database.customerDao()
                    .getCustomerById(customerId)
            ) {
                "Customer does not exist."
            }

        require(
            customer.businessId ==
                session.business.id
        ) {
            "Customer does not belong to the active business."
        }

        require(
            currencyCode ==
                session.branch.currencyCode
        ) {
            "Credit currency does not match the active branch."
        }

        return ledgerDao.getCreditBalance(
            customerId,
            currencyCode
        )
    }

    suspend fun executeCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): CommandResult<CreditRepaymentExecutionResult> {

        return try {

            require(command.amountMinor > 0L) {
                "Credit repayment amount must be positive."
            }

            require(
                command.paymentCategory ==
                    com.dailymoneyregister.core.model.enums
                        .PaymentCategory.CASH ||
                    command.paymentCategory ==
                    com.dailymoneyregister.core.model.enums
                        .PaymentCategory.ONLINE
            ) {
                "This Credit repayment command supports Cash or Online only."
            }

            when (command.paymentCategory) {

                com.dailymoneyregister.core.model.enums.PaymentCategory.CASH ->
                    require(
                        command.paymentChannel ==
                            com.dailymoneyregister.core.model.enums
                                .PaymentChannel.CASH_TENDER
                    ) {
                        "Cash Credit repayment requires CASH_TENDER."
                    }

                com.dailymoneyregister.core.model.enums.PaymentCategory.ONLINE ->
                    require(
                        command.paymentChannel ==
                            com.dailymoneyregister.core.model.enums
                                .PaymentChannel.MANUAL_QR ||
                            command.paymentChannel ==
                            com.dailymoneyregister.core.model.enums
                                .PaymentChannel.SOFTPOS
                    ) {
                        "Online Credit repayment requires an Online channel."
                    }

                else ->
                    error("Unsupported Credit repayment category.")
            }

            val existing =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )

            if (existing != null) {

                if (
                    existing.requestFingerprint !=
                        command.requestFingerprint ||
                    existing.intentPayload !=
                        command.intentPayload ||
                    existing.commandSchemaVersion !=
                        command.commandSchemaVersion
                ) {
                    return CommandResult.Conflict(
                        "Credit repayment operation already belongs to a different request."
                    )
                }

                val durable =
                    com.dailymoneyregister.feature.customers.data
                        .CreditRepaymentPayloadCodec.decode(existing)

                return executeAdmittedCreditRepayment(
                    durable
                )
            }

            val permission =
                when (command.paymentCategory) {

                    com.dailymoneyregister.core.model.enums
                        .PaymentCategory.CASH ->
                        PermissionAction.ACCEPT_CASH

                    com.dailymoneyregister.core.model.enums
                        .PaymentCategory.ONLINE ->
                        PermissionAction.ACCEPT_ONLINE

                    else ->
                        error(
                            "Unsupported Credit repayment permission category."
                        )
                }

            authorizeNewFinancialMutation(
                actorUserId = command.actorUserId,
                businessId = command.businessId,
                branchId = command.branchId,
                action = permission,
                actionLabel = "Credit repayment"
            )?.let {
                return it
            }

            executeAdmittedCreditRepayment(command)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    private suspend fun executeAdmittedCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): CommandResult<CreditRepaymentExecutionResult> =
        commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = {
                loadCreditRepaymentEvidence(command)
            }
        ) {

            val branch =
                checkNotNull(
                    businessBranchDao.getBranchById(
                        command.branchId
                    )
                ) {
                    "Credit repayment branch does not exist."
                }

            require(branch.businessId == command.businessId) {
                "Credit repayment branch/business mismatch."
            }

            require(branch.currencyCode == command.currencyCode) {
                "Credit repayment branch/currency mismatch."
            }

            val customer =
                checkNotNull(
                    database.customerDao()
                        .getCustomerById(
                            command.customerId
                        )
                ) {
                    "Credit repayment customer does not exist."
                }

            require(customer.businessId == command.businessId) {
                "Credit repayment customer/business mismatch."
            }

            val currentOutstanding =
                ledgerDao.getCreditBalance(
                    command.customerId,
                    command.currencyCode
                )

            require(currentOutstanding > 0L) {
                "Customer has no outstanding Credit balance."
            }

            require(
                command.amountMinor <= currentOutstanding
            ) {
                "Credit repayment cannot exceed outstanding balance."
            }

            /*
             * Before writing anything, reject partial/unexpected evidence for
             * this operation. CommandExecutor has already persisted only the
             * parent PENDING operation at this point.
             */
            check(
                ledgerDao
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            ) {
                "Unexpected Credit repayment evidence already exists."
            }

            check(
                transactionDao
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            ) {
                "Unexpected Credit repayment collection evidence exists."
            }

            check(
                ledgerDao
                    .getCreditLedgerForCustomer(
                        command.customerId
                    )
                    .none {
                        it.operationId ==
                            command.operationId.value
                    }
            ) {
                "Unexpected Credit repayment ledger evidence exists."
            }

            check(
                ledgerDao
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .isEmpty()
            ) {
                "Unexpected Credit repayment allocation evidence exists."
            }

            val obligations =
                creditRepaymentOutstandingObligations(
                    command
                )

            val plan =
                com.dailymoneyregister.feature.customers.data
                    .CreditRepaymentFifoAllocator.allocate(
                        repaymentAmountMinor =
                            command.amountMinor,
                        obligations = obligations
                    )

            check(plan.isNotEmpty()) {
                "Credit repayment produced no FIFO allocations."
            }

            val repayment =
                creditRepaymentRecord(command)

            val collection =
                creditRepaymentCollection(command)

            val ledgerEvent =
                creditRepaymentLedgerEvent(command)

            val allocations =
                plan.map { allocation ->
                    com.dailymoneyregister.core.database.entity
                        .CreditRepaymentAllocationEntity(
                            id =
                                creditRepaymentAllocationId(
                                    command.operationId.value,
                                    allocation.transactionId
                                ),
                            repaymentId =
                                command.repaymentId,
                            originalTransactionId =
                                allocation.transactionId,
                            allocatedAmountMinor =
                                allocation.amountMinor,
                            occurredAt =
                                command.occurredAt
                        )
                }

            /*
             * These writes execute inside CommandExecutor's Room transaction.
             * Any failure rolls back repayment, collection, ledger event and
             * allocations together.
             */
            ledgerDao.insertCreditRepayment(
                repayment
            )

            transactionDao.insertSettlement(
                collection
            )

            ledgerDao.insertCreditEvent(
                ledgerEvent
            )

            ledgerDao.insertCreditRepaymentAllocations(
                allocations
            )

            val evidence =
                loadCreditRepaymentEvidence(command)

            val expectedAfter =
                Math.subtractExact(
                    currentOutstanding,
                    command.amountMinor
                )

            check(
                evidence.outstandingAfterMinor ==
                    expectedAfter
            ) {
                "Credit repayment resulting outstanding mismatch."
            }

            evidence to
                (
                    "CREDIT_REPAYMENT" to
                        command.repaymentId
                )
        }


    /**
     * BOARDLOCK Credit Phase 1C-B recovery.
     *
     * Recovery replays the already-admitted durable intent.
     * Mutable future permissions must not redefine that intent.
     */
    suspend fun recoverPendingCreditRepayments() {

        val pending =
            commandExecutor.getPendingOperations()
                .filter {
                    it.operationType ==
                        com.dailymoneyregister.core.model.enums
                            .OperationType.RECORD_CREDIT_REPAYMENT
                }

        for (candidate in pending) {

            val operation =
                database.operationDao()
                    .getOperationById(candidate.id)
                    ?: continue

            if (
                operation.status !=
                    com.dailymoneyregister.core.model.enums
                        .OperationStatus.PENDING
            ) {
                continue
            }

            try {

                /*
                 * Recovery dispatch is based on the immutable durable command
                 * type. Do not re-evaluate mutable cashier permissions.
                 */
                if (
                    operation.intentPayload
                        .lineSequence()
                        .any {
                            it ==
                                "commandType=COUPON_CREDIT_REPAYMENT"
                        }
                ) {

                    val command =
                        com.dailymoneyregister.feature.customers.data
                            .CouponCreditRepaymentPayloadCodec
                            .decode(operation)

                    executeAdmittedCouponCreditRepayment(
                        command
                    )

                } else {

                    val command =
                        com.dailymoneyregister.feature.customers.data
                            .CreditRepaymentPayloadCodec
                            .decode(operation)

                    executeAdmittedCreditRepayment(
                        command
                    )
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e

            } catch (e: Exception) {

                /*
                 * Leave durable PENDING intent visible rather than inventing
                 * new financial evidence. Existing reconciliation/recovery
                 * infrastructure can surface unresolved operations.
                 */
                android.util.Log.e(
                    "CreditRepaymentRecovery",
                    "Unable to recover Credit repayment operation ${operation.id}",
                    e
                )
            }
        }
    }
    suspend fun executeOnlineSale(command: OnlineSaleCommand): CommandResult<TransactionEntity> {
        val operationDao = database.operationDao()
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
    ): CommandResult<AmountCorrectionExecutionResult> {

        val operationDao =
            database.operationDao()

        val existingOperation =
            operationDao.getOperationById(
                command.operationId.value
            )

        if (existingOperation != null) {

            if (
                existingOperation.requestFingerprint !=
                    command.requestFingerprint
            ) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '${command.operationId.value}' " +
                        "has already been used with a different request fingerprint."
                )
            }

            val durableCommand =
                try {
                    decodePersistedAmountCorrectionCommand(
                        existingOperation
                    )
                } catch (
                    error: RuntimeException
                ) {

                    if (
                        existingOperation.status ==
                            OperationStatus.PENDING
                    ) {
                        return reconcilePendingAmountCorrection(
                            operation =
                                existingOperation,
                            reason =
                                "Invalid durable Amount Correction intent: " +
                                    (error.message ?: "decode failure")
                        )
                    }

                    return CommandResult.Error(
                        error
                    )
                }

            if (
                existingOperation.status ==
                    OperationStatus.PENDING &&
                durableCommand.financialPrecondition ==
                    null
            ) {
                return reconcilePendingAmountCorrection(
                    operation =
                        existingOperation,
                    reason =
                        "PENDING Amount Correction lacks a financial-state precondition"
                )
            }

            return executeAdmittedAmountCorrection(
                durableCommand
            )
        }

        authorizeNewFinancialMutation(
            actorUserId =
                command.actorUserId,
            businessId =
                command.businessId,
            branchId =
                command.branchId,
            action =
                PermissionAction.CORRECT_AMOUNT,
            actionLabel =
                "Amount Correction"
        )?.let {
            return it
        }

        val admittedCommand =
            try {
                prepareAmountCorrectionAdmission(
                    command
                )
            } catch (
                error: RuntimeException
            ) {
                return CommandResult.Error(
                    error
                )
            }

        return executeAdmittedAmountCorrection(
            admittedCommand
        )
    }

    private suspend fun prepareAmountCorrectionAdmission(
        command: AmountCorrectionCommand
    ): AmountCorrectionCommand {

        val record =
            transactionDao
                .getFinancialRecordById(
                    command.subjectId
                )
                ?: throw IllegalArgumentException(
                    "Transaction '${command.subjectId}' was not found"
                )

        if (
            record.transaction.branchId !=
                command.branchId
        ) {
            throw IllegalStateException(
                "Transaction belongs to another branch"
            )
        }

        AmountCorrectionFinancialIntegrity
            .requireChronologicalAppend(
                record =
                    record,
                occurredAt =
                    command.occurredAt
            )

        val allocations =
            ledgerDao
                .getCreditRepaymentAllocationsForTransaction(
                    command.subjectId
                )

        val validated =
            AmountCorrectionFinancialIntegrity
                .validate(
                    record =
                        record,
                    creditAllocations =
                        allocations,
                    correctedAmountMinor =
                        command.correctedAmountMinor
                )

        val stateFingerprint =
            AmountCorrectionFinancialIntegrity
                .fingerprint(
                    transactionId =
                        command.subjectId,
                    record =
                        record,
                    creditAllocations =
                        allocations
                )

        return command.copy(
            financialPrecondition =
                AmountCorrectionFinancialPrecondition(
                    stateFingerprint =
                        stateFingerprint,
                    expectedPreviousAmountMinor =
                        validated.previousAmountMinor,
                    expectedRefundedAmountMinor =
                        validated.refundedAmountMinor,
                    expectedCurrentCategory =
                        validated.currentCategory,
                    expectedCurrentChannel =
                        validated.currentChannel,
                    expectedDeltaMinor =
                        validated.deltaMinor
                )
        )
    }

    private fun decodePersistedAmountCorrectionCommand(
        operation: OperationEntity
    ): AmountCorrectionCommand {

        check(
            operation.operationType ==
                OperationType.RECORD_AMOUNT_CORRECTION
        ) {
            "Durable Amount Correction operation type mismatch"
        }

        check(
            operation.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Durable Amount Correction subject type mismatch"
        }

        check(
            operation.transactionId ==
                operation.subjectId
        ) {
            "Durable Amount Correction transaction identity mismatch"
        }

        val branchId =
            operation.branchId
                ?: throw IllegalStateException(
                    "Durable Amount Correction has no branch"
                )

        val payload =
            AmountCorrectionPayloadCodec.decode(
                operation.intentPayload
            )

        check(
            operation.commandSchemaVersion ==
                payload.schemaVersion
        ) {
            "Durable Amount Correction schema version mismatch"
        }

        val command =
            AmountCorrectionCommand(
                operationId =
                    OperationId(
                        operation.id
                    ),
                businessId =
                    operation.businessId,
                branchId =
                    branchId,
                subjectId =
                    operation.subjectId,
                actorUserId =
                    operation.actorUserId,
                deviceId =
                    operation.deviceId,
                occurredAt =
                    operation.occurredAt,
                correctedAmountMinor =
                    payload.correctedAmountMinor,
                reason =
                    payload.reason,
                financialPrecondition =
                    payload.financialPrecondition
            )

        check(
            command.requestFingerprint ==
                operation.requestFingerprint
        ) {
            "Durable Amount Correction request fingerprint mismatch"
        }

        if (
            operation.status ==
                OperationStatus.PENDING
        ) {
            check(
                operation.resultReferenceType ==
                    null &&
                    operation.resultReferenceId ==
                    null
            ) {
                "Pending Amount Correction already has a result reference"
            }
        }

        return command
    }

    private suspend fun reconcilePendingAmountCorrection(
        operation: OperationEntity,
        reason: String
    ): CommandResult<AmountCorrectionExecutionResult> {

        return database.withTransaction {

            val latest =
                database
                    .operationDao()
                    .getOperationById(
                        operation.id
                    )

            if (latest == null) {

                CommandResult.Error(
                    IllegalStateException(
                        "Pending Amount Correction operation '${operation.id}' disappeared"
                    )
                )

            } else if (
                latest.requestFingerprint !=
                    operation.requestFingerprint
            ) {

                CommandResult.Conflict(
                    "Conflict: Amount Correction intent changed during reconciliation."
                )

            } else {

                when (latest.status) {

                    OperationStatus.SUCCEEDED -> {
                        CommandResult.Success(
                            data =
                                resolveAmountCorrectionReplay(
                                    operation =
                                        latest,
                                    expectedTransactionId =
                                        latest.subjectId
                                ),
                            isIdempotentReplay =
                                true
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
                            database
                                .operationDao()
                                .markRequiresReconciliationIfPending(
                                    operationId =
                                        latest.id,
                                    requestFingerprint =
                                        latest.requestFingerprint,
                                    reason =
                                        reason
                                )

                        check(changed == 1) {
                            "Amount Correction reconciliation transition failed"
                        }

                        CommandResult.Conflict(
                            "Conflict: Operation ID '${latest.id}' " +
                                "requires reconciliation: $reason"
                        )
                    }
                }
            }
        }
    }

    private suspend fun executeAdmittedAmountCorrection(
        command: AmountCorrectionCommand
    ): CommandResult<AmountCorrectionExecutionResult> {

        return commandExecutor.execute(
            command =
                command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = {
                operation ->

                resolveAmountCorrectionReplay(
                    operation =
                        operation,
                    expectedTransactionId =
                        command.subjectId
                )
            }
        ) {

            val precondition =
                command.financialPrecondition
                    ?: throw CommandRequiresReconciliationException(
                        "PENDING Amount Correction lacks a financial-state precondition"
                    )

            val record =
                transactionDao
                    .getFinancialRecordById(
                        command.subjectId
                    )

            val allocations =
                ledgerDao
                    .getCreditRepaymentAllocationsForTransaction(
                        command.subjectId
                    )

            val currentFingerprint =
                AmountCorrectionFinancialIntegrity
                    .fingerprint(
                        transactionId =
                            command.subjectId,
                        record =
                            record,
                        creditAllocations =
                            allocations
                    )

            if (
                currentFingerprint !=
                    precondition.stateFingerprint
            ) {
                throw CommandRequiresReconciliationException(
                    "Amount Correction financial state changed after durable admission"
                )
            }

            val authoritativeRecord =
                record
                    ?: throw IllegalArgumentException(
                        "Transaction '${command.subjectId}' was not found"
                    )

            val transaction =
                authoritativeRecord.transaction

            if (
                transaction.branchId !=
                    command.branchId
            ) {
                throw IllegalStateException(
                    "Transaction belongs to another branch"
                )
            }

            AmountCorrectionFinancialIntegrity
                .requireChronologicalAppend(
                    record =
                        authoritativeRecord,
                    occurredAt =
                        command.occurredAt
                )

            val validated =
                AmountCorrectionFinancialIntegrity
                    .validate(
                        record =
                            authoritativeRecord,
                        creditAllocations =
                            allocations,
                        correctedAmountMinor =
                            command.correctedAmountMinor
                    )

            if (
                validated.previousAmountMinor !=
                    precondition.expectedPreviousAmountMinor ||
                validated.refundedAmountMinor !=
                    precondition.expectedRefundedAmountMinor ||
                validated.currentCategory !=
                    precondition.expectedCurrentCategory ||
                validated.currentChannel !=
                    precondition.expectedCurrentChannel ||
                validated.deltaMinor !=
                    precondition.expectedDeltaMinor
            ) {
                throw CommandRequiresReconciliationException(
                    "Amount Correction financial expectation no longer matches durable intent"
                )
            }

            val correctionEvent =
                com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity(
                    id =
                        IdGenerator.newId(),
                    transactionId =
                        transaction.id,
                    operationId =
                        command.operationId.value,
                    previousAmountMinor =
                        validated.previousAmountMinor,
                    correctedAmountMinor =
                        command.correctedAmountMinor,
                    reason =
                        command.reason,
                    actorUserId =
                        command.actorUserId,
                    occurredAt =
                        command.occurredAt
                )

            val financialDelta =
                SettlementEntryEntity(
                    id =
                        IdGenerator.newId(),
                    operationId =
                        command.operationId.value,
                    subjectType =
                        SubjectType.TRANSACTION,
                    subjectId =
                        transaction.id,
                    transactionId =
                        transaction.id,
                    entryPurpose =
                        EntryPurpose.FUNDING_CORRECTION,
                    category =
                        validated.currentCategory,
                    channel =
                        validated.currentChannel,
                    amountSignedMinor =
                        validated.deltaMinor,
                    currencyCode =
                        transaction.currencyCode,
                    confirmationType =
                        ConfirmationType.CASHIER_CONFIRMED,
                    occurredAt =
                        command.occurredAt,
                    actorUserId =
                        command.actorUserId
                )

            transactionDao.insertAmountCorrection(
                correctionEvent
            )

            transactionDao.insertSettlement(
                financialDelta
            )

            if (validated.currentCategory == PaymentCategory.CREDIT) {
                val customerId =
                    transaction.customerId
                        ?: throw IllegalStateException(
                            "Credit Amount Correction customer evidence is missing"
                        )

                ledgerDao.insertCreditEvent(
                    com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity(
                        id = "${command.operationId.value}:amount-correction-credit",
                        operationId = command.operationId.value,
                        customerId = customerId,
                        type = com.dailymoneyregister.core.model.enums.CreditLedgerType.ADJUSTMENT,
                        amountSignedMinor = validated.deltaMinor,
                        currencyCode = transaction.currencyCode,
                        transactionId = transaction.id,
                        repaymentId = null,
                        occurredAt = command.occurredAt
                    )
                )
            }

            Pair(
                AmountCorrectionExecutionResult(
                    transactionId =
                        transaction.id,
                    amountCorrectionEventId =
                        correctionEvent.id,
                    previousAmountMinor =
                        validated.previousAmountMinor,
                    correctedAmountMinor =
                        command.correctedAmountMinor,
                    deltaMinor =
                        validated.deltaMinor
                ),
                Pair(
                    "AMOUNT_CORRECTION_EVENT",
                    correctionEvent.id
                )
            )
        }
    }

    private suspend fun resolveAmountCorrectionReplay(
        operation: OperationEntity,
        expectedTransactionId: String
    ): AmountCorrectionExecutionResult {

        check(
            operation.operationType ==
                OperationType.RECORD_AMOUNT_CORRECTION
        ) {
            "Amount Correction replay operation type mismatch"
        }

        check(
            operation.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Amount Correction replay subject type mismatch"
        }

        check(
            operation.subjectId ==
                expectedTransactionId &&
                operation.transactionId ==
                expectedTransactionId
        ) {
            "Amount Correction replay transaction identity mismatch"
        }

        val payload =
            AmountCorrectionPayloadCodec.decode(
                operation.intentPayload
            )

        check(
            payload.schemaVersion ==
                operation.commandSchemaVersion
        ) {
            "Amount Correction replay payload version mismatch"
        }

        val precondition =
            payload.financialPrecondition
                ?: throw IllegalStateException(
                    "Succeeded Amount Correction lacks durable financial expectation"
                )

        val events =
            transactionDao
                .getAmountCorrectionsByOperationId(
                    operation.id
                )

        val event =
            events.singleOrNull()
                ?: throw IllegalStateException(
                    "Amount Correction replay event is missing or ambiguous"
                )

        check(
            event.transactionId ==
                expectedTransactionId &&
                event.operationId ==
                operation.id
        ) {
            "Amount Correction replay event identity mismatch"
        }

        check(
            event.previousAmountMinor ==
                precondition.expectedPreviousAmountMinor &&
                event.correctedAmountMinor ==
                payload.correctedAmountMinor
        ) {
            "Amount Correction replay amount mismatch"
        }

        check(
            event.reason ==
                payload.reason &&
                event.actorUserId ==
                operation.actorUserId &&
                event.occurredAt ==
                operation.occurredAt
        ) {
            "Amount Correction replay audit mismatch"
        }

        check(
            operation.resultReferenceType ==
                "AMOUNT_CORRECTION_EVENT" &&
                operation.resultReferenceId ==
                event.id
        ) {
            "Amount Correction replay result reference mismatch"
        }

        val transaction =
            transactionDao
                .getTransactionById(
                    expectedTransactionId
                )
                ?: throw IllegalStateException(
                    "Amount Correction replay transaction is missing"
                )

        val settlements =
            transactionDao
                .getSettlementsForOperation(
                    operation.id
                )

        check(settlements.size == 1) {
            "Amount Correction replay settlement count mismatch"
        }

        val settlement =
            settlements.single()

        check(
            settlement.subjectType ==
                SubjectType.TRANSACTION &&
                settlement.subjectId ==
                expectedTransactionId &&
                settlement.transactionId ==
                expectedTransactionId
        ) {
            "Amount Correction replay settlement identity mismatch"
        }

        check(
            settlement.entryPurpose ==
                EntryPurpose.FUNDING_CORRECTION
        ) {
            "Amount Correction replay settlement purpose mismatch"
        }

        check(
            settlement.amountSignedMinor ==
                precondition.expectedDeltaMinor
        ) {
            "Amount Correction replay settlement delta mismatch"
        }

        check(
            settlement.category ==
                precondition.expectedCurrentCategory &&
                settlement.channel ==
                precondition.expectedCurrentChannel
        ) {
            "Amount Correction replay funding identity mismatch"
        }

        check(
            settlement.currencyCode ==
                transaction.currencyCode
        ) {
            "Amount Correction replay currency mismatch"
        }

        check(
            settlement.confirmationType ==
                ConfirmationType.CASHIER_CONFIRMED
        ) {
            "Amount Correction replay confirmation mismatch"
        }

        check(
            settlement.actorUserId ==
                event.actorUserId &&
                settlement.occurredAt ==
                event.occurredAt
        ) {
            "Amount Correction replay settlement audit mismatch"
        }

        val creditEvents =
            ledgerDao.getCreditEventsForOperations(
                listOf(operation.id)
            )

        if (settlement.category == PaymentCategory.CREDIT) {
            val customerId =
                transaction.customerId
                    ?: throw IllegalStateException(
                        "Amount Correction replay credit customer is missing"
                    )
            val creditAdjustment =
                creditEvents.singleOrNull()
                    ?: throw IllegalStateException(
                        "Amount Correction replay credit adjustment is missing or ambiguous"
                    )

            check(
                creditAdjustment.id ==
                    "${operation.id}:amount-correction-credit" &&
                    creditAdjustment.operationId == operation.id &&
                    creditAdjustment.customerId == customerId &&
                    creditAdjustment.type ==
                    com.dailymoneyregister.core.model.enums.CreditLedgerType.ADJUSTMENT &&
                    creditAdjustment.amountSignedMinor ==
                    precondition.expectedDeltaMinor &&
                    creditAdjustment.currencyCode == transaction.currencyCode &&
                    creditAdjustment.transactionId == expectedTransactionId &&
                    creditAdjustment.repaymentId == null &&
                    creditAdjustment.occurredAt == operation.occurredAt
            ) {
                "Amount Correction replay credit adjustment mismatch"
            }
        } else {
            check(creditEvents.isEmpty()) {
                "Non-credit Amount Correction unexpectedly has credit-ledger evidence"
            }
        }

        return AmountCorrectionExecutionResult(
            transactionId =
                expectedTransactionId,
            amountCorrectionEventId =
                event.id,
            previousAmountMinor =
                event.previousAmountMinor,
            correctedAmountMinor =
                event.correctedAmountMinor,
            deltaMinor =
                settlement.amountSignedMinor
        )
    }

    suspend fun recoverPendingAmountCorrections():
        List<AmountCorrectionExecutionResult> {

        val operationDao =
            database.operationDao()

        val recovered =
            mutableListOf<AmountCorrectionExecutionResult>()

        val pendingOperations =
            commandExecutor
                .getPendingOperations()
                .filter {
                    it.operationType ==
                        OperationType.RECORD_AMOUNT_CORRECTION
                }

        for (candidate in pendingOperations) {

            val current =
                operationDao
                    .getOperationById(
                        candidate.id
                    )
                    ?: throw IllegalStateException(
                        "Pending Amount Correction operation unavailable during recovery"
                    )

            if (
                current.status ==
                    OperationStatus.SUCCEEDED
            ) {

                recovered +=
                    resolveAmountCorrectionReplay(
                        operation =
                            current,
                        expectedTransactionId =
                            current.subjectId
                    )

                continue
            }

            check(
                current.status ==
                    OperationStatus.PENDING
            ) {
                "Amount Correction operation is no longer pending"
            }

            val command =
                try {

                    decodePersistedAmountCorrectionCommand(
                        current
                    )

                } catch (
                    error: RuntimeException
                ) {

                    val reconciled =
                        reconcilePendingAmountCorrection(
                            operation =
                                current,
                            reason =
                                "Invalid durable Amount Correction intent: " +
                                    (error.message ?: "decode failure")
                        )

                    throw IllegalStateException(
                        (
                            reconciled as?
                                CommandResult.Conflict
                        )?.message
                            ?: "Amount Correction requires financial reconciliation",
                        error
                    )
                }

            if (
                command.financialPrecondition ==
                    null
            ) {

                val reconciled =
                    reconcilePendingAmountCorrection(
                        operation =
                            current,
                        reason =
                            "PENDING Amount Correction lacks a financial-state precondition"
                    )

                throw IllegalStateException(
                    (
                        reconciled as?
                            CommandResult.Conflict
                    )?.message
                        ?: "Amount Correction requires financial reconciliation"
                )
            }

            when (
                val result =
                    executeAdmittedAmountCorrection(
                        command
                    )
            ) {

                is CommandResult.Success -> {
                    recovered +=
                        result.data
                }

                is CommandResult.Conflict -> {
                    throw IllegalStateException(
                        result.message
                    )
                }

                is CommandResult.PermissionDenied -> {
                    throw IllegalStateException(
                        "Admitted Amount Correction recovery unexpectedly requested permission"
                    )
                }

                is CommandResult.Error -> {
                    throw result.exception
                }
            }
        }

        return recovered
    }
    suspend fun executePaymentMethodChange(
        command: PaymentMethodChangeCommand
    ): CommandResult<PaymentMethodChangeExecutionResult> {
        val operationDao = database.operationDao()
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
        val latest = database.operationDao().getOperationById(operation.id)
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
                val changed = database.operationDao().markRequiresReconciliationIfPending(
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
        val operationDao = database.operationDao()
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
    ): CommandResult<RefundExecutionResult> {

        val operationDao =
            database.operationDao()

        val existingOperation =
            operationDao.getOperationById(
                command.operationId.value
            )

        /*
         * Existing operation:
         * use the already-persisted durable intent.
         *
         * Never reconstruct a different financial snapshot from today's
         * mutable state.
         */
        if (existingOperation != null) {

            if (
                existingOperation.requestFingerprint !=
                command.requestFingerprint
            ) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '${command.operationId.value}' " +
                        "has already been used with a different request fingerprint."
                )
            }

            val durableCommand =
                try {
                    decodePersistedRefundCommand(
                        existingOperation
                    )
                } catch (error: RuntimeException) {

                    if (
                        existingOperation.status ==
                        OperationStatus.PENDING
                    ) {
                        return reconcilePendingRefund(
                            existingOperation,
                            "Invalid durable Refund intent: " +
                                (error.message ?: "decode failure")
                        )
                    }

                    return CommandResult.Error(error)
                }

            if (
                existingOperation.status ==
                    OperationStatus.PENDING &&
                durableCommand.financialPrecondition == null
            ) {
                return reconcilePendingRefund(
                    existingOperation,
                    "PENDING Refund lacks a financial-state precondition"
                )
            }

            return executeAdmittedRefund(
                durableCommand
            )
        }

        /*
         * Only a NEW Refund operation consults current authorization.
         */
        authorizeNewFinancialMutation(
            actorUserId = command.actorUserId,
            businessId = command.businessId,
            branchId = command.branchId,
            action = PermissionAction.REFUND,
            actionLabel = "Refund"
        )?.let { return it }

        /*
         * Capture the exact authoritative financial lifetime after
         * authorization and before durable admission.
         */
        val admittedCommand =
            prepareRefundAdmission(command)

        return executeAdmittedRefund(
            admittedCommand
        )
    }

    private suspend fun prepareRefundAdmission(
        command: RefundCommand
    ): RefundCommand {

        val record =
            transactionDao.getFinancialRecordById(
                command.subjectId
            )

        val allocations =
            ledgerDao
                .getCreditRepaymentAllocationsForTransaction(
                    command.subjectId
                )

        val stateFingerprint =
            RefundFinancialIntegrity.fingerprint(
                transactionId = command.subjectId,
                record = record,
                creditAllocations = allocations
            )

        /*
         * Always persist the raw financial-state fingerprint.
         *
         * A currently invalid Refund is allowed to enter the durable
         * command spine, then fail atomically during authoritative
         * execution. We never invent a funding destination.
         */
        val validated =
            if (
                record != null &&
                record.transaction.branchId ==
                    command.branchId
            ) {
                try {
                    RefundFinancialIntegrity.validate(
                        record = record,
                        creditAllocations = allocations,
                        refundDestination = command.destinationCategory
                    )
                } catch (_: RuntimeException) {
                    null
                }
            } else {
                null
            }

        return command.copy(
            financialPrecondition =
                RefundFinancialPrecondition(
                    stateFingerprint =
                        stateFingerprint,
                    expectedRemainingRefundableMinor =
                        validated?.remainingRefundableMinor,
                    fundingCategory =
                        validated?.fundingCategory,
                    fundingChannel =
                        validated?.fundingChannel
                )
        )
    }

    private fun decodePersistedRefundCommand(
        operation: OperationEntity
    ): RefundCommand {

        check(
            operation.operationType ==
                OperationType.RECORD_REFUND
        ) {
            "Durable Refund operation type mismatch"
        }

        check(
            operation.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Durable Refund subject type mismatch"
        }

        check(
            operation.transactionId ==
                operation.subjectId
        ) {
            "Durable Refund transaction identity mismatch"
        }

        val branchId =
            operation.branchId
                ?: throw IllegalStateException(
                    "Durable Refund has no branch"
                )

        val payload =
            RefundPayloadCodec.decode(
                operation.intentPayload
            )

        check(
            operation.commandSchemaVersion ==
                payload.schemaVersion
        ) {
            "Durable Refund schema version mismatch"
        }

        val command =
            RefundCommand(
                operationId =
                    OperationId(operation.id),
                businessId =
                    operation.businessId,
                branchId =
                    branchId,
                subjectId =
                    operation.subjectId,
                actorUserId =
                    operation.actorUserId,
                deviceId =
                    operation.deviceId,
                occurredAt =
                    operation.occurredAt,
                refundAmountMinor =
                    payload.refundAmountMinor,
                destinationCategory =
                    payload.destinationCategory,
                destinationChannel =
                    payload.destinationChannel,
                reason =
                    payload.reason,
                financialPrecondition =
                    payload.financialPrecondition,
                couponDestination =
                    payload.couponDestination
            )

        check(
            command.requestFingerprint ==
                operation.requestFingerprint
        ) {
            "Durable Refund request fingerprint mismatch"
        }

        if (
            operation.status ==
            OperationStatus.PENDING
        ) {
            check(
                operation.resultReferenceType == null &&
                    operation.resultReferenceId == null
            ) {
                "Pending Refund already has a result reference"
            }
        }

        return command
    }

    private suspend fun reconcilePendingRefund(
        operation: OperationEntity,
        reason: String
    ): CommandResult<RefundExecutionResult> {

        return database.withTransaction {

            val latest =
                database.operationDao()
                    .getOperationById(operation.id)
                    ?: return@withTransaction CommandResult.Error(
                        IllegalStateException(
                            "Pending Refund operation '${operation.id}' disappeared"
                        )
                    )

            if (
                latest.requestFingerprint !=
                operation.requestFingerprint
            ) {
                return@withTransaction CommandResult.Conflict(
                    "Conflict: Refund intent changed during reconciliation."
                )
            }

            when (latest.status) {

                OperationStatus.SUCCEEDED -> {
                    CommandResult.Success(
                        data =
                            resolveRefundReplay(
                                operation = latest,
                                expectedTransactionId =
                                    latest.subjectId
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
                        database.operationDao()
                            .markRequiresReconciliationIfPending(
                                operationId =
                                    latest.id,
                                requestFingerprint =
                                    latest.requestFingerprint,
                                reason =
                                    reason
                            )

                    check(changed == 1) {
                        "Refund reconciliation transition failed"
                    }

                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' " +
                            "requires reconciliation: $reason"
                    )
                }
            }
        }
    }

    /**
     * Executes a Refund whose durable financial intent has already
     * been admitted.
     *
     * CommandExecutor provides the transaction boundary. Therefore:
     *
     * RefundEvent + REFUND_OUTFLOW + SUCCEEDED
     *
     * either commit together or none commit.
     */
    private suspend fun executeAdmittedRefund(
        command: RefundCommand
    ): CommandResult<RefundExecutionResult> {

        return commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = { operation ->
                resolveRefundReplay(
                    operation = operation,
                    expectedTransactionId =
                        command.subjectId
                )
            }
        ) {

            val precondition =
                command.financialPrecondition
                    ?: throw CommandRequiresReconciliationException(
                        "PENDING Refund lacks a financial-state precondition"
                    )

            val record =
                transactionDao.getFinancialRecordById(
                    command.subjectId
                )

            val allocations =
                ledgerDao
                    .getCreditRepaymentAllocationsForTransaction(
                        command.subjectId
                    )

            val currentStateFingerprint =
                RefundFinancialIntegrity.fingerprint(
                    transactionId =
                        command.subjectId,
                    record =
                        record,
                    creditAllocations =
                        allocations
                )

            /*
             * This is the concurrency/recovery guard.
             *
             * Any financial event committed after admission changes the
             * lifetime fingerprint, so this Refund cannot silently execute
             * against a different remaining balance.
             */
            if (
                currentStateFingerprint !=
                precondition.stateFingerprint
            ) {
                throw CommandRequiresReconciliationException(
                    "Refund financial state changed after durable admission"
                )
            }

            val authoritativeRecord =
                record ?: throw IllegalArgumentException(
                    "Transaction '${command.subjectId}' was not found"
                )

            val transaction =
                authoritativeRecord.transaction

            if (
                transaction.branchId !=
                command.branchId
            ) {
                throw IllegalStateException(
                    "Transaction belongs to another branch"
                )
            }

            val validated =
                RefundFinancialIntegrity.validate(
                    record =
                        authoritativeRecord,
                    creditAllocations =
                        allocations,
                    refundDestination = command.destinationCategory
                )

            val creditReturnB2Plan =
                if (
                    validated.fundingCategory ==
                        PaymentCategory.CREDIT &&
                    allocations.isNotEmpty()
                ) {
                    buildCreditReturnB2Plan(
                        transaction =
                            transaction,
                        record =
                            authoritativeRecord,
                        allocations =
                            allocations,
                        requestedReturnMinor =
                            command.refundAmountMinor
                    )
                } else {
                    null
                }

            /*
             * Frozen B1 remains byte-for-byte behaviorally isolated for
             * zero-repayment Credit returns.
             */
            val creditReturnCustomerId =
                if (
                    validated.fundingCategory ==
                        PaymentCategory.CREDIT
                ) {
                    creditReturnB2Plan
                        ?.customerId
                        ?: validateUnpaidCreditReturnLedger(
                            transaction =
                                transaction,
                            expectedRemainingMinor =
                                validated
                                    .remainingRefundableMinor
                        )
                } else {
                    null
                }

            val expectedRemaining =
                precondition
                    .expectedRemainingRefundableMinor

            val expectedCategory =
                precondition.fundingCategory

            val expectedChannel =
                precondition.fundingChannel

            if (
                expectedRemaining == null ||
                expectedCategory == null ||
                expectedChannel == null
            ) {
                throw CommandRequiresReconciliationException(
                    "Refund durable intent lacks an executable financial expectation"
                )
            }

            if (
                validated.remainingRefundableMinor !=
                    expectedRemaining ||
                validated.fundingCategory !=
                    expectedCategory ||
                validated.fundingChannel !=
                    expectedChannel
            ) {
                throw CommandRequiresReconciliationException(
                    "Refund financial expectation no longer matches durable intent"
                )
            }

            /*
             * Never allow cumulative Refunds to exceed the authoritative
             * remaining refundable value.
             */
            if (
                command.refundAmountMinor >
                validated.remainingRefundableMinor
            ) {
                throw RefundAmountExceedsRemainingException(
                    transactionId =
                        transaction.id,
                    requestedAmountMinor =
                        command.refundAmountMinor,
                    remainingAmountMinor =
                        validated.remainingRefundableMinor
                )
            }

            /*
             * Initial supported Refund scope is intentionally conservative:
             * refund back through the exact authoritative funding category
             * and channel.
             *
             * CREDIT, COUPON, mixed tender and payment-method-change cases
             * already fail closed in RefundFinancialIntegrity.
             */
            val refundToCoupon = command.destinationCategory == PaymentCategory.COUPON &&
                command.destinationChannel == PaymentChannel.COUPON_WALLET

            val validCreditReturnB2Destination =
                creditReturnB2Plan != null &&
                    (
                        (
                            command.destinationCategory == PaymentCategory.CASH &&
                                command.destinationChannel == PaymentChannel.CASH_TENDER
                        ) ||
                            (
                                command.destinationCategory == PaymentCategory.ONLINE &&
                                    (
                                        command.destinationChannel == PaymentChannel.MANUAL_QR ||
                                            command.destinationChannel == PaymentChannel.SOFTPOS
                                    )
                            ) ||
                            refundToCoupon ||
                            (
                                command.destinationCategory == PaymentCategory.CREDIT &&
                                    command.destinationChannel == PaymentChannel.CREDIT_ACCOUNT
                            )
                    )

            val validUnpaidCreditReturnDestination =
                validated.fundingCategory == PaymentCategory.CREDIT &&
                    creditReturnB2Plan == null &&
                    (
                        (
                            command.destinationCategory == PaymentCategory.CASH &&
                                command.destinationChannel == PaymentChannel.CASH_TENDER
                        ) ||
                            (
                                command.destinationCategory == PaymentCategory.ONLINE &&
                                    (
                                        command.destinationChannel == PaymentChannel.MANUAL_QR ||
                                            command.destinationChannel == PaymentChannel.SOFTPOS
                                    )
                            ) ||
                            refundToCoupon ||
                            (
                                command.destinationCategory == PaymentCategory.CREDIT &&
                                    command.destinationChannel == PaymentChannel.CREDIT_ACCOUNT
                            )
                    )
            if (
                !validCreditReturnB2Destination &&
                !validUnpaidCreditReturnDestination &&
                !refundToCoupon &&
                (
                    command.destinationCategory != validated.fundingCategory ||
                        command.destinationChannel != validated.fundingChannel
                )
            ) {
                throw RefundDestinationMismatchException(
                    transaction.id
                )
            }

            /*
             * BOARDLOCK CREDIT REFUND -> COUPON IDENTITY.
             *
             * A Credit return belongs to the customer who owns the
             * authoritative Credit transaction.
             *
             * When the cashier chooses Refund to Coupon, value may only
             * enter that same customer's Coupon wallet.
             *
             * Never allow a different customer identity to receive the
             * Credit customer's refund.
             */
            if (
                refundToCoupon &&
                creditReturnB2Plan != null
            ) {
                val creditCustomerId =
                    requireNotNull(transaction.customerId) {
                        "Credit transaction has no authoritative customer."
                    }

                val destination =
                    requireNotNull(command.couponDestination) {
                        "Enter the phone number of the Credit customer."
                    }

                require(
                    destination.customerId == creditCustomerId
                ) {
                    "Wrong customer number. Enter the phone number of the Credit customer."
                }

                val creditCustomer =
                    requireNotNull(
                        database.customerDao()
                            .getCustomerById(creditCustomerId)
                    ) {
                        "Credit customer does not exist."
                    }

                require(
                    creditCustomer.businessId ==
                        command.businessId
                ) {
                    "Credit customer belongs to another business."
                }

                require(
                    destination.canonicalPhone ==
                        creditCustomer.canonicalPhone
                ) {
                    "Wrong customer number. Enter the phone number of the Credit customer."
                }

                require(
                    !destination.createCustomer
                ) {
                    "Wrong customer number. Enter the phone number of the Credit customer."
                }
            }

            /*
             * Unpaid Credit is a debt reversal, not stored value.
             * Do not even create a Coupon wallet unless this Credit return
             * has an authoritative paid-value portion to credit.
             */
            val couponWallet =
                if (
                    refundToCoupon &&
                    !(
                        validated.fundingCategory == PaymentCategory.CREDIT &&
                            (creditReturnB2Plan?.paidValueRefundMinor ?: 0L) <= 0L
                    )
                ) {
                    val destination =
                        command.couponDestination

                    val customerId =
                        if (destination == null) {
                            requireNotNull(
                                transaction.customerId
                            ) {
                                "Legacy Refund to Coupon requires the transaction's customer"
                            }
                        } else {
                            val customerDao =
                                database.customerDao()

                            val byId =
                                customerDao.getCustomerById(
                                    destination.customerId
                                )

                            val byPhone =
                                customerDao.getCustomerByPhone(
                                    command.businessId,
                                    destination.canonicalPhone
                                )

                            val customer =
                                when {
                                    byId != null -> {
                                        require(
                                            byId.businessId ==
                                                command.businessId
                                        ) {
                                            "Refund Coupon customer belongs to another business"
                                        }

                                        require(
                                            byId.canonicalPhone ==
                                                destination.canonicalPhone
                                        ) {
                                            "Refund Coupon customer identity changed; search again"
                                        }

                                        require(
                                            byPhone == null ||
                                                byPhone.id ==
                                                byId.id
                                        ) {
                                            "This phone belongs to another customer"
                                        }

                                        byId
                                    }

                                    byPhone != null -> {
                                        require(
                                            byPhone.id ==
                                                destination.customerId
                                        ) {
                                            "This phone already belongs to a customer. Search and select that customer."
                                        }

                                        byPhone
                                    }

                                    else -> {
                                        require(
                                            destination.createCustomer
                                        ) {
                                            "Select an existing customer"
                                        }

                                        val phoneUtil =
                                            com.google.i18n.phonenumbers
                                                .PhoneNumberUtil
                                                .getInstance()

                                        val parsedPhone =
                                            phoneUtil.parse(
                                                destination.canonicalPhone,
                                                "ZZ"
                                            )

                                        require(
                                            phoneUtil.isValidNumber(
                                                parsedPhone
                                            ) &&
                                                phoneUtil.format(
                                                    parsedPhone,
                                                    com.google.i18n.phonenumbers
                                                        .PhoneNumberUtil
                                                        .PhoneNumberFormat
                                                        .E164
                                                ) ==
                                                destination.canonicalPhone
                                        ) {
                                            "Enter a valid international phone number"
                                        }

                                        com.dailymoneyregister.core.database.entity.CustomerEntity(
                                            destination.customerId,
                                            command.businessId,
                                            destination.canonicalPhone,
                                            destination.customerName,
                                            command.occurredAt,
                                            command.occurredAt
                                        ).also {
                                            customerDao.insertCustomer(
                                                it
                                            )
                                        }
                                    }
                                }

                            val originallyCouponFunded =
                                authoritativeRecord
                                    .settlements
                                    .any {
                                        it.entryPurpose ==
                                            EntryPurpose.SALE_FUNDING &&
                                            it.category ==
                                            PaymentCategory.COUPON &&
                                            it.amountSignedMinor > 0L
                                    }

                            if (
                                originallyCouponFunded
                            ) {
                                require(
                                    transaction.customerId ==
                                        customer.id
                                ) {
                                    "Coupon-funded sale must be refunded to its original customer"
                                }
                            }

                            customer.id
                        }

                    val existingWallet =
                        ledgerDao.getCouponWallet(
                            customerId,
                            transaction.currencyCode
                        )

                    val wallet =
                        existingWallet
                            ?.also {
                                require(it.isActive) {
                                    "Coupon wallet is inactive"
                                }
                            }
                            ?: com.dailymoneyregister.core.database.entity.CouponWalletEntity(
                                id =
                                    couponRefundWalletId(
                                        command.operationId.value
                                    ),
                                customerId =
                                    customerId,
                                currencyCode =
                                    transaction.currencyCode,
                                createdAt =
                                    command.occurredAt
                            ).also {
                                ledgerDao.insertCouponWallet(
                                    it
                                )
                            }

                    if (
                        existingWallet == null &&
                        destination != null
                    ) {
                        require(
                            ledgerDao
                                .getActiveAuthorizedUsers(
                                    wallet.id
                                )
                                .isEmpty()
                        ) {
                            "New Refund Coupon wallet already has authorized users"
                        }

                        (
                            listOf(
                                destination.customerName
                            ) +
                                destination
                                    .additionalUserNames
                        )
                            .distinct()
                            .forEachIndexed {
                                    index,
                                    name ->

                                val userId =
                                    couponRefundAuthorizedUserId(
                                        command.operationId.value,
                                        index
                                    )

                                ledgerDao
                                    .insertAuthorizedUser(
                                        com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity(
                                            userId,
                                            wallet.id,
                                            index == 0,
                                            true,
                                            command.occurredAt
                                        )
                                    )

                                ledgerDao
                                    .insertAuthorizedUserEvent(
                                        com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity(
                                            couponRefundAuthorizedUserEventId(
                                                command.operationId.value,
                                                index
                                            ),
                                            userId,
                                            command.operationId.value,
                                            com.dailymoneyregister.core.model.enums.AuthorizedUserEventType.ADDED,
                                            name,
                                            command.occurredAt,
                                            command.actorUserId
                                        )
                                    )
                            }
                    }

                    wallet
                } else {
                    null
                }

            val refundEvent =
                RefundEventEntity(
                    id =
                        IdGenerator.newId(),
                    transactionId =
                        transaction.id,
                    operationId =
                        command.operationId.value,
                    refundAmountMinor =
                        command.refundAmountMinor,
                    reason =
                        command.reason,
                    actorUserId =
                        command.actorUserId,
                    occurredAt =
                        command.occurredAt
                )

            val outflows =
                /*
                 * UNPAID CREDIT RETURN: no external payout.
                 *
                 * A Credit sale with no repayment has no paid value to
                 * return through Cash, Online or Coupon.
                 *
                 * The RefundEvent still records the return and the existing
                 * CreditLedger ADJUSTMENT below reduces the authoritative
                 * Credit obligation by the returned amount.
                 *
                 * B2 remains unchanged: when repayment evidence exists,
                 * only paidValueRefundMinor may leave through the selected
                 * destination.
                 */
                if (
                    validated.fundingCategory == PaymentCategory.CREDIT &&
                    creditReturnB2Plan == null
                ) {
                    emptyList<SettlementEntryEntity>()
                } else {

                    if (creditReturnB2Plan == null) {

                        /*
                         * Frozen B1 and every existing non-B2 Refund retain
                         * the original single-settlement behavior.
                         */
                        listOf(
                            SettlementEntryEntity(
                                id =
                                    IdGenerator.newId(),
                                operationId =
                                    command.operationId.value,
                                subjectType =
                                    SubjectType.TRANSACTION,
                                subjectId =
                                    transaction.id,
                                transactionId =
                                    transaction.id,
                                entryPurpose =
                                    EntryPurpose.REFUND_OUTFLOW,
                                category =
                                    command.destinationCategory,
                                channel =
                                    command.destinationChannel,
                                amountSignedMinor =
                                    Math.negateExact(
                                        command.refundAmountMinor
                                    ),
                                currencyCode =
                                    transaction.currencyCode,
                                confirmationType =
                                    ConfirmationType.CASHIER_CONFIRMED,
                                occurredAt =
                                    command.occurredAt,
                                actorUserId =
                                    command.actorUserId
                            )
                        )

                    } else {

                        val result =
                            mutableListOf<
                                SettlementEntryEntity
                                >()

                        if (
                            creditReturnB2Plan
                                .creditReductionMinor > 0L
                        ) {
                            result +=
                                SettlementEntryEntity(
                                    id =
                                        creditReturnB2SettlementId(
                                            command.operationId.value,
                                            "credit",
                                            0
                                        ),
                                    operationId =
                                        command.operationId.value,
                                    subjectType =
                                        SubjectType.TRANSACTION,
                                    subjectId =
                                        transaction.id,
                                    transactionId =
                                        transaction.id,
                                    entryPurpose =
                                        EntryPurpose.REFUND_OUTFLOW,
                                    category =
                                        PaymentCategory.CREDIT,
                                    channel =
                                        PaymentChannel.CREDIT_ACCOUNT,
                                    amountSignedMinor =
                                        Math.negateExact(
                                            creditReturnB2Plan
                                                .creditReductionMinor
                                        ),
                                    currencyCode =
                                        transaction.currencyCode,
                                    confirmationType =
                                        ConfirmationType.CASHIER_CONFIRMED,
                                    occurredAt =
                                        command.occurredAt,
                                    actorUserId =
                                        command.actorUserId
                                )
                        }

                        if (
                            command.destinationCategory ==
                                PaymentCategory.CREDIT
                        ) {
                            /*
                             * Preserve the existing legacy CREDIT destination
                             * evidence shape.
                             */
                            creditReturnB2Plan
                                .paidValuePortions
                                .forEachIndexed {
                                        index,
                                        portion ->

                                    result +=
                                        SettlementEntryEntity(
                                            id =
                                                creditReturnB2SettlementId(
                                                    command.operationId.value,
                                                    "paid",
                                                    index
                                                ),
                                            operationId =
                                                command.operationId.value,
                                            subjectType =
                                                SubjectType.TRANSACTION,
                                            subjectId =
                                                transaction.id,
                                            transactionId =
                                                transaction.id,
                                            entryPurpose =
                                                EntryPurpose.REFUND_OUTFLOW,
                                            category =
                                                portion.paymentCategory,
                                            channel =
                                                portion.paymentChannel,
                                            amountSignedMinor =
                                                Math.negateExact(
                                                    portion.amountMinor
                                                ),
                                            currencyCode =
                                                transaction.currencyCode,
                                            confirmationType =
                                                ConfirmationType.CASHIER_CONFIRMED,
                                            occurredAt =
                                                command.occurredAt,
                                            actorUserId =
                                                command.actorUserId
                                        )
                                }
                        } else if (
                            creditReturnB2Plan
                                .paidValueRefundMinor > 0L
                        ) {
                            /*
                             * For Cash, Online or Coupon, the settlement records
                             * the cashier-selected actual refund destination.
                             *
                             * Historical repayments determine refundable value,
                             * not the destination of the new payout.
                             */
                            result +=
                                SettlementEntryEntity(
                                    id =
                                        creditReturnB2SettlementId(
                                            command.operationId.value,
                                            "paid",
                                            0
                                        ),
                                    operationId =
                                        command.operationId.value,
                                    subjectType =
                                        SubjectType.TRANSACTION,
                                    subjectId =
                                        transaction.id,
                                    transactionId =
                                        transaction.id,
                                    entryPurpose =
                                        EntryPurpose.REFUND_OUTFLOW,
                                    category =
                                        command.destinationCategory,
                                    channel =
                                        command.destinationChannel,
                                    amountSignedMinor =
                                        Math.negateExact(
                                            creditReturnB2Plan
                                                .paidValueRefundMinor
                                        ),
                                    currencyCode =
                                        transaction.currencyCode,
                                    confirmationType =
                                        ConfirmationType.CASHIER_CONFIRMED,
                                    occurredAt =
                                        command.occurredAt,
                                    actorUserId =
                                        command.actorUserId
                                )
                        }
                        val plannedOutflow =
                            result.fold(0L) {
                                    total,
                                    entry ->

                                Math.addExact(
                                    total,
                                    Math.negateExact(
                                        entry.amountSignedMinor
                                    )
                                )
                            }

                        check(
                            plannedOutflow ==
                                command.refundAmountMinor
                        ) {
                            "Credit Return B2 outflow does not reconcile with RefundEvent"
                        }

                        result
                    }
                }

            transactionDao.insertRefund(
                refundEvent
            )

            outflows.forEach {
                transactionDao.insertSettlement(it)
            }

            val creditAdjustment =
                creditReturnCustomerId
                    ?.takeIf {
                        creditReturnB2Plan == null ||
                            creditReturnB2Plan
                                .creditReductionMinor > 0L
                    }
                    ?.let {
                        customerId ->

                    val adjustment =
                        com.dailymoneyregister.core.database.entity
                            .CreditLedgerEventEntity(
                                id =
                                    creditRefundAdjustmentId(
                                        command
                                            .operationId
                                            .value
                                    ),
                                operationId =
                                    command
                                        .operationId
                                        .value,
                                customerId =
                                    customerId,
                                type =
                                    com.dailymoneyregister.core.model.enums
                                        .CreditLedgerType
                                        .ADJUSTMENT,
                                amountSignedMinor =
                                    Math.negateExact(
                                        creditReturnB2Plan
                                            ?.creditReductionMinor
                                            ?: command
                                                .refundAmountMinor
                                    ),
                                currencyCode =
                                    transaction.currencyCode,
                                transactionId =
                                    transaction.id,
                                repaymentId =
                                    null,
                                occurredAt =
                                    command.occurredAt
                            )

                    ledgerDao.insertCreditEvent(
                        adjustment
                    )

                    adjustment
                }

            if (
                validated.fundingCategory ==
                    PaymentCategory.CREDIT &&
                (
                    creditReturnB2Plan == null ||
                        creditReturnB2Plan
                            .creditReductionMinor > 0L
                )
            ) {
                check(creditAdjustment != null) {
                    "Credit Return adjustment evidence is missing"
                }
            }

            /*
             * B2-B: if Credit was repaid using Coupon value, only that
             * paid-value portion is restored to the original customer's
             * existing Coupon wallet.
             *
             * This is compensation linked to the original Credit sale,
             * never a Coupon purchase/top-up.
             */
            /*
             * Legacy CREDIT-destination compatibility only.
             *
             * Cash, Online and Coupon selected destinations must not
             * additionally restore the historical Coupon repayment.
             */
            if (
                command.destinationCategory ==
                    PaymentCategory.CREDIT
            ) {
                creditReturnB2Plan
                    ?.paidValuePortions
                    ?.forEachIndexed {
                            index,
                            portion ->

                        if (
                            portion.paymentCategory ==
                                PaymentCategory.COUPON
                        ) {
                            val customerId =
                                requireNotNull(
                                    transaction.customerId
                                )

                            val wallet =
                                checkNotNull(
                                    ledgerDao.getCouponWallet(
                                        customerId,
                                        transaction.currencyCode
                                    )
                                ) {
                                    "Credit Return Coupon repayment wallet is missing"
                                }

                            require(wallet.isActive) {
                                "Credit Return Coupon repayment wallet is inactive"
                            }

                            ledgerDao.insertCouponEvent(
                                com.dailymoneyregister.core.database.entity
                                    .CouponLedgerEventEntity(
                                        id =
                                            creditReturnB2CouponEventId(
                                                command.operationId.value,
                                                index
                                            ),
                                        operationId =
                                            command.operationId.value,
                                        walletId =
                                            wallet.id,
                                        type =
                                            com.dailymoneyregister.core.model.enums
                                                .CouponLedgerType
                                                .REFUND_CREDIT,
                                        amountSignedMinor =
                                            portion.amountMinor,
                                        currencyCode =
                                            transaction.currencyCode,
                                        transactionId =
                                            transaction.id,
                                        occurredAt =
                                            command.occurredAt
                                    )
                            )
                        }
                    }
            }

            val couponLedgerEvent =
                couponWallet
                    ?.takeIf {
                        if (validated.fundingCategory == PaymentCategory.CREDIT) {
                            (creditReturnB2Plan?.paidValueRefundMinor ?: 0L) > 0L
                        } else {
                            creditReturnB2Plan == null ||
                                creditReturnB2Plan.paidValueRefundMinor > 0L
                        }
                    }
                    ?.let { wallet ->
                val event = com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity(
                    id = couponRefundEvidenceId(command.operationId.value),
                    operationId = command.operationId.value,
                    walletId = wallet.id,
                    type = com.dailymoneyregister.core.model.enums.CouponLedgerType.REFUND_CREDIT,
                    amountSignedMinor =
                        creditReturnB2Plan
                            ?.paidValueRefundMinor
                            ?: command.refundAmountMinor,
                    currencyCode = transaction.currencyCode,
                    transactionId = transaction.id,
                    occurredAt = command.occurredAt
                )
                ledgerDao.insertCouponEvent(event)
                event
            }

            Pair(
                RefundExecutionResult(
                    transactionId =
                        transaction.id,
                    refundEventId =
                        refundEvent.id,
                    refundAmountMinor = command.refundAmountMinor,
                    couponWalletId = couponWallet?.id,
                    couponBalanceAfterMinor = couponLedgerEvent?.let {
                        ledgerDao.getCouponBalanceAfterEvent(it.walletId, it.id)
                    }
                ),
                Pair(
                    "REFUND_EVENT",
                    refundEvent.id
                )
            )
        }
    }

    /**
     * Successful replay never creates another Refund.
     *
     * It proves that the existing RefundEvent, settlement and durable
     * Operation all describe the same immutable business action.
     */
    private suspend fun resolveRefundReplay(
        operation: OperationEntity,
        expectedTransactionId: String
    ): RefundExecutionResult {

        check(
            operation.operationType ==
                OperationType.RECORD_REFUND
        ) {
            "Refund replay operation type mismatch"
        }

        check(
            operation.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Refund replay subject type mismatch"
        }

        check(
            operation.subjectId ==
                expectedTransactionId
        ) {
            "Refund replay subject identity mismatch"
        }

        check(
            operation.transactionId ==
                expectedTransactionId
        ) {
            "Refund replay transaction identity mismatch"
        }

        val payload =
            RefundPayloadCodec.decode(
                operation.intentPayload
            )

        check(
            payload.schemaVersion ==
                operation.commandSchemaVersion
        ) {
            "Refund replay payload version mismatch"
        }

        val event =
            transactionDao
                .getRefundEventByOperationId(
                    operation.id
                )
                ?: throw IllegalStateException(
                    "Refund replay event is missing"
                )

        check(
            event.transactionId ==
                expectedTransactionId
        ) {
            "Refund replay event transaction mismatch"
        }

        check(
            event.operationId ==
                operation.id
        ) {
            "Refund replay event operation mismatch"
        }

        check(
            event.refundAmountMinor ==
                payload.refundAmountMinor
        ) {
            "Refund replay event amount mismatch"
        }

        check(
            event.actorUserId ==
                operation.actorUserId
        ) {
            "Refund replay actor mismatch"
        }

        check(
            event.occurredAt ==
                operation.occurredAt
        ) {
            "Refund replay timestamp mismatch"
        }

        check(
            event.reason ==
                payload.reason
        ) {
            "Refund replay reason mismatch"
        }

        check(
            operation.resultReferenceType ==
                "REFUND_EVENT"
        ) {
            "Refund replay result type mismatch"
        }

        check(
            operation.resultReferenceId ==
                event.id
        ) {
            "Refund replay result reference mismatch"
        }

        val transaction =
            transactionDao.getTransactionById(
                expectedTransactionId
            ) ?: throw IllegalStateException(
                "Refund replay transaction is missing"
            )


        /*
         * BOARDLOCK Credit Return B2-B replay.
         *
         * Legacy Refund replay below intentionally remains unchanged for
         * B1 zero-repayment Credit returns and every non-B2 Refund.
         *
         * B2 is different because one RefundEvent can legitimately
         * materialize multiple REFUND_OUTFLOW settlements:
         *
         *   unpaid Credit reduction
         *   + zero or more customer-paid funding portions.
         *
         * Replay must reconstruct the same authoritative B2-A plan and
         * verify persisted evidence. It must never create new evidence.
         */
        val replayIsCreditDestination =
            payload.destinationCategory ==
                PaymentCategory.CREDIT &&
                payload.destinationChannel ==
                    PaymentChannel.CREDIT_ACCOUNT

        val replayIsCouponDestination =
            payload.destinationCategory ==
                PaymentCategory.COUPON &&
                payload.destinationChannel ==
                    PaymentChannel.COUPON_WALLET

        val replayIsCashDestination =
            payload.destinationCategory ==
                PaymentCategory.CASH &&
                payload.destinationChannel ==
                    PaymentChannel.CASH_TENDER

        val replayIsOnlineDestination =
            payload.destinationCategory ==
                PaymentCategory.ONLINE &&
                (
                    payload.destinationChannel ==
                        PaymentChannel.MANUAL_QR ||
                        payload.destinationChannel ==
                            PaymentChannel.SOFTPOS
                )

        val replayIsCreditReturnB2Destination =
            replayIsCreditDestination ||
                replayIsCashDestination ||
                replayIsOnlineDestination ||
                replayIsCouponDestination

        val replayAllocations =
            if (
                replayIsCreditReturnB2Destination
            ) {
                ledgerDao
                    .getCreditRepaymentAllocationsForTransaction(
                        expectedTransactionId
                    )
            } else {
                emptyList()
            }

        if (
            replayAllocations.isNotEmpty() &&
            replayIsCreditReturnB2Destination
        ) {

            val replayRecord =
                transactionDao
                    .getFinancialRecordById(
                        expectedTransactionId
                    )
                    ?: throw IllegalStateException(
                        "Credit Return B2 replay financial record is missing"
                    )

            val replayPlan =
                buildCreditReturnB2Plan(
                    transaction =
                        transaction,
                    record =
                        replayRecord,
                    allocations =
                        replayAllocations,
                    requestedReturnMinor =
                        payload.refundAmountMinor,
                    excludeReturnOperationId =
                        operation.id
                )

            check(
                replayPlan.originalTransactionId ==
                    expectedTransactionId
            ) {
                "Credit Return B2 replay plan transaction mismatch"
            }

            check(
                replayPlan.requestedReturnAmountMinor ==
                    payload.refundAmountMinor
            ) {
                "Credit Return B2 replay plan amount mismatch"
            }

            val settlements =
                transactionDao
                    .getSettlementsForOperation(
                        operation.id
                    )

            val expectedPaidSettlementCount =
                if (replayIsCreditDestination) {
                    replayPlan.paidValuePortions.size
                } else if (
                    replayPlan.paidValueRefundMinor > 0L
                ) {
                    1
                } else {
                    0
                }

            val expectedSettlementCount =
                (
                    if (
                        replayPlan.creditReductionMinor >
                            0L
                    ) {
                        1
                    } else {
                        0
                    }
                ) +
                    expectedPaidSettlementCount

            check(
                settlements.size ==
                    expectedSettlementCount
            ) {
                "Credit Return B2 replay settlement count mismatch"
            }

            check(
                settlements.all {
                    it.entryPurpose ==
                        EntryPurpose.REFUND_OUTFLOW &&
                    it.subjectType ==
                        SubjectType.TRANSACTION &&
                    it.subjectId ==
                        expectedTransactionId &&
                    it.transactionId ==
                        expectedTransactionId &&
                    it.amountSignedMinor <
                        0L &&
                    it.currencyCode ==
                        transaction.currencyCode &&
                    it.actorUserId ==
                        event.actorUserId &&
                    it.occurredAt ==
                        event.occurredAt
                }
            ) {
                "Credit Return B2 replay settlement identity mismatch"
            }

            if (
                replayPlan.creditReductionMinor >
                    0L
            ) {
                val creditSettlementId =
                    creditReturnB2SettlementId(
                        operation.id,
                        "credit",
                        0
                    )

                val creditSettlement =
                    settlements.singleOrNull {
                        it.id ==
                            creditSettlementId
                    } ?: throw IllegalStateException(
                        "Credit Return B2 replay Credit settlement is missing"
                    )

                check(
                    creditSettlement.category ==
                        PaymentCategory.CREDIT &&
                    creditSettlement.channel ==
                        PaymentChannel.CREDIT_ACCOUNT &&
                    creditSettlement.amountSignedMinor ==
                        Math.negateExact(
                            replayPlan
                                .creditReductionMinor
                        )
                ) {
                    "Credit Return B2 replay Credit settlement mismatch"
                }
            }

            if (replayIsCreditDestination) {
                replayPlan
                    .paidValuePortions
                    .forEachIndexed {
                            index,
                            portion ->

                        val settlementId =
                            creditReturnB2SettlementId(
                                operation.id,
                                "paid",
                                index
                            )

                        val paidSettlement =
                            settlements.singleOrNull {
                                it.id ==
                                    settlementId
                            } ?: throw IllegalStateException(
                                "Credit Return B2 replay paid-value settlement is missing"
                            )

                        check(
                            paidSettlement.category ==
                                portion.paymentCategory &&
                            paidSettlement.channel ==
                                portion.paymentChannel &&
                            paidSettlement.amountSignedMinor ==
                                Math.negateExact(
                                    portion.amountMinor
                                )
                        ) {
                            "Credit Return B2 replay paid-value settlement mismatch"
                        }
                    }
            } else if (
                replayPlan.paidValueRefundMinor > 0L
            ) {
                val paidSettlementId =
                    creditReturnB2SettlementId(
                        operation.id,
                        "paid",
                        0
                    )

                val paidSettlement =
                    settlements.singleOrNull {
                        it.id ==
                            paidSettlementId
                    } ?: throw IllegalStateException(
                        "Credit Return B2 replay selected-destination paid-value settlement is missing"
                    )

                check(
                    paidSettlement.category ==
                        payload.destinationCategory &&
                    paidSettlement.channel ==
                        payload.destinationChannel &&
                    paidSettlement.amountSignedMinor ==
                        Math.negateExact(
                            replayPlan
                                .paidValueRefundMinor
                        )
                ) {
                    "Credit Return B2 replay selected-destination paid-value settlement mismatch"
                }
            }

            val replayOutflowAmount =
                settlements.fold(0L) {
                        total,
                        settlement ->

                    Math.addExact(
                        total,
                        Math.negateExact(
                            settlement
                                .amountSignedMinor
                        )
                    )
                }

            check(
                replayOutflowAmount ==
                    payload.refundAmountMinor
            ) {
                "Credit Return B2 replay outflow does not reconcile with RefundEvent"
            }

            val customerId =
                requireNotNull(
                    transaction.customerId
                ) {
                    "Credit Return B2 replay customer is missing"
                }

            val operationCreditEvents =
                ledgerDao
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            operation.id
                    }

            if (
                replayPlan.creditReductionMinor >
                    0L
            ) {
                check(
                    operationCreditEvents.size ==
                        1
                ) {
                    "Credit Return B2 replay Credit evidence count mismatch"
                }

                val creditEvent =
                    operationCreditEvents.single()

                check(
                    creditEvent.id ==
                        creditRefundAdjustmentId(
                            operation.id
                        ) &&
                    creditEvent.customerId ==
                        customerId &&
                    creditEvent.type ==
                        com.dailymoneyregister.core.model.enums
                            .CreditLedgerType
                            .ADJUSTMENT &&
                    creditEvent.transactionId ==
                        expectedTransactionId &&
                    creditEvent.repaymentId ==
                        null &&
                    creditEvent.currencyCode ==
                        transaction.currencyCode &&
                    creditEvent.amountSignedMinor ==
                        Math.negateExact(
                            replayPlan
                                .creditReductionMinor
                        ) &&
                    creditEvent.occurredAt ==
                        operation.occurredAt
                ) {
                    "Credit Return B2 replay Credit adjustment mismatch"
                }
            } else {
                check(
                    operationCreditEvents.isEmpty()
                ) {
                    "Credit Return B2 replay unexpectedly contains Credit adjustment evidence"
                }
            }

            val couponLedger =
                ledgerDao
                    .getCouponEventsForOperation(
                        operation.id
                    )

            if (replayIsCouponDestination) {
                check(
                    replayPlan.paidValueRefundMinor > 0L
                ) {
                    "Credit Return B2 Coupon destination has no paid value to refund"
                }

                check(couponLedger.size == 1) {
                    "Credit Return B2 replay Coupon destination evidence count mismatch"
                }

                val couponEvent =
                    couponLedger.single()

                check(
                    couponEvent.id ==
                        couponRefundEvidenceId(
                            operation.id
                        ) &&
                    couponEvent.type ==
                        com.dailymoneyregister.core.model.enums
                            .CouponLedgerType
                            .REFUND_CREDIT &&
                    couponEvent.transactionId ==
                        expectedTransactionId &&
                    couponEvent.currencyCode ==
                        transaction.currencyCode &&
                    couponEvent.amountSignedMinor ==
                        replayPlan.paidValueRefundMinor &&
                    couponEvent.occurredAt ==
                        operation.occurredAt
                ) {
                    "Credit Return B2 replay Coupon destination evidence mismatch"
                }
            } else if (replayIsCreditDestination) {
                val expectedCouponPortions =
                    replayPlan
                        .paidValuePortions
                        .mapIndexedNotNull {
                                index,
                                portion ->

                            if (
                                portion.paymentCategory ==
                                    PaymentCategory.COUPON
                            ) {
                                Pair(
                                    index,
                                    portion
                                )
                            } else {
                                null
                            }
                        }

                check(
                    couponLedger.size ==
                        expectedCouponPortions.size
                ) {
                    "Credit Return B2 replay Coupon evidence count mismatch"
                }

                if (
                    expectedCouponPortions.isNotEmpty()
                ) {
                    val wallet =
                        checkNotNull(
                            ledgerDao.getCouponWallet(
                                customerId,
                                transaction.currencyCode
                            )
                        ) {
                            "Credit Return B2 replay Coupon wallet is missing"
                        }

                    check(wallet.isActive) {
                        "Credit Return B2 replay Coupon wallet is inactive"
                    }

                    expectedCouponPortions
                        .forEach {
                                indexedPortion ->

                            val index =
                                indexedPortion.first

                            val portion =
                                indexedPortion.second

                            val couponEventId =
                                creditReturnB2CouponEventId(
                                    operation.id,
                                    index
                                )

                            val couponEvent =
                                couponLedger
                                    .singleOrNull {
                                        it.id ==
                                            couponEventId
                                    }
                                    ?: throw IllegalStateException(
                                        "Credit Return B2 replay Coupon compensation is missing"
                                    )

                            check(
                                couponEvent.walletId ==
                                    wallet.id &&
                                couponEvent.type ==
                                    com.dailymoneyregister.core.model.enums
                                        .CouponLedgerType
                                        .REFUND_CREDIT &&
                                couponEvent.transactionId ==
                                    expectedTransactionId &&
                                couponEvent.currencyCode ==
                                    transaction.currencyCode &&
                                couponEvent.amountSignedMinor ==
                                    portion.amountMinor &&
                                couponEvent.occurredAt ==
                                    operation.occurredAt
                            ) {
                                "Credit Return B2 replay Coupon compensation mismatch"
                            }
                        }
                }
            } else {
                check(couponLedger.isEmpty()) {
                    "Credit Return B2 selected Cash/Online destination unexpectedly created Coupon evidence"
                }
            }

            val replayDestinationCouponEvent =
                if (replayIsCouponDestination) {
                    couponLedger.single()
                } else {
                    null
                }

            return RefundExecutionResult(
                transactionId =
                    expectedTransactionId,
                refundEventId =
                    event.id,
                refundAmountMinor =
                    payload.refundAmountMinor,
                couponWalletId =
                    replayDestinationCouponEvent?.walletId,
                couponBalanceAfterMinor =
                    replayDestinationCouponEvent?.let {
                        ledgerDao.getCouponBalanceAfterEvent(
                            it.walletId,
                            it.id
                        )
                    }
            )
        }

        val originalSaleSettlements =
            transactionDao
                .getSettlementsForTransaction(
                    expectedTransactionId
                )

        val originalSaleFunding =
            originalSaleSettlements.filter {
                it.entryPurpose == EntryPurpose.SALE_FUNDING &&
                    it.amountSignedMinor > 0L
            }

        val replayIsUnpaidCreditReturn =
            transaction.customerId != null &&
                originalSaleFunding.size == 1 &&
                originalSaleFunding.single().category == PaymentCategory.CREDIT &&
                originalSaleFunding.single().channel == PaymentChannel.CREDIT_ACCOUNT &&
                ledgerDao
                    .getCreditRepaymentAllocationsForTransaction(
                        expectedTransactionId
                    )
                    .isEmpty()

        if (replayIsUnpaidCreditReturn) {
            val settlements =
                transactionDao
                    .getSettlementsForOperation(operation.id)

            check(settlements.isEmpty()) {
                "Unpaid Credit replay must not contain settlement evidence"
            }

            check(
                ledgerDao
                    .getCouponEventsForOperation(operation.id)
                    .isEmpty()
            ) {
                "Unpaid Credit replay must not contain Coupon evidence"
            }

            val customerId = requireNotNull(transaction.customerId)
            val creditAdjustments =
                ledgerDao
                    .getCreditLedgerForCustomer(customerId)
                    .filter { it.operationId == operation.id }

            check(creditAdjustments.size == 1) {
                "Unpaid Credit replay adjustment count mismatch"
            }

            val adjustment = creditAdjustments.single()
            check(
                adjustment.type ==
                    com.dailymoneyregister.core.model.enums.CreditLedgerType.ADJUSTMENT &&
                    adjustment.amountSignedMinor ==
                        Math.negateExact(payload.refundAmountMinor) &&
                    adjustment.currencyCode == transaction.currencyCode &&
                    adjustment.transactionId == expectedTransactionId &&
                    adjustment.repaymentId == null &&
                    adjustment.occurredAt == operation.occurredAt
            ) {
                "Unpaid Credit replay adjustment mismatch"
            }

            return RefundExecutionResult(
                transactionId = expectedTransactionId,
                refundEventId = event.id,
                refundAmountMinor = payload.refundAmountMinor,
                couponWalletId = null,
                couponBalanceAfterMinor = null
            )
        }

        val settlements =
            transactionDao
                .getSettlementsForOperation(
                    operation.id
                )

        check(settlements.size == 1) {
            "Refund replay settlement count mismatch"
        }

        val outflow =
            settlements.single()

        check(
            outflow.entryPurpose ==
                EntryPurpose.REFUND_OUTFLOW
        ) {
            "Refund replay settlement purpose mismatch"
        }

        check(
            outflow.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Refund replay settlement subject type mismatch"
        }

        check(
            outflow.subjectId ==
                expectedTransactionId
        ) {
            "Refund replay settlement subject mismatch"
        }

        check(
            outflow.transactionId ==
                expectedTransactionId
        ) {
            "Refund replay settlement transaction mismatch"
        }

        check(
            outflow.amountSignedMinor < 0L
        ) {
            "Refund replay settlement amount is not negative"
        }

        val replayAmount =
            Math.negateExact(
                outflow.amountSignedMinor
            )

        check(
            replayAmount ==
                payload.refundAmountMinor
        ) {
            "Refund replay settlement amount mismatch"
        }

        check(
            outflow.category ==
                payload.destinationCategory
        ) {
            "Refund replay destination category mismatch"
        }

        check(
            outflow.channel ==
                payload.destinationChannel
        ) {
            "Refund replay destination channel mismatch"
        }

        check(
            outflow.currencyCode ==
                transaction.currencyCode
        ) {
            "Refund replay currency mismatch"
        }

        check(
            outflow.actorUserId ==
                event.actorUserId
        ) {
            "Refund replay settlement actor mismatch"
        }

        check(
            outflow.occurredAt ==
                event.occurredAt
        ) {
            "Refund replay settlement timestamp mismatch"
        }

        val couponLedger = ledgerDao.getCouponEventsForOperation(operation.id)
        if (payload.destinationCategory == PaymentCategory.COUPON) {
            check(couponLedger.size == 1)
            val event = couponLedger.single()
            check(event.id == couponRefundEvidenceId(operation.id) &&
                event.type == com.dailymoneyregister.core.model.enums.CouponLedgerType.REFUND_CREDIT &&
                event.transactionId == expectedTransactionId && event.currencyCode == transaction.currencyCode &&
                event.amountSignedMinor == replayAmount && event.occurredAt == operation.occurredAt)
            val replayCustomerId =
                payload.couponDestination
                    ?.customerId
                    ?: requireNotNull(
                        transaction.customerId
                    )

            val wallet =
                checkNotNull(
                    ledgerDao.getCouponWallet(
                        replayCustomerId,
                        transaction.currencyCode
                    )
                )

            check(wallet.id == event.walletId)
        } else check(couponLedger.isEmpty())

        val creditCustomerId =
            transaction.customerId

        val operationCreditEvents =
            if (creditCustomerId == null) {
                emptyList()
            } else {
                ledgerDao
                    .getCreditLedgerForCustomer(
                        creditCustomerId
                    )
                    .filter {
                        it.operationId ==
                            operation.id
                    }
            }

        if (
            payload.destinationCategory ==
                PaymentCategory.CREDIT
        ) {
            val customerId =
                requireNotNull(
                    creditCustomerId
                ) {
                    "Credit Return replay customer is missing"
                }

            check(
                payload.destinationChannel ==
                    PaymentChannel.CREDIT_ACCOUNT
            ) {
                "Credit Return replay channel mismatch"
            }

            check(
                operationCreditEvents.size == 1
            ) {
                "Credit Return replay Credit evidence count mismatch"
            }

            val creditEvent =
                operationCreditEvents.single()

            check(
                creditEvent.id ==
                    creditRefundAdjustmentId(
                        operation.id
                    ) &&
                    creditEvent.customerId ==
                    customerId &&
                    creditEvent.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType
                        .ADJUSTMENT &&
                    creditEvent.transactionId ==
                    expectedTransactionId &&
                    creditEvent.repaymentId ==
                    null &&
                    creditEvent.currencyCode ==
                    transaction.currencyCode &&
                    creditEvent.amountSignedMinor ==
                    Math.negateExact(
                        replayAmount
                    ) &&
                    creditEvent.occurredAt ==
                    operation.occurredAt
            ) {
                "Credit Return replay Credit adjustment mismatch"
            }
        } else {
            check(
                operationCreditEvents.isEmpty()
            ) {
                "Non-Credit Refund unexpectedly created Credit evidence"
            }
        }

        return RefundExecutionResult(
            transactionId =
                expectedTransactionId,
            refundEventId =
                event.id,
            refundAmountMinor = replayAmount,
            couponWalletId = couponLedger.singleOrNull()?.walletId,
            couponBalanceAfterMinor = couponLedger.singleOrNull()?.let {
                ledgerDao.getCouponBalanceAfterEvent(it.walletId, it.id)
            }
        )
    }

    /**
     * BOARDLOCK Credit Return B1.
     *
     * Transaction-specific Credit outstanding must agree with the
     * authoritative transaction lifetime before the return can execute.
     *
     * No customer balance is stored or edited.
     */
    // ============================================================
    // BOARDLOCK CREDIT RETURN B2-B
    // PARTIALLY-PAID CREDIT RETURN - AUTHORITATIVE PLAN
    // ============================================================

    /**
     * Reconstructs the B2-A planner input entirely from persisted
     * authoritative financial evidence.
     *
     * No balance is stored here.
     * No original sale, repayment or allocation is modified.
     */
    private suspend fun buildCreditReturnB2Plan(
        transaction: TransactionEntity,
        record:
            com.dailymoneyregister.core.database.readmodel
                .TransactionFinancialRecord,
        allocations:
            List<
                com.dailymoneyregister.core.database.entity
                    .CreditRepaymentAllocationEntity
                >,
        requestedReturnMinor: Long,
        excludeReturnOperationId: String? = null
    ): CreditReturnPlan {

        val customerId =
            requireNotNull(transaction.customerId) {
                "Credit Return requires the original Credit customer"
            }

        val transactionCreditEvents =
            ledgerDao
                .getCreditLedgerForCustomer(customerId)
                .filter {
                    it.currencyCode ==
                        transaction.currencyCode &&
                        it.transactionId ==
                        transaction.id
                }

        val obligations =
            transactionCreditEvents.filter {
                it.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType.OBLIGATION
            }

        if (
            obligations.size != 1 ||
            obligations.single()
                .amountSignedMinor <= 0L
        ) {
            throw RefundUnsupportedCreditException(
                transaction.id
            )
        }

        val originalCreditAmountMinor =
            obligations.single()
                .amountSignedMinor

        /*
         * Only negative Return ADJUSTMENTs are allowed to represent
         * historical Credit extinguishment for this transaction.
         */
        val priorAdjustments =
            transactionCreditEvents.filter {
                it.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType.ADJUSTMENT &&
                    it.operationId !=
                        excludeReturnOperationId
            }

        if (
            priorAdjustments.any {
                it.amountSignedMinor >= 0L
            }
        ) {
            throw CommandRequiresReconciliationException(
                "Credit Return found non-return Credit adjustment evidence"
            )
        }

        val priorCreditReductionMinor =
            priorAdjustments.fold(0L) {
                    total,
                    event ->

                Math.addExact(
                    total,
                    Math.negateExact(
                        event.amountSignedMinor
                    )
                )
            }

        val historicalRefundEvents =
            record.refundEvents.filter {
                it.operationId !=
                    excludeReturnOperationId
            }

        val alreadyReturnedAmountMinor =
            historicalRefundEvents.fold(0L) {
                    total,
                    event ->

                Math.addExact(
                    total,
                    event.refundAmountMinor
                )
            }

        val priorPaidValueRefundMinor =
            Math.subtractExact(
                alreadyReturnedAmountMinor,
                priorCreditReductionMinor
            )

        if (priorPaidValueRefundMinor < 0L) {
            throw CommandRequiresReconciliationException(
                "Credit Return historical paid-value evidence does not reconcile"
            )
        }

        val repaymentIds =
            allocations
                .map { it.repaymentId }
                .distinct()

        val repayments =
            if (repaymentIds.isEmpty()) {
                emptyList()
            } else {
                ledgerDao
                    .getCreditRepaymentsByIds(
                        repaymentIds
                    )
            }

        if (
            repayments.size !=
            repaymentIds.size
        ) {
            throw CommandRequiresReconciliationException(
                "Credit Return repayment evidence is missing or ambiguous"
            )
        }

        val repaymentById =
            repayments.associateBy { it.id }

        if (
            repaymentById.size !=
            repayments.size
        ) {
            throw CommandRequiresReconciliationException(
                "Credit Return duplicate repayment evidence"
            )
        }

        val plannerEvidence =
            allocations.map { allocation ->

                val repayment =
                    repaymentById[
                        allocation.repaymentId
                    ] ?: throw CommandRequiresReconciliationException(
                        "Credit Return repayment evidence is missing"
                    )

                if (
                    repayment.customerId !=
                        customerId ||
                    repayment.currencyCode !=
                        transaction.currencyCode
                ) {
                    throw CommandRequiresReconciliationException(
                        "Credit Return repayment identity does not match original Credit sale"
                    )
                }

                val allAllocations =
                    ledgerDao
                        .getCreditRepaymentAllocations(
                            repayment.id
                        )

                val totalAllocated =
                    allAllocations.fold(0L) {
                            total,
                            item ->

                        Math.addExact(
                            total,
                            item.allocatedAmountMinor
                        )
                    }

                val collections =
                    transactionDao
                        .getSettlementsForOperation(
                            repayment.operationId
                        )
                        .filter {
                            it.entryPurpose ==
                                EntryPurpose
                                    .CREDIT_REPAYMENT_COLLECTION
                        }

                if (collections.size != 1) {
                    throw CommandRequiresReconciliationException(
                        "Credit Return repayment collection evidence is missing or ambiguous"
                    )
                }

                val collection =
                    collections.single()

                if (
                    collection.subjectType !=
                        SubjectType.REPAYMENT ||
                    collection.subjectId !=
                        repayment.id ||
                    collection.amountSignedMinor !=
                        repayment.amountMinor ||
                    collection.currencyCode !=
                        repayment.currencyCode ||
                    collection.category !=
                        repayment.paymentCategory ||
                    collection.occurredAt !=
                        repayment.occurredAt
                ) {
                    throw CommandRequiresReconciliationException(
                        "Credit Return repayment collection evidence does not reconcile"
                    )
                }

                CreditReturnRepaymentEvidence(
                    repaymentId =
                        repayment.id,
                    customerId =
                        repayment.customerId,
                    currencyCode =
                        repayment.currencyCode,
                    repaymentAmountMinor =
                        repayment.amountMinor,
                    totalAllocatedAcrossCreditMinor =
                        totalAllocated,
                    allocatedToTransactionMinor =
                        allocation
                            .allocatedAmountMinor,
                    paymentCategory =
                        collection.category,
                    paymentChannel =
                        collection.channel,
                    repaymentOccurredAt =
                        repayment.occurredAt
                )
            }

        return CreditReturnPlanner.plan(
            originalTransactionId =
                transaction.id,
            customerId =
                customerId,
            currencyCode =
                transaction.currencyCode,
            originalCreditAmountMinor =
                originalCreditAmountMinor,
            requestedReturnAmountMinor =
                requestedReturnMinor,
            alreadyReturnedAmountMinor =
                alreadyReturnedAmountMinor,
            priorCreditReductionMinor =
                priorCreditReductionMinor,
            priorPaidValueRefundMinor =
                priorPaidValueRefundMinor,
            repayments =
                plannerEvidence
        )
    }

    private fun creditReturnB2SettlementId(
        operationId: String,
        kind: String,
        index: Int
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-return-b2:$operationId:settlement:$kind:$index"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    private fun creditReturnB2CouponEventId(
        operationId: String,
        index: Int
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-return-b2:$operationId:coupon:$index"
                .toByteArray(Charsets.UTF_8)
        ).toString()
    private suspend fun validateUnpaidCreditReturnLedger(
        transaction: TransactionEntity,
        expectedRemainingMinor: Long
    ): String {

        val customerId =
            requireNotNull(
                transaction.customerId
            ) {
                "Credit Return requires the original Credit customer"
            }

        val events =
            ledgerDao
                .getCreditLedgerForCustomer(
                    customerId
                )
                .filter {
                    it.currencyCode ==
                        transaction.currencyCode &&
                        it.transactionId ==
                        transaction.id
                }

        val obligations =
            events.filter {
                it.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType
                        .OBLIGATION
            }

        if (
            obligations.size != 1 ||
            obligations.single()
                .amountSignedMinor <= 0L
        ) {
            throw RefundUnsupportedCreditException(
                transaction.id
            )
        }

        /*
         * B1 permits only:
         *   original positive OBLIGATION
         *   prior negative Return ADJUSTMENTs
         *
         * REPAYMENT is not transaction-linked in the ledger; repayment
         * allocations are checked separately by RefundFinancialIntegrity.
         */
        val unsupported =
            events.filter {
                it.type !=
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType
                        .OBLIGATION &&
                    it.type !=
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType
                        .ADJUSTMENT
            }

        if (unsupported.isNotEmpty()) {
            throw CommandRequiresReconciliationException(
                "Credit Return found unsupported transaction-linked Credit ledger evidence"
            )
        }

        val adjustments =
            events.filter {
                it.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType
                        .ADJUSTMENT
            }

        if (
            adjustments.any {
                it.amountSignedMinor >= 0L
            }
        ) {
            throw CommandRequiresReconciliationException(
                "Credit Return found non-return Credit adjustment evidence"
            )
        }

        var remaining =
            obligations.single()
                .amountSignedMinor

        for (adjustment in adjustments) {
            remaining =
                Math.addExact(
                    remaining,
                    adjustment.amountSignedMinor
                )
        }

        if (
            remaining < 0L ||
            remaining != expectedRemainingMinor
        ) {
            throw CommandRequiresReconciliationException(
                "Credit Return ledger outstanding does not match transaction lifetime"
            )
        }

        return customerId
    }

    private fun creditRefundAdjustmentId(
        operationId: String
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-return:$operationId"
                .toByteArray(Charsets.UTF_8)
        ).toString()
    private fun couponRefundEvidenceId(operationId: String): String =
        java.util.UUID.nameUUIDFromBytes("refund-to-coupon:$operationId".toByteArray(Charsets.UTF_8)).toString()

    private fun couponRefundWalletId(operationId: String): String =
        java.util.UUID.nameUUIDFromBytes("refund-to-coupon-wallet:$operationId".toByteArray(Charsets.UTF_8)).toString()

    private fun couponRefundAuthorizedUserId(
        operationId: String,
        index: Int
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "refund-to-coupon-user:$operationId:$index"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    private fun couponRefundAuthorizedUserEventId(
        operationId: String,
        index: Int
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "refund-to-coupon-user-event:$operationId:$index"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    /**
     * Recover only already-admitted PENDING Refund operations.
     *
     * Permission is deliberately NOT checked again because mutable current
     * permissions must not redefine historical durable intent.
     */
    suspend fun recoverPendingRefunds():
        List<RefundExecutionResult> {

        val operationDao =
            database.operationDao()

        val recovered =
            mutableListOf<RefundExecutionResult>()

        val pendingOperations =
            commandExecutor
                .getPendingOperations()
                .filter {
                    it.operationType ==
                        OperationType.RECORD_REFUND
                }

        for (candidate in pendingOperations) {

            val current =
                operationDao.getOperationById(
                    candidate.id
                ) ?: throw IllegalStateException(
                    "Pending Refund operation unavailable during recovery"
                )

            if (
                current.status ==
                OperationStatus.SUCCEEDED
            ) {
                recovered +=
                    resolveRefundReplay(
                        operation =
                            current,
                        expectedTransactionId =
                            current.subjectId
                    )

                continue
            }

            check(
                current.status ==
                    OperationStatus.PENDING
            ) {
                "Refund operation is no longer pending"
            }

            val command =
                try {
                    decodePersistedRefundCommand(
                        current
                    )
                } catch (error: RuntimeException) {

                    val reconciled =
                        reconcilePendingRefund(
                            current,
                            "Invalid durable Refund intent: " +
                                (error.message ?: "decode failure")
                        )

                    throw IllegalStateException(
                        (reconciled as?
                            CommandResult.Conflict)
                            ?.message
                            ?: "Refund requires financial reconciliation",
                        error
                    )
                }

            if (
                command.financialPrecondition ==
                null
            ) {
                val reconciled =
                    reconcilePendingRefund(
                        current,
                        "PENDING Refund lacks a financial-state precondition"
                    )

                throw IllegalStateException(
                    (reconciled as?
                        CommandResult.Conflict)
                        ?.message
                        ?: "Refund requires financial reconciliation"
                )
            }

            when (
                val result =
                    executeAdmittedRefund(command)
            ) {

                is CommandResult.Success -> {
                    recovered += result.data
                }

                is CommandResult.Conflict -> {
                    throw IllegalStateException(
                        result.message
                    )
                }

                is CommandResult.PermissionDenied -> {
                    throw IllegalStateException(
                        "Admitted Refund recovery unexpectedly requested permission"
                    )
                }

                is CommandResult.Error -> {
                    throw result.exception
                }
            }
        }

        return recovered
    }
    suspend fun executeVoid(command: VoidCommand): CommandResult<VoidExecutionResult> {
        val operationDao = database.operationDao()
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
                database.operationDao().getOperationById(operation.id)
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
                        database.operationDao()
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
        val operationDao = database.operationDao()
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
        val operationDao = database.operationDao()
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
        val operationDao = database.operationDao()
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

    fun observeRecentTransactions(branchId: String, limit: Int = 20): Flow<List<TransactionEntity>> {
        return transactionDao.observeRecentTransactions(branchId, limit)
    }

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






