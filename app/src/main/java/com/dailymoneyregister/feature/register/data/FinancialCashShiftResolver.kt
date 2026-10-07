package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BOARDLOCK automatic cashier-shift attribution boundary.
 *
 * Existing persisted Operation.cashShiftId remains authoritative.
 *
 * This component does not calculate sales, refunds, Credit, Coupon,
 * Expense, settlement, or balance amounts.
 *
 * It owns the existing process-local admission mutex and delegates actual
 * shift creation to the existing openRegisterShift command path.
 */
internal class FinancialCashShiftResolver(
    private val database: DmrDatabase,
    private val openRegisterShift:
        suspend (OpenRegisterShiftCommand) ->
            CommandResult<RegisterShiftResult>
) {
    private val cashShiftDao =
        database.cashShiftDao()

    private val financialShiftAdmissionMutex =
        Mutex()

    suspend fun resolveFinancialCashShiftId(
        command: OperationCommand
    ): String? =
        financialShiftAdmissionMutex.withLock {

            val operationDao = database.operationDao()

            /*
             * Recovery/replay path.
             *
             * Once an Operation exists, its persisted shift attribution is
             * authoritative. NULL remains NULL for historical operations.
             */
            operationDao.getOperationById(command.operationId.value)
                ?.let { existing ->
                    /*
                     * Existing durable attribution is immutable.
                     *
                     * NULL is valid for historical operations admitted
                     * before cashier-shift attribution existed.
                     *
                     * Never invent or retrofit a shift during replay.
                     */
                    return@withLock existing.cashShiftId
                }

            val latest =
                cashShiftDao.getLatestShiftForCashier(
                    branchId = requireNotNull(command.branchId) {
                        "Cashier financial operation requires a branch"
                    },
                    userId = command.actorUserId
                )

            if (latest != null) {
                val events =
                    cashShiftDao.getEventsForShift(latest.id)

                val isOpen =
                    events.firstOrNull {
                        it.type == CashShiftEventType.OPENED
                    } != null &&
                        events.none {
                            it.type == CashShiftEventType.CLOSED
                        }

                if (isOpen) {
                    return@withLock latest.id
                }
            }

            /*
             * No open shift exists for this cashier.
             *
             * The financial operation has already passed its admission
             * checks before this helper is reached, so opening the shift
             * cannot bypass financial permissions.
             */
            val shiftId = CashShiftId.generate()

            val openCommand =
                OpenRegisterShiftCommand(
                    operationId = OperationId.generate(),
                    businessId = command.businessId,
                    branchId = requireNotNull(command.branchId) {
                        "Cashier financial operation requires a branch"
                    },
                    shiftId = shiftId,
                    actorUserId = command.actorUserId,
                    deviceId = command.deviceId,
                    occurredAt = command.occurredAt
                )

            when (val opened = openRegisterShift(openCommand)) {

                is CommandResult.Success ->
                    return@withLock opened.data.shiftId.value

                is CommandResult.Conflict ->
                    throw IllegalStateException(
                        "Unable to establish cashier shift: " +
                            opened.message
                    )

                is CommandResult.PermissionDenied ->
                    throw IllegalStateException(
                        "Unable to establish cashier shift: " +
                            opened.message
                    )

                is CommandResult.Error ->
                    throw opened.exception
            }
        }
}
