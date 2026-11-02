package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.security.PermissionAction

data class RefundExecutionResult(
    val transactionId: String,
    val refundEventId: String,
    val refundAmountMinor: Long,
    val couponWalletId: String? = null,
    val couponBalanceAfterMinor: Long? = null
)

/**
 * Owns the complete durable Refund execution and recovery workflow.
 *
 * RegisterRepository remains the public compatibility facade. This
 * extraction preserves the existing command, transaction, replay,
 * reconciliation, authorization and cash-shift boundaries.
 */
internal class RefundFinancialRepository(
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
        suspend (OperationCommand) -> String?
) {
    private val transactionDao = database.transactionDao()
    private val ledgerDao = database.ledgerDao()

    private val refundAdmissionCoordinator = RefundAdmissionCoordinator(database)
    private val refundReplayCoordinator = RefundReplayCoordinator(
        database = database,
        commandExecutor = commandExecutor,
        admissionCoordinator = refundAdmissionCoordinator,
        executeAdmittedRefund = ::executeAdmittedRefund,
        buildCreditReturnB2Plan = ::buildCreditReturnB2Plan
    )

    suspend fun recoverPendingRefunds(): List<RefundExecutionResult> =
        refundReplayCoordinator.recoverPendingRefunds()

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
                    refundAdmissionCoordinator.decodePersistedRefundCommand(
                        existingOperation
                    )
                } catch (error: RuntimeException) {

                    if (
                        existingOperation.status ==
                        OperationStatus.PENDING
                    ) {
                        return refundReplayCoordinator.reconcilePendingRefund(
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
                return refundReplayCoordinator.reconcilePendingRefund(
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
        authorizeFinancialMutation(
            command.actorUserId,
            command.businessId,
            command.branchId,
            PermissionAction.REFUND,
            "Refund"
        )?.let { return it }

        /*
         * Capture the exact authoritative financial lifetime after
         * authorization and before durable admission.
         */
        val admittedCommand =
            refundAdmissionCoordinator.prepareRefundAdmission(command)

        return executeAdmittedRefund(
            admittedCommand
        )
    }


    private suspend fun executeAdmittedRefund(
        command: RefundCommand
    ): CommandResult<RefundExecutionResult> {

        return commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = { operation ->
                refundReplayCoordinator.resolveRefundReplay(
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

            /*
             * Validate persisted payment-method-change operations before Refund
             * admission resolves a destination or calculates refundable value.
             */
            if (authoritativeRecord.paymentMethodChanges.isNotEmpty()) {
                try {
                    PaymentMethodChangeFinancialIntegrity.validate(
                        record = authoritativeRecord,
                        creditAllocations = allocations
                    )
                } catch (_: PaymentMethodChangeAmbiguousSettlementException) {
                    throw RefundAmbiguousSettlementException(transaction.id)
                }
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
                                    RefundIdFactory.couponRefundWalletId(
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
                                        RefundIdFactory.creditReturnB2SettlementId(
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
                                                RefundIdFactory.creditReturnB2SettlementId(
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
                                        RefundIdFactory.creditReturnB2SettlementId(
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
                                    RefundIdFactory.creditRefundAdjustmentId(
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
                                            RefundIdFactory.creditReturnB2CouponEventId(
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
                    id = RefundIdFactory.couponRefundEvidenceId(command.operationId.value),
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


}
