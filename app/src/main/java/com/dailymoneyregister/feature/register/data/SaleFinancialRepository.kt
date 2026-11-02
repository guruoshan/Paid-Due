/**
 * Owns the authoritative cash, credit, and online sale execution and recovery
 * lifecycles. RegisterRepository remains the public compatibility facade.
 *
 * Admission and recovery paths intentionally remain distinct: cash performs
 * its authenticated actor/branch/permission checks, credit preserves its
 * customer/credit authorization path, and online preserves its own existing
 * operation and online authorization checks.
 */
internal class SaleFinancialRepository(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val saleFinancialWriter: SaleFinancialWriter,
    private val financialMutationAuthorizer: FinancialMutationAuthorizer,
    private val resolveCashShiftId: suspend (OperationCommand) -> String?,
    private val registerCustomerReadRepository: RegisterCustomerReadRepository,
    private val transactionDao: com.dailymoneyregister.core.database.dao.TransactionDao,
    private val ledgerDao: com.dailymoneyregister.core.database.dao.LedgerDao,
    private val businessBranchDao: com.dailymoneyregister.core.database.dao.BusinessBranchDao,
    private val userDao: com.dailymoneyregister.core.database.dao.UserDao,
    private val operationDao: com.dailymoneyregister.core.database.dao.OperationDao
) {
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
            cashShiftId = resolveCashShiftId(command),
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
}
