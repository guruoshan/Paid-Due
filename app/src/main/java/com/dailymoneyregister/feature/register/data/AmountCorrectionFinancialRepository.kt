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


/**
 * Owns the complete Amount Correction financial workflow.
 *
 * RegisterRepository remains the public compatibility facade. This extraction
 * preserves durable admission, financial preconditions, authorization,
 * CommandExecutor transaction semantics, replay verification and recovery.
 */
internal class AmountCorrectionFinancialRepository(
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
            command.actorUserId,
            command.businessId,
            command.branchId,
            PermissionAction.CORRECT_AMOUNT,
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
}
