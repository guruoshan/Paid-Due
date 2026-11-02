package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import org.json.JSONObject

/** Routes non-transaction inbound operations through their atomic evidence writer. */
internal object RemoteNonTransactionEnvelopeMaterializer {

    suspend fun materialize(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        branchId: String,
        sessionBusinessId: String,
        actorUserId: String
    ): InboundProcessResult {
        val payload = envelope.payloadJson
        val operationType = runCatching { OperationType.valueOf(envelope.operationType) }
            .getOrElse { return InboundProcessResult.UnsupportedYet(envelope.operationType) }
        val evidence = payload.optJSONObject("financial_evidence") ?: JSONObject()
        val repaymentJson = evidence.optJSONObject("credit_repayment")
        val shiftJson = evidence.optJSONObject("cash_shift")
        val shiftEventJson = evidence.optJSONObject("cash_shift_event")
        val expenseSnapshot = evidence.optJSONObject("expense_snapshot")
        val expenseEvents = evidence.optJSONArray("expense_events")

        when (operationType) {
            OperationType.RECORD_CREDIT_REPAYMENT -> {
                RemoteCreditRepaymentValidator.validate(
                    database, envelope, branchId, sessionBusinessId, actorUserId, repaymentJson, evidence
                )?.let { return it }
            }
            OperationType.OPEN_CASH_SHIFT,
            OperationType.COUNT_CASH_SHIFT,
            OperationType.CLOSE_CASH_SHIFT -> {
                RemoteCashShiftLifecycleValidator.validate(
                    database, envelope, operationType, branchId, actorUserId, shiftJson, shiftEventJson
                )?.let { return it }
            }
            OperationType.RECORD_EXPENSE_CORRECTION,
            OperationType.RECORD_EXPENSE_VOID -> {
                RemoteExpenseLifecycleValidator.validate(
                    envelope, operationType, branchId, sessionBusinessId, actorUserId, expenseSnapshot, expenseEvents
                )?.let { return it }
            }
            else -> return InboundProcessResult.UnsupportedYet(envelope.operationType)
        }

        return try {
            database.withTransaction {
                val operationDao = database.operationDao()
                val subjectType = payload.optString("subject_type").ifBlank {
                    when (operationType) {
                        OperationType.RECORD_CREDIT_REPAYMENT -> SubjectType.REPAYMENT.name
                        OperationType.OPEN_CASH_SHIFT,
                        OperationType.COUNT_CASH_SHIFT,
                        OperationType.CLOSE_CASH_SHIFT -> SubjectType.CASH_SHIFT.name
                        else -> SubjectType.EXPENSE.name
                    }
                }.let { SubjectType.valueOf(it) }
                val subjectId = payload.optString("subject_id").ifBlank {
                    repaymentJson?.optString("id")
                        ?: shiftJson?.optString("id")
                        ?: expenseSnapshot?.optString("id")
                        ?: envelope.operationId
                }
                val fingerprint = payload.optString("request_fingerprint")
                val occurredAt = java.time.Instant.parse(envelope.occurredAtIso).toEpochMilli()
                val isCashShiftLifecycle = operationType == OperationType.OPEN_CASH_SHIFT ||
                    operationType == OperationType.COUNT_CASH_SHIFT ||
                    operationType == OperationType.CLOSE_CASH_SHIFT
                val cashShiftId = if (isCashShiftLifecycle) {
                    null
                } else {
                    payload.optString("cash_shift_id").ifBlank { null }
                }
                val operation = OperationEntity(
                    id = envelope.operationId,
                    businessId = sessionBusinessId,
                    branchId = branchId,
                    operationType = operationType,
                    subjectType = subjectType,
                    subjectId = subjectId,
                    transactionId = null,
                    cashShiftId = cashShiftId,
                    actorUserId = actorUserId,
                    deviceId = payload.optString("device_id").ifBlank { "remote" },
                    occurredAt = occurredAt,
                    requestFingerprint = fingerprint,
                    commandSchemaVersion = payload.optInt("command_schema_version", 1),
                    intentPayload = payload.optString("intent_payload", "{}"),
                    status = OperationStatus.SUCCEEDED,
                    resultReferenceType = subjectType.name,
                    resultReferenceId = subjectId
                )
                val admission = InboundOperationCompletion.ensure(database, operation)
                if (admission == InboundOperationAdmission.ALREADY_SUCCEEDED) {
                    return@withTransaction InboundProcessResult.Skipped
                }

                if (shiftJson != null && shiftEventJson != null) {
                    val shiftId = shiftJson.getString("id")
                    val existingShift = database.cashShiftDao().getShiftById(shiftId)
                    if (operationType != OperationType.OPEN_CASH_SHIFT && existingShift == null) {
                        error("Count/close cannot materialize a cash shift that is not already open locally")
                    }
                    if (operationType == OperationType.OPEN_CASH_SHIFT && existingShift == null) {
                        val latestShift = database.cashShiftDao().getLatestShiftForCashier(
                            branchId = branchId,
                            userId = shiftJson.getString("user_id")
                        )
                        if (latestShift != null) {
                            val latestEvents = database.cashShiftDao().getEventsForShift(latestShift.id)
                            check(latestEvents.lastOrNull()?.type == CashShiftEventType.CLOSED) {
                                "A Register Shift is already open for this cashier"
                            }
                        }
                    }
                    val priorEvents = database.cashShiftDao().getEventsForShift(shiftId)
                        .filter { it.operationId != envelope.operationId }
                    val existingShiftEvent = database.cashShiftDao().getEventByOperationId(envelope.operationId)
                    if (existingShiftEvent != null && (
                            existingShiftEvent.id != shiftEventJson.optString("id") ||
                                existingShiftEvent.shiftId != shiftId ||
                                existingShiftEvent.type.name != shiftEventJson.optString("type") ||
                                existingShiftEvent.amountMinor != shiftEventJson.optLong("amount_minor") ||
                                existingShiftEvent.occurredAt != payload.optLong("occurred_at", Long.MAX_VALUE) ||
                                existingShiftEvent.actorUserId != actorUserId
                        )
                    ) {
                        error("Existing cash-shift event conflicts with the inbound lifecycle intent")
                    }
                    val hasOpened = priorEvents.any { it.type == CashShiftEventType.OPENED }
                    val hasCounted = priorEvents.any { it.type == CashShiftEventType.COUNTED }
                    val hasClosed = priorEvents.any { it.type == CashShiftEventType.CLOSED }
                    when (operationType) {
                        OperationType.OPEN_CASH_SHIFT -> check(!hasOpened && !hasClosed) {
                            "Cash shift already has a conflicting lifecycle history"
                        }
                        OperationType.COUNT_CASH_SHIFT -> check(hasOpened && !hasClosed) {
                            "Cash shift must be open and not closed before counting"
                        }
                        OperationType.CLOSE_CASH_SHIFT -> check(hasOpened && hasCounted && !hasClosed) {
                            "Cash shift must be opened and counted before closing"
                        }
                        else -> Unit
                    }
                }

                val settlementsArray = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                val settlementRows = (0 until settlementsArray.length()).map { i ->
                    val row = settlementsArray.getJSONObject(i)
                    SettlementEntryEntity(
                        id = row.getString("id"),
                        operationId = row.getString("operation_id"),
                        subjectType = SubjectType.valueOf(row.getString("subject_type")),
                        subjectId = row.getString("subject_id"),
                        transactionId = row.optString("transaction_id").ifBlank { null },
                        entryPurpose = EntryPurpose.valueOf(row.getString("entry_purpose")),
                        category = PaymentCategory.valueOf(row.getString("category")),
                        channel = PaymentChannel.valueOf(row.getString("channel")),
                        amountSignedMinor = row.getLong("amount_signed_minor"),
                        currencyCode = row.getString("currency_code"),
                        confirmationType = ConfirmationType.valueOf(row.getString("confirmation_type")),
                        qrProfileIdSnapshot = row.optString("qr_profile_id_snapshot").ifBlank { null },
                        occurredAt = row.optLong("occurred_at", occurredAt),
                        actorUserId = row.optString("actor_user_id").ifBlank { actorUserId },
                        providerRef = row.optString("provider_ref").ifBlank { null }
                    )
                }
                val existingSettlements = database.transactionDao()
                    .getSettlementsForOperation(envelope.operationId)
                if (existingSettlements.isEmpty() && settlementRows.isNotEmpty()) {
                    database.transactionDao().insertSettlements(settlementRows)
                } else if (settlementRows.isNotEmpty()) {
                    check(existingSettlements.toSet() == settlementRows.toSet()) {
                        "Inbound settlement evidence conflicts with local history"
                    }
                }

                RemoteEnvelopeEvidenceApplier.apply(
                    database = database,
                    evidence = evidence,
                    operationId = envelope.operationId,
                    businessId = sessionBusinessId,
                    branchId = branchId,
                    actorUserId = actorUserId,
                    transactionId = null
                )

                if (admission == InboundOperationAdmission.PENDING) {
                    InboundOperationCompletion.completePending(database, operation)
                } else {
                    check(operationDao.isOperationSucceeded(envelope.operationId)) {
                        "Inbound non-transaction operation was not persisted as succeeded"
                    }
                }
                InboundProcessResult.Applied
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            InboundProcessResult.Rejected(
                "Unable to materialize ${envelope.operationType}: ${error.message ?: "integrity validation failed"}"
            )
        }
    }
}
