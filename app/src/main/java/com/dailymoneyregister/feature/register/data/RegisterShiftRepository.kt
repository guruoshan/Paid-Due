package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CashShiftDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.SessionManager

/**
 * Owns the write-side Register Shift lifecycle.
 *
 * Durable command admission, replay, event materialization and recovery stay
 * together here. Read projections continue to use the existing
 * RegisterShiftStateReadRepository collaborator.
 */
internal class RegisterShiftRepository(
    private val commandExecutor: CommandExecutor,
    private val operationDao: OperationDao,
    private val cashShiftDao: CashShiftDao,
    private val businessBranchDao: BusinessBranchDao,
    private val userDao: UserDao,
    private val registerShiftStateReadRepository: RegisterShiftStateReadRepository
) {
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
                registerShiftStateReadRepository.readRegisterShift(
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
                registerShiftStateReadRepository.readRegisterShift(
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
                registerShiftStateReadRepository.readRegisterShift(
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
            operationDao.getOperationById(operationId.value)

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

        return registerShiftStateReadRepository.readRegisterShift(
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

        return registerShiftStateReadRepository.readRegisterShift(
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

        return registerShiftStateReadRepository.readRegisterShift(
            shiftId = command.shiftId.value,
            expectedBranchId = command.branchId,
            expectedBusinessId = command.businessId
        )
    }

    // Retained as a private characterization-test seam; production callers use the collaborator directly.
    internal suspend fun readRegisterShift(
        shiftId: String,
        expectedBranchId: String,
        expectedBusinessId: String
    ): RegisterShiftResult =
        registerShiftStateReadRepository.readRegisterShift(
            shiftId = shiftId,
            expectedBranchId = expectedBranchId,
            expectedBusinessId = expectedBusinessId
        )
}
