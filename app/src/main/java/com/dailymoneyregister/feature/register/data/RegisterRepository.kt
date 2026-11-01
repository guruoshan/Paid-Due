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






