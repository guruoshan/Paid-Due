package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.ids.CashShiftId

/**
 * Read-only projection of persisted cashier-shift lifecycle state.
 *
 * This owns the shared identity validation and event-to-state projection
 * previously implemented inside RegisterRepository.
 */
internal class RegisterShiftStateReadRepository(
    private val database: DmrDatabase
) {
    private val cashShiftDao = database.cashShiftDao()
    private val businessBranchDao = database.businessBranchDao()

    suspend fun readRegisterShift(
        shiftId: String,
        expectedBranchId: String,
        expectedBusinessId: String
    ): RegisterShiftResult {
        val shift = cashShiftDao.getShiftById(shiftId)
            ?: error("Register Shift '$shiftId' does not exist")
        check(shift.branchId == expectedBranchId) {
            "Register Shift branch identity mismatch"
        }
        check(businessBranchDao.getBranchById(shift.branchId)?.businessId == expectedBusinessId) {
            "Register Shift business identity mismatch"
        }
        val events = cashShiftDao.getEventsForShift(shift.id)
        val opened = events.firstOrNull { it.type == CashShiftEventType.OPENED }
            ?: error("Register Shift '$shiftId' has no OPENED event")
        val counted = events
            .filter { it.type == CashShiftEventType.COUNTED }
            .maxByOrNull { it.occurredAt }

        val closed = events
            .firstOrNull { it.type == CashShiftEventType.CLOSED }

        return RegisterShiftResult(
            shiftId = CashShiftId(shift.id),
            branchId = shift.branchId,
            actorUserId = shift.userId,
            openedAt = opened.occurredAt,
            isOpen = closed == null,
            countedCashMinor = counted?.amountMinor,
            countedAt = counted?.occurredAt,
            closedAt = closed?.occurredAt
        )
    }

}
