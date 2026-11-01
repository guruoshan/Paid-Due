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
import com.dailymoneyregister.core.database.dao.LedgerDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.TransactionDao
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
 * Owns the complete transaction lifecycle financial workflows.
 *
 * Payment-method changes and Voids deliberately live together: Void
 * validation verifies payment-method reclassification evidence before
 * calculating the reversal. RegisterRepository remains the compatibility
 * facade and delegates execution and recovery to this collaborator.
 *
 * This is an extraction only. Durable operation identity, admission,
 * authorization, replay, reconciliation and recovery semantics remain
 * unchanged.
 */
internal class TransactionLifecycleFinancialRepository(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val operationDao: OperationDao,
    private val transactionDao: TransactionDao,
    private val ledgerDao: LedgerDao,
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

        authorizeFinancialMutation(
            command.actorUserId,
            command.businessId,
            command.branchId,
            PermissionAction.CHANGE_PAYMENT_METHOD,
            "Payment Method Change"
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
        authorizeFinancialMutation(
            command.actorUserId,
            command.businessId,
            command.branchId,
            PermissionAction.VOID,
            "Void"
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
}
