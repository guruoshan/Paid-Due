package com.dailymoneyregister.core.command

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.cloud.sync.FinancialSyncDeliveryState
import com.dailymoneyregister.core.cloud.sync.FinancialSyncOperationPolicy
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import kotlinx.coroutines.CancellationException

class CommandRequiresReconciliationException(message: String) :
    IllegalStateException(message)

/**
 * Universal Command Execution Spine.
 *
 * Guarantees:
 * - One durable Operation ID represents one business action.
 * - The same Operation ID cannot be reused with a changed request.
 * - Concurrent executions are resolved against authoritative DB state.
 * - SUCCEEDED can never later become FAILED.
 * - Successful operations can be safely replayed.
 * - Interrupted/cancelled work remains PENDING for recovery.
 */
class CommandExecutor(
    private val database: DmrDatabase
) {
    private val operationDao = database.operationDao()

    suspend fun <T> execute(
        command: OperationCommand,
        cashShiftId: String? = command.cashShiftId,
        replayResolver: suspend (OperationEntity) -> T,
        executionBlock: suspend () -> Pair<T, Pair<String, String>>
    ): CommandResult<T> {

        val opId = command.operationId.value

        val pendingEntity = OperationEntity(
            id = opId,
            businessId = command.businessId,
            branchId = command.branchId,
            operationType = command.operationType,
            subjectType = command.subjectType,
            subjectId = command.subjectId,
            transactionId = command.transactionId,
            cashShiftId = cashShiftId,
            actorUserId = command.actorUserId,
            deviceId = command.deviceId,
            occurredAt = command.occurredAt,
            requestFingerprint = command.requestFingerprint,
            commandSchemaVersion = command.commandSchemaVersion,
            intentPayload = command.intentPayload,
            status = OperationStatus.PENDING
        )

        /*
         * Concurrent callers may both reach this point.
         *
         * Exactly one insert wins.
         * Any competing insert is ignored and then reads the same
         * authoritative operation row.
         */
        operationDao.insertOperationIfAbsent(pendingEntity)

        val persisted = operationDao.getOperationById(opId)
            ?: return CommandResult.Error(
                IllegalStateException(
                    "Operation '$opId' could not be persisted or loaded."
                )
            )

        /*
         * An Operation ID permanently belongs to one request.
         */
        if (persisted.requestFingerprint != command.requestFingerprint) {
            return CommandResult.Conflict(
                "Conflict: Operation ID '$opId' has already been used " +
                    "with a different request fingerprint."
            )
        }

        if (!persisted.matchesDurableIntent(command, cashShiftId)) {
            return CommandResult.Conflict(
                "Conflict: Operation ID '$opId' has a different durable intent."
            )
        }

        when (persisted.status) {

            OperationStatus.SUCCEEDED -> {
                return CommandResult.Success(
                    data = replayResolver(persisted),
                    isIdempotentReplay = true
                )
            }

            OperationStatus.FAILED -> {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '$opId' previously failed " +
                        "and cannot be re-executed. A new Operation ID is required."
                )
            }

            OperationStatus.REQUIRES_RECONCILIATION -> {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '$opId' requires reconciliation."
                )
            }

            OperationStatus.PENDING -> Unit
        }

        return try {

            /*
             * Critical concurrency boundary.
             *
             * Room serializes the transaction. A competing execution
             * must re-read the Operation after entering this boundary.
             */
            database.withTransaction {

                val current = operationDao.getOperationById(opId)
                    ?: throw IllegalStateException(
                        "Operation '$opId' disappeared before execution."
                    )

                if (current.requestFingerprint != command.requestFingerprint) {
                    return@withTransaction CommandResult.Conflict(
                        "Conflict: Operation ID '$opId' has a different request fingerprint."
                    )
                }

                if (!current.matchesDurableIntent(command, cashShiftId)) {
                    return@withTransaction CommandResult.Conflict(
                        "Conflict: Operation ID '$opId' has a different durable intent."
                    )
                }

                when (current.status) {

                    OperationStatus.SUCCEEDED -> {
                        CommandResult.Success(
                            data = replayResolver(current),
                            isIdempotentReplay = true
                        )
                    }

                    OperationStatus.FAILED -> {
                        CommandResult.Conflict(
                            "Conflict: Operation ID '$opId' previously failed."
                        )
                    }

                    OperationStatus.REQUIRES_RECONCILIATION -> {
                        CommandResult.Conflict(
                            "Conflict: Operation ID '$opId' requires reconciliation."
                        )
                    }

                    OperationStatus.PENDING -> {

                        val executionResult = executionBlock()
                        val resultData = executionResult.first
                        val refType = executionResult.second.first
                        val refId = executionResult.second.second

                        OperationStateMachine.assertTransitionAllowed(
                            OperationStatus.PENDING,
                            OperationStatus.SUCCEEDED
                        )

                        /*
                         * Database-enforced state transition.
                         *
                         * Only PENDING may become SUCCEEDED.
                         */
                        val changedRows = operationDao.markSucceededIfPending(
                            operationId = opId,
                            requestFingerprint = command.requestFingerprint,
                            resultReferenceType = refType,
                            resultReferenceId = refId
                        )

                        check(changedRows == 1) {
                            "Operation '$opId' lost its PENDING state " +
                                "before success could be recorded."
                        }

                        /*
                         * Admit remote delivery in the same Room transaction as the
                         * financial write and SUCCEEDED transition. A process death
                         * therefore cannot leave a committed local operation without
                         * its delivery metadata.
                         *
                         * The outbox contains no financial authority. For commands
                         * without a transaction row, transaction_id is an empty
                         * lookup sentinel; the canonical command remains in operations.
                         */
                        val actor = database.userDao().getUserByIdInBusiness(
                            userId = current.actorUserId,
                            businessId = current.businessId
                        ) ?: throw IllegalStateException(
                            "Operation actor is missing from its business."
                        )
                        val deliveryState = FinancialSyncOperationPolicy.initialState(
                            role = actor.role,
                            type = current.operationType
                        )
                        if (deliveryState != FinancialSyncDeliveryState.NOT_APPLICABLE) {
                            val branchId = current.branchId
                                ?: throw IllegalStateException(
                                    "A synchronizable operation must have a branch."
                                )
                            val outbox = FinancialSyncOutboxEntity(
                                operationId = current.id,
                                businessId = current.businessId,
                                branchId = branchId,
                                transactionId = current.transactionId.orEmpty(),
                                actorUserId = current.actorUserId,
                                deviceId = current.deviceId,
                                requestFingerprint = current.requestFingerprint,
                                commandSchemaVersion = current.commandSchemaVersion,
                                occurredAt = current.occurredAt,
                                status = deliveryState.name,
                                attemptCount = 0
                            )
                            val inserted = database.financialSyncOutboxDao()
                                .insertOutbox(outbox)
                            if (inserted == -1L) {
                                val existing = database.financialSyncOutboxDao()
                                    .findByOperationId(current.id)
                                check(existing == outbox) {
                                    "Outbox identity conflicts with operation '$opId'."
                                }
                            }
                        }

                        CommandResult.Success(
                            data = resultData,
                            isIdempotentReplay = false
                        )
                    }
                }
            }

        } catch (cancelled: CancellationException) {

            /*
             * Cancellation or process interruption is not proof that
             * the business operation failed.
             *
             * Leave the Operation PENDING so startup recovery can
             * safely resolve it.
             */
            throw cancelled

        } catch (reconciliation: CommandRequiresReconciliationException) {

            /*
             * A proven mismatch between durable intent and current financial
             * evidence is not an ordinary execution failure. The operation
             * must remain visible for explicit reconciliation and must never
             * be silently retried against a different financial state.
             */
            val reconciliationRows = try {
                OperationStateMachine.assertTransitionAllowed(
                    OperationStatus.PENDING,
                    OperationStatus.REQUIRES_RECONCILIATION
                )

                operationDao.markRequiresReconciliationIfPending(
                    operationId = opId,
                    requestFingerprint = command.requestFingerprint,
                    reason = reconciliation.message ?: "Financial reconciliation required"
                )
            } catch (_: Throwable) {
                0
            }

            if (reconciliationRows == 1) {
                return CommandResult.Conflict(
                    "Conflict: Operation ID '$opId' requires reconciliation: " +
                        (reconciliation.message ?: "financial evidence changed")
                )
            }

            val latest = operationDao.getOperationById(opId)

            if (
                latest != null &&
                latest.requestFingerprint == command.requestFingerprint
            ) {
                when (latest.status) {
                    OperationStatus.SUCCEEDED -> {
                        return CommandResult.Success(
                            data = replayResolver(latest),
                            isIdempotentReplay = true
                        )
                    }

                    OperationStatus.REQUIRES_RECONCILIATION -> {
                        return CommandResult.Conflict(
                            "Conflict: Operation ID '$opId' requires reconciliation."
                        )
                    }

                    OperationStatus.FAILED -> {
                        return CommandResult.Conflict(
                            "Conflict: Operation ID '$opId' is already FAILED."
                        )
                    }

                    OperationStatus.PENDING -> Unit
                }
            }

            CommandResult.Error(
                IllegalStateException(
                    "Operation '$opId' could not enter reconciliation state.",
                    reconciliation
                )
            )

        } catch (error: Throwable) {

            /*
             * Only a still-PENDING operation may become FAILED.
             *
             * Therefore a competing execution that already reached
             * SUCCEEDED can never be overwritten as FAILED here.
             */
            val failedRows = try {

                OperationStateMachine.assertTransitionAllowed(
                    OperationStatus.PENDING,
                    OperationStatus.FAILED
                )

                operationDao.markFailedIfPending(
                    operationId = opId,
                    requestFingerprint = command.requestFingerprint,
                    reason = error.message ?: "Execution failed"
                )

            } catch (_: Throwable) {
                0
            }

            /*
             * If zero rows changed, another execution may have
             * completed this Operation while this caller was failing.
             */
            if (failedRows == 0) {

                val latest = operationDao.getOperationById(opId)

                if (
                    latest != null &&
                    latest.requestFingerprint == command.requestFingerprint
                ) {
                    when (latest.status) {

                        OperationStatus.SUCCEEDED -> {
                            return CommandResult.Success(
                                data = replayResolver(latest),
                                isIdempotentReplay = true
                            )
                        }

                        OperationStatus.FAILED -> {
                            return CommandResult.Conflict(
                                "Conflict: Operation ID '$opId' is already FAILED."
                            )
                        }

                        OperationStatus.REQUIRES_RECONCILIATION -> {
                            return CommandResult.Conflict(
                                "Conflict: Operation ID '$opId' requires reconciliation."
                            )
                        }

                        OperationStatus.PENDING -> Unit
                    }
                }
            }

            CommandResult.Error(error)
        }
    }

    private fun OperationEntity.matchesDurableIntent(
        command: OperationCommand,
        expectedCashShiftId: String?
    ): Boolean {
        return businessId == command.businessId &&
            branchId == command.branchId &&
            operationType == command.operationType &&
            subjectType == command.subjectType &&
            subjectId == command.subjectId &&
            transactionId == command.transactionId &&
            cashShiftId == expectedCashShiftId &&
            actorUserId == command.actorUserId &&
            deviceId == command.deviceId &&
            occurredAt == command.occurredAt &&
            requestFingerprint == command.requestFingerprint &&
            commandSchemaVersion == command.commandSchemaVersion &&
            intentPayload == command.intentPayload
    }

    /**
     * Returns durable operations that were interrupted before reaching
     * a terminal state.
     */
    suspend fun getPendingOperations(): List<OperationEntity> {
        return operationDao.getPendingOperations()
    }
}
