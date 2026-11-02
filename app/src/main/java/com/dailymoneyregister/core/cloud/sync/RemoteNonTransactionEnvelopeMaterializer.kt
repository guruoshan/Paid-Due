package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.json.JSONObject
import java.math.BigDecimal

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
                if (repaymentJson == null) {
                    return InboundProcessResult.Rejected("Missing credit repayment evidence")
                }
                if (
                    repaymentJson.optString("operation_id") != envelope.operationId ||
                    repaymentJson.optString("actor_user_id") != actorUserId ||
                    repaymentJson.optString("customer_id").isBlank() ||
                    repaymentJson.optLong("amount_minor", 0L) <= 0L ||
                    repaymentJson.optLong("occurred_at", Long.MIN_VALUE) != payload.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Credit repayment identity or amount is invalid")
                }
                val customerId = repaymentJson.optString("customer_id")
                val customer = database.customerDao().getCustomerById(customerId)
                    ?: evidence.optJSONObject("customer")?.let { null }
                val branch = database.businessBranchDao().getBranchById(branchId)
                    ?: return InboundProcessResult.Rejected("Referenced branch is missing locally")
                if (branch.businessId != sessionBusinessId ||
                    repaymentJson.optString("currency_code") != branch.currencyCode
                ) {
                    return InboundProcessResult.Rejected("Credit repayment business, branch, or currency mismatch")
                }
                if (customer != null && customer.businessId != sessionBusinessId) {
                    return InboundProcessResult.Rejected("Credit repayment customer belongs to another business")
                }
                val repaymentId = repaymentJson.optString("id")
                val repaymentAmount = repaymentJson.optLong("amount_minor", 0L)
                if (
                    repaymentId.isBlank() ||
                    payload.optString("subject_type") != SubjectType.REPAYMENT.name ||
                    payload.optString("subject_id") != repaymentId
                ) {
                    return InboundProcessResult.Rejected("Credit repayment subject does not match its repayment evidence")
                }
                val paymentCategory = runCatching {
                    PaymentCategory.valueOf(repaymentJson.getString("payment_category"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Credit repayment payment category is invalid")
                }
                val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                if (settlements.length() != 1) {
                    return InboundProcessResult.Rejected("Credit repayment requires exactly one funding settlement")
                }
                val settlement = settlements.getJSONObject(0)
                val paymentChannel = runCatching {
                    PaymentChannel.valueOf(settlement.getString("channel"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Credit repayment settlement channel is invalid")
                }
                val validPaymentPair = when (paymentCategory) {
                    PaymentCategory.CASH -> paymentChannel == PaymentChannel.CASH_TENDER
                    PaymentCategory.ONLINE -> paymentChannel == PaymentChannel.MANUAL_QR ||
                        paymentChannel == PaymentChannel.SOFTPOS
                    PaymentCategory.COUPON -> paymentChannel == PaymentChannel.COUPON_WALLET
                    else -> false
                }
                if (
                    !validPaymentPair ||
                    settlement.optString("operation_id") != envelope.operationId ||
                    settlement.optString("subject_type") != SubjectType.REPAYMENT.name ||
                    settlement.optString("subject_id") != repaymentId ||
                    settlement.optString("transaction_id").isNotBlank() ||
                    settlement.optString("currency_code") != repaymentJson.optString("currency_code") ||
                    settlement.optString("entry_purpose") != EntryPurpose.CREDIT_REPAYMENT_COLLECTION.name ||
                    settlement.optString("category") != paymentCategory.name ||
                    settlement.optString("actor_user_id") != actorUserId ||
                    settlement.optString("confirmation_type") != ConfirmationType.CASHIER_CONFIRMED.name ||
                    settlement.optLong("amount_signed_minor", 0L) != repaymentAmount ||
                    settlement.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Credit repayment settlement does not match the repayment record")
                }
                val ledgerEvents = evidence.optJSONArray("credit_ledger_events") ?: org.json.JSONArray()
                if (ledgerEvents.length() != 1) {
                    return InboundProcessResult.Rejected("Credit repayment requires exactly one matching Credit ledger event")
                }
                val ledgerEvent = ledgerEvents.getJSONObject(0)
                val expectedNegativeAmount = try {
                    Math.negateExact(repaymentAmount)
                } catch (_: ArithmeticException) {
                    return InboundProcessResult.Rejected("Credit repayment amount overflows the supported money range")
                }
                if (
                    ledgerEvent.optString("operation_id") != envelope.operationId ||
                    ledgerEvent.optString("type") != "REPAYMENT" ||
                    ledgerEvent.optString("repayment_id") != repaymentId ||
                    ledgerEvent.optString("transaction_id").isNotBlank() ||
                    ledgerEvent.optString("customer_id") != customerId ||
                    ledgerEvent.optString("currency_code") != repaymentJson.optString("currency_code") ||
                    ledgerEvent.optLong("amount_signed_minor", 0L) != expectedNegativeAmount ||
                    ledgerEvent.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Credit repayment is missing its exact negative ledger event")
                }
                val allocations = evidence.optJSONArray("credit_repayment_allocations") ?: org.json.JSONArray()
                if (allocations.length() == 0) {
                    return InboundProcessResult.Rejected("Credit repayment is missing FIFO allocation evidence")
                }
                var allocatedTotal = 0L
                val allocationIds = mutableSetOf<String>()
                val allocatedTransactions = mutableSetOf<String>()
                for (i in 0 until allocations.length()) {
                    val allocation = allocations.getJSONObject(i)
                    val allocationId = allocation.optString("id")
                    val originalTransactionId = allocation.optString("original_transaction_id")
                    val allocatedAmount = allocation.optLong("allocated_amount_minor", 0L)
                    if (
                        allocation.optString("repayment_id") != repaymentId ||
                        allocationId.isBlank() ||
                        originalTransactionId.isBlank() ||
                        allocatedAmount <= 0L ||
                        allocation.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE) ||
                        !allocationIds.add(allocationId) ||
                        !allocatedTransactions.add(originalTransactionId)
                    ) {
                        return InboundProcessResult.Rejected("Credit repayment FIFO allocation identity or amount is invalid")
                    }
                    allocatedTotal = Math.addExact(allocatedTotal, allocatedAmount)
                }
                if (allocatedTotal != repaymentAmount) {
                    return InboundProcessResult.Rejected("Credit repayment FIFO allocations do not equal the repayment amount")
                }
                val couponEvents = evidence.optJSONArray("coupon_ledger_events") ?: org.json.JSONArray()
                if (paymentCategory == PaymentCategory.COUPON) {
                    val wallets = evidence.optJSONArray("coupon_wallets") ?: org.json.JSONArray()
                    if (wallets.length() != 1 || couponEvents.length() != 1) {
                        return InboundProcessResult.Rejected(
                            "Coupon-funded Credit repayment requires one wallet and one redemption event"
                        )
                    }
                    val wallet = wallets.getJSONObject(0)
                    val couponEvent = couponEvents.getJSONObject(0)
                    if (
                        wallet.optString("id").isBlank() ||
                        wallet.optString("customer_id") != customerId ||
                        wallet.optString("currency_code") != repaymentJson.optString("currency_code") ||
                        !wallet.has("is_active") || wallet.isNull("is_active") || !wallet.optBoolean("is_active", false) ||
                        couponEvent.optString("id").isBlank() ||
                        couponEvent.optString("operation_id") != envelope.operationId ||
                        couponEvent.optString("wallet_id") != wallet.optString("id") ||
                        couponEvent.optString("type") != "REDEMPTION" ||
                        couponEvent.optLong("amount_signed_minor", 0L) != expectedNegativeAmount ||
                        couponEvent.optString("currency_code") != repaymentJson.optString("currency_code") ||
                        couponEvent.optString("transaction_id").isNotBlank() ||
                        couponEvent.optString("authorized_user_id").isBlank() ||
                        couponEvent.optString("authorized_user_name_snapshot").isBlank() ||
                        couponEvent.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
                    ) {
                        return InboundProcessResult.Rejected(
                            "Coupon-funded Credit repayment is missing its exact wallet redemption evidence"
                        )
                    }
                } else if (couponEvents.length() != 0) {
                    return InboundProcessResult.Rejected(
                        "Cash/Online Credit repayment cannot carry Coupon redemption evidence"
                    )
                }
            }
            OperationType.OPEN_CASH_SHIFT,
            OperationType.COUNT_CASH_SHIFT,
            OperationType.CLOSE_CASH_SHIFT -> {
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
            }
            OperationType.RECORD_EXPENSE_CORRECTION,
            OperationType.RECORD_EXPENSE_VOID -> {
                if (expenseSnapshot == null || expenseEvents == null || expenseEvents.length() == 0) {
                    return InboundProcessResult.Rejected("Missing linked expense event evidence")
                }
                if (
                    expenseSnapshot.optString("business_id") != sessionBusinessId ||
                    expenseSnapshot.optString("branch_id") != branchId
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle evidence belongs to another tenant or branch")
                }
                val operationEvents = (0 until expenseEvents.length())
                    .map { expenseEvents.getJSONObject(it) }
                    .filter { it.optString("operation_id") == envelope.operationId }
                if (operationEvents.size != 1) {
                    return InboundProcessResult.Rejected("Expense lifecycle operation must have exactly one event")
                }
                val expenseId = expenseSnapshot.optString("id")
                if (
                    expenseId.isBlank() ||
                    payload.optString("subject_type") != SubjectType.EXPENSE.name ||
                    payload.optString("subject_id") != expenseId
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle subject metadata mismatch")
                }
                val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
                if (settlements.length() != 1) {
                    return InboundProcessResult.Rejected(
                        "Expense lifecycle operation requires exactly one signed settlement"
                    )
                }
                val expectedCategory = runCatching {
                    PaymentCategory.valueOf(expenseSnapshot.optString("payment_category"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Expense lifecycle payment category is invalid")
                }
                val row = settlements.getJSONObject(0)
                val rowChannel = runCatching {
                    PaymentChannel.valueOf(row.optString("channel"))
                }.getOrElse {
                    return InboundProcessResult.Rejected("Expense lifecycle settlement channel is invalid")
                }
                val validChannel = when (expectedCategory) {
                    PaymentCategory.CASH -> rowChannel == PaymentChannel.CASH_TENDER
                    PaymentCategory.ONLINE -> rowChannel == PaymentChannel.MANUAL_QR ||
                        rowChannel == PaymentChannel.SOFTPOS
                    else -> false
                }
                val expectedPurpose = if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    EntryPurpose.VOID_REVERSAL.name
                } else {
                    EntryPurpose.EXPENSE_CORRECTION.name
                }
                if (
                    !validChannel ||
                    row.optString("operation_id") != envelope.operationId ||
                    row.optString("subject_type") != SubjectType.EXPENSE.name ||
                    row.optString("subject_id") != expenseId ||
                    row.optString("transaction_id").isNotBlank() ||
                    row.optString("currency_code") != expenseSnapshot.optString("currency_code") ||
                    row.optString("actor_user_id") != actorUserId ||
                    row.optString("entry_purpose") != expectedPurpose ||
                    row.optString("category") != expectedCategory.name ||
                    row.optString("confirmation_type") != ConfirmationType.CASHIER_CONFIRMED.name ||
                    row.optLong("occurred_at", Long.MIN_VALUE) != envelope.payloadJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected("Expense lifecycle settlement identity mismatch")
                }
                val total = row.getLong("amount_signed_minor")
                val event = operationEvents.single()
                val expectedEventType = if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    "VOIDED"
                } else {
                    "CORRECTED"
                }
                if (
                    event.optString("id").isBlank() ||
                    event.optString("expense_id") != expenseId ||
                    event.optString("operation_id") != envelope.operationId ||
                    event.optString("actor_user_id") != actorUserId ||
                    event.optString("event_type") != expectedEventType ||
                    event.optLong("occurred_at", Long.MIN_VALUE) != envelope.payloadJson.optLong("occurred_at", Long.MAX_VALUE)
                ) {
                    return InboundProcessResult.Rejected(
                        "Expense lifecycle event must match the exact expense, operation, actor, and event type"
                    )
                }
                if (operationType == OperationType.RECORD_EXPENSE_VOID) {
                    val amountBeingVoided = event.optLong("previous_amount_minor", 0L)
                    if (amountBeingVoided <= 0L || total != amountBeingVoided) {
                        return InboundProcessResult.Rejected("Expense void settlement does not reverse the effective expense outflow")
                    }
                } else {
                    if (
                        !event.has("previous_amount_minor") || event.isNull("previous_amount_minor") ||
                        !event.has("new_amount_minor") || event.isNull("new_amount_minor")
                    ) {
                        return InboundProcessResult.Rejected("Expense correction is missing its before/after amount evidence")
                    }
                    val previousAmount = event.getLong("previous_amount_minor")
                    val newAmount = event.getLong("new_amount_minor")
                    if (previousAmount <= 0L || newAmount <= 0L || previousAmount == newAmount) {
                        return InboundProcessResult.Rejected("Expense correction before/after amounts are invalid")
                    }
                    val expectedDelta = try {
                        Math.subtractExact(previousAmount, newAmount)
                    } catch (_: ArithmeticException) {
                        return InboundProcessResult.Rejected(
                            "Expense correction delta overflows the supported money range"
                        )
                    }
                    if (total != expectedDelta) {
                        return InboundProcessResult.Rejected("Expense correction delta does not match signed settlements")
                    }
                }
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
