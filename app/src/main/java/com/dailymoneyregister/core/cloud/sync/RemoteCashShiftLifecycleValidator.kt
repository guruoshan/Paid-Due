package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import org.json.JSONObject

internal object RemoteCashShiftLifecycleValidator {
    suspend fun validate(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        operationType: OperationType,
        branchId: String,
        actorUserId: String,
        shiftJson: JSONObject?,
        shiftEventJson: JSONObject?
    ): InboundProcessResult? {
        val payload = envelope.payloadJson
        
        if (shiftJson == null || shiftEventJson == null) {
            return InboundProcessResult.Rejected("Missing cash-shift identity/event evidence")
        }
        val expectedEvent = when (operationType) {
            OperationType.OPEN_CASH_SHIFT -> CashShiftEventType.OPENED
            OperationType.COUNT_CASH_SHIFT -> CashShiftEventType.COUNTED
            else -> CashShiftEventType.CLOSED
        }
        if (
            shiftJson.optString("branch_id") != branchId ||
            shiftEventJson.optString("operation_id") != envelope.operationId ||
            shiftEventJson.optString("type") != expectedEvent.name ||
            shiftEventJson.optString("actor_user_id") != actorUserId
        ) {
            return InboundProcessResult.Rejected("Cash-shift operation/event identity mismatch")
        }
        val expectedShiftAmount = shiftEventJson.optLong("amount_minor", Long.MIN_VALUE)
        val expectedShiftOccurredAt = payload.optLong("occurred_at", Long.MAX_VALUE)
        if (
            shiftEventJson.optString("id") != envelope.operationId + ":" + expectedEvent.name ||
            shiftEventJson.optLong("occurred_at", Long.MIN_VALUE) != expectedShiftOccurredAt ||
            expectedShiftAmount < 0L ||
            (operationType == OperationType.OPEN_CASH_SHIFT && expectedShiftAmount != 0L) ||
            (operationType == OperationType.CLOSE_CASH_SHIFT && expectedShiftAmount != 0L) ||
            (operationType == OperationType.OPEN_CASH_SHIFT &&
                shiftJson.optString("user_id") != actorUserId)
        ) {
            return InboundProcessResult.Rejected("Cash-shift lifecycle amount, actor, or event identity is invalid")
        }
        val shiftId = shiftJson.optString("id")
        if (
            shiftId.isBlank() ||
            payload.optString("subject_type") != SubjectType.CASH_SHIFT.name ||
            payload.optString("subject_id") != shiftId ||
            shiftEventJson.optString("shift_id") != shiftId
        ) {
            return InboundProcessResult.Rejected(
                "Cash-shift event identity must match the authoritative envelope subject"
            )
        }
        val existingShift = database.cashShiftDao().getShiftById(shiftId)
        val existingShiftEvents = if (existingShift == null) {
            emptyList()
        } else {
            if (existingShift.branchId != branchId) {
                return InboundProcessResult.Rejected("Cash-shift lifecycle references a shift from another branch")
            }
            database.cashShiftDao().getEventsForShift(shiftId)
        }
        when (operationType) {
            OperationType.OPEN_CASH_SHIFT -> {
                if (existingShift != null || existingShiftEvents.isNotEmpty()) {
                    return InboundProcessResult.Rejected("Cash shift already exists before its inbound OPENED event")
                }
            }
            OperationType.COUNT_CASH_SHIFT -> {
                if (
                    existingShift == null ||
                    existingShiftEvents.none { it.type == CashShiftEventType.OPENED } ||
                    existingShiftEvents.any { it.type == CashShiftEventType.CLOSED }
                ) {
                    return InboundProcessResult.Rejected("Cash shift must be open and not closed before an inbound count")
                }
            }
            OperationType.CLOSE_CASH_SHIFT -> {
                if (
                    existingShift == null ||
                    existingShiftEvents.none { it.type == CashShiftEventType.OPENED } ||
                    existingShiftEvents.none { it.type == CashShiftEventType.COUNTED } ||
                    existingShiftEvents.any { it.type == CashShiftEventType.CLOSED }
                ) {
                    return InboundProcessResult.Rejected("Cash shift must be open, counted, and not already closed")
                }
            }
            else -> Unit
        }
        if ((payload.optJSONArray("settlements")?.length() ?: 0) != 0) {
            return InboundProcessResult.Rejected("Cash-shift lifecycle operations cannot carry settlements")
        }
        return null
    }
}
