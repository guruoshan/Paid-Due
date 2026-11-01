package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.security.PermissionAction

/**
 * Owns the complete ordinary and Coupon Credit repayment workflows.
 *
 * RegisterRepository remains the compatibility facade. This extraction
 * preserves existing authorization, durable command, cash-shift attribution,
 * CommandExecutor transaction, replay, FIFO allocation and recovery behavior.
 */
internal class CreditRepaymentFinancialRepository(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val authorizeNewFinancialMutation: suspend (
        actorUserId: String,
        businessId: String,
        branchId: String,
        action: PermissionAction,
        actionLabel: String
    ) -> CommandResult<Nothing>?,
    private val resolveFinancialCashShiftId: suspend (OperationCommand) -> String?
) {
    private val transactionDao = database.transactionDao()
    private val ledgerDao = database.ledgerDao()
    private val businessBranchDao = database.businessBranchDao()

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
    ): RegisterRepository.CreditRepaymentExecutionResult {

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
    ): CommandResult<RegisterRepository.CreditRepaymentExecutionResult> {

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
    ): CommandResult<RegisterRepository.CreditRepaymentExecutionResult> =

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
                RegisterRepository.CouponAuthorizedUserDetails(
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
             * Positive Amount Correction adjustments increase this sale's
             * effective obligation below; they are not interpreted as return
             * debt reduction. Negative adjustments (returns and downward
             * corrections) reduce the obligation independently of repayments.
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
    ): RegisterRepository.CreditRepaymentExecutionResult {

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

        return RegisterRepository.CreditRepaymentExecutionResult(
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
    suspend fun executeCreditRepayment(
        command:
            com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
    ): CommandResult<RegisterRepository.CreditRepaymentExecutionResult> {

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
    ): CommandResult<RegisterRepository.CreditRepaymentExecutionResult> =
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
}
