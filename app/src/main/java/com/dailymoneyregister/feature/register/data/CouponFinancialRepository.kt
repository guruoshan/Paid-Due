package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.SessionManager

/**
 * BOARDLOCK RegisterRepository decomposition.
 *
 * Owns coupon customer/wallet lookup and all coupon financial workflows:
 * sale, redemption, top-up, authorized users, evidence verification,
 * durable replay and pending-operation recovery.
 *
 * RegisterRepository remains the compatibility facade.
 *
 * No schema, migration, command payload, operation identity,
 * request fingerprint or financial calculation is redefined here.
 */
internal class CouponFinancialRepository(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val authorizeFinancialMutation:
        suspend (
            actorUserId: String,
            businessId: String,
            branchId: String,
            action: PermissionAction,
            actionLabel: String
        ) -> CommandResult<Nothing>?,
    private val resolveFinancialCashShiftId:
        suspend (OperationCommand) -> String,
    private val saleFinancialWriter: SaleFinancialWriter
) {
    private val transactionDao = database.transactionDao()
    private val ledgerDao = database.ledgerDao()
    private val operationDao = database.operationDao()
    private val businessBranchDao = database.businessBranchDao()

    suspend fun searchCouponSaleCustomers(
        query: String
    ): List<CouponSaleCustomerWallet> {
        val session =
            SessionManager.currentSession.value
                ?: return emptyList()

        return database.customerDao()
            .searchCustomers(
                session.business.id,
                query.trim()
            )
            .map { customer ->
                val wallet =
                    ledgerDao.getCouponWallet(
                        customer.id,
                        session.branch.currencyCode
                    )

                CouponSaleCustomerWallet(
                    customer = customer,
                    wallet = wallet,
                    balanceMinor =
                        wallet?.let {
                            ledgerDao.getCouponBalance(it.id)
                        } ?: 0L
                )
            }
    }

    suspend fun executeCouponTopUp(
        command: CouponTopUpCommand
    ): CommandResult<CouponTopUpResult> {
        return try {
            command.validate()

            val existing =
                database.operationDao()
                    .getOperationById(command.operationId.value)

            if (existing != null) {
                if (
                    existing.requestFingerprint !=
                        command.requestFingerprint ||
                    existing.intentPayload !=
                        command.intentPayload
                ) {
                    return CommandResult.Conflict(
                        "Coupon Top Up operation already belongs to a different request"
                    )
                }

                return executeAdmittedCouponTopUp(
                    CouponTopUpPayloadCodec.decode(existing)
                )
            }

            for (
                action in listOf(
                    PermissionAction.USE_COUPON,
                    if (
                        command.fundingCategory ==
                            PaymentCategory.CASH
                    ) {
                        PermissionAction.ACCEPT_CASH
                    } else {
                        PermissionAction.ACCEPT_ONLINE
                    }
                )
            ) {
                authorizeFinancialMutation(
                    command.actorUserId,
                    command.businessId,
                    command.branchId,
                    action,
                    "Coupon Top Up"
                )?.let {
                    return it
                }
            }

            executeAdmittedCouponTopUp(command)

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    internal suspend fun executeAdmittedCouponTopUp(
        command: CouponTopUpCommand,
        recovering: Boolean = false
    ): CommandResult<CouponTopUpResult> =
        commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = {
                loadCouponTopUpEvidence(command)
            }
        ) {
            try {
                val branch =
                    checkNotNull(
                        database.businessBranchDao()
                            .getBranchById(command.branchId)
                    )

                require(
                    branch.businessId == command.businessId &&
                        branch.currencyCode ==
                            command.currencyCode &&
                        branch.currencyDecimalPlaces ==
                            command.currencyDecimalPlaces
                ) {
                    "Coupon Top Up branch/currency mismatch"
                }

                val customer =
                    checkNotNull(
                        database.customerDao()
                            .getCustomerById(command.customerId)
                    ) {
                        "Coupon Top Up customer does not exist"
                    }

                require(
                    customer.businessId == command.businessId &&
                        customer.name == command.customerName &&
                        customer.canonicalPhone ==
                            command.canonicalPhone
                ) {
                    "Customer identity changed"
                }

                val wallet =
                    checkNotNull(
                        ledgerDao.getCouponWallet(
                            command.customerId,
                            command.currencyCode
                        )
                    ) {
                        "Coupon Top Up requires an existing coupon wallet"
                    }

                require(
                    wallet.id == command.walletId &&
                        wallet.isActive
                ) {
                    "Coupon wallet is inactive or mismatched"
                }

                Math.addExact(
                    ledgerDao.getCouponBalance(wallet.id),
                    command.amountMinor
                )

                require(
                    transactionDao.getTransactionById(
                        command.subjectId
                    ) == null
                ) {
                    throw CommandRequiresReconciliationException(
                        "Coupon Top Up transaction already exists"
                    )
                }

                require(
                    transactionDao
                        .getSettlementsForCashSaleRecovery(
                            command.operationId.value,
                            command.subjectId
                        )
                        .isEmpty()
                ) {
                    throw CommandRequiresReconciliationException(
                        "Partial Coupon Top Up financial evidence"
                    )
                }

                saleFinancialWriter.materialize(
                    command.writerIntent()
                )

                ledgerDao.insertCouponEvent(
                    com.dailymoneyregister.core.database.entity
                        .CouponLedgerEventEntity(
                            id =
                                couponTopUpEvidenceId(
                                    command,
                                    "ledger"
                                ),
                            operationId =
                                command.operationId.value,
                            walletId =
                                wallet.id,
                            type =
                                com.dailymoneyregister.core.model.enums
                                    .CouponLedgerType.PURCHASE,
                            amountSignedMinor =
                                command.amountMinor,
                            currencyCode =
                                command.currencyCode,
                            transactionId =
                                command.subjectId,
                            occurredAt =
                                command.occurredAt
                        )
                )

                loadCouponTopUpEvidence(command) to
                    ("TRANSACTION" to command.subjectId)

            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (
                e: CommandRequiresReconciliationException
            ) {
                throw e
            } catch (e: Exception) {
                if (recovering) {
                    throw CommandRequiresReconciliationException(
                        "Coupon Top Up recovery evidence mismatch: ${e.message}"
                    )
                }

                throw e
            }
        }

    private fun couponTopUpEvidenceId(
        command: CouponTopUpCommand,
        suffix: String
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "coupon-topup:${command.operationId.value}:$suffix"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    private suspend fun loadCouponTopUpEvidence(
        command: CouponTopUpCommand
    ): CouponTopUpResult {
        val transaction =
            saleFinancialWriter.loadVerified(
                command.writerIntent()
            )

        val wallet =
            checkNotNull(
                ledgerDao.getCouponWallet(
                    command.customerId,
                    command.currencyCode
                )
            )

        val expected =
            com.dailymoneyregister.core.database.entity
                .CouponLedgerEventEntity(
                    id =
                        couponTopUpEvidenceId(
                            command,
                            "ledger"
                        ),
                    operationId =
                        command.operationId.value,
                    walletId =
                        wallet.id,
                    type =
                        com.dailymoneyregister.core.model.enums
                            .CouponLedgerType.PURCHASE,
                    amountSignedMinor =
                        command.amountMinor,
                    currencyCode =
                        command.currencyCode,
                    transactionId =
                        command.subjectId,
                    occurredAt =
                        command.occurredAt
                )

        check(
            ledgerDao.getCouponLedgerForWallet(wallet.id)
                .filter {
                    it.operationId ==
                        command.operationId.value
                } == listOf(expected)
        ) {
            "Coupon Top Up ledger evidence mismatch"
        }

        return CouponTopUpResult(
            transaction,
            wallet.id,
            command.amountMinor
        )
    }

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
                val result = authorizeFinancialMutation(c.actorUserId, c.businessId, c.branchId, action, "Use Coupon")
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
            val details = RegisterRepository.CouponAuthorizedUserDetails(user, ledgerDao.getAuthorizedUserEvents(user.id))
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
            authorizeFinancialMutation(command.actorUserId, command.businessId, command.branchId,
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

    suspend fun executeCouponAuthorizedUser(command: CouponAuthorizedUserCommand): CommandResult<RegisterRepository.CouponAuthorizedUserDetails> {
        return try {
            command.validate()
            val existing = operationDao.getOperationById(command.operationId.value)
            if (existing != null) {
                if (existing.requestFingerprint != command.requestFingerprint) return CommandResult.Conflict("Authorization request changed")
                executeAdmittedCouponAuthorizedUser(CouponAuthorizedUserCommand.decode(existing))
            } else {
                authorizeFinancialMutation(command.actorUserId, command.businessId, command.branchId,
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

    private suspend fun authorizationEvidence(c: CouponAuthorizedUserCommand): RegisterRepository.CouponAuthorizedUserDetails {
        val wallet = checkNotNull(ledgerDao.getCouponWallet(c.customerId, c.currencyCode))
        check(wallet.id == c.walletId)
        val user = ledgerDao.getActiveAuthorizedUsers(wallet.id).single { it.id == c.userId }
        val events = ledgerDao.getAuthorizedUserEvents(user.id)
        check(events.filter { it.operationId == c.operationId.value } == listOf(authorizationEvent(c)))
        return RegisterRepository.CouponAuthorizedUserDetails(user, events)
    }

    private suspend fun executeAdmittedCouponAuthorizedUser(c: CouponAuthorizedUserCommand): CommandResult<RegisterRepository.CouponAuthorizedUserDetails> =
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
                require(RegisterRepository.CouponAuthorizedUserDetails(existing, events).isActive) { "Authorized user is inactive" }
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


}
