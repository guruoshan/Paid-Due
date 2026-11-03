package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.feature.customers.data.CustomerIdentity
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import org.json.JSONObject

/**
 * Owns the inbound expense-create boundary so the universal envelope dispatcher
 * does not accumulate another financial workflow.
 */
internal object RemoteExpenseEnvelopeMaterializer {

    suspend fun materialize(database: DmrDatabase, jsonPayload: JSONObject): InboundProcessResult = try {
        val operationId = jsonPayload.getString("operation_id")
        val businessId = jsonPayload.getString("business_id")
        val branchId = jsonPayload.getString("branch_id")
        val actorUserId = jsonPayload.getString("actor_user_id")
        val deviceId = jsonPayload.getString("device_id")
        val occurredAt = jsonPayload.getLong("occurred_at")
        val schemaVersion = jsonPayload.optInt("command_schema_version", 1)
        val requestFingerprint = jsonPayload.getString("request_fingerprint")
        val intentPayload = jsonPayload.optString("intent_payload", "{}")
        val expenseJson = jsonPayload.getJSONObject("expense")
        val expenseId = expenseJson.getString("id")
        val cashShiftId = jsonPayload.optString("cash_shift_id").trim()
            .takeIf { it.isNotBlank() && it != "null" }
            ?: error("Inbound expense is missing its required cash shift ID")
        val expenseShiftId = expenseJson.optString("cash_shift_id").trim()
        require(expenseShiftId == cashShiftId) {
            "Expense snapshot cash shift ID does not match the envelope"
        }
        val expenseCategory = runCatching {
            ExpenseCategory.valueOf(expenseJson.getString("category"))
        }.getOrElse {
            error("Inbound expense category is unsupported")
        }

        require(operationId.isNotBlank() && expenseId.isNotBlank())
        require(deviceId.isNotBlank() && requestFingerprint.isNotBlank() && schemaVersion > 0)
        require(jsonPayload.optString("operation_type") == OperationType.RECORD_EXPENSE.name)
        require(expenseJson.getString("operation_id") == operationId)
        require(expenseJson.getString("business_id") == businessId)
        require(expenseJson.getString("branch_id") == branchId)
        require(expenseJson.getString("actor_user_id") == actorUserId && jsonPayload.getString("actor_user_id") == actorUserId)
        require(expenseJson.optString("cash_shift_id").ifBlank { null } == cashShiftId)
        require(jsonPayload.optString("subject_type") == SubjectType.EXPENSE.name)
        require(jsonPayload.optString("subject_id") == expenseId)
        require(!jsonPayload.has("transaction") || jsonPayload.isNull("transaction"))
        require((jsonPayload.optJSONArray("items")?.length() ?: 0) == 0)

        val expense = ExpenseEntity(
            id = expenseId,
            operationId = operationId,
            businessId = businessId,
            branchId = branchId,
            cashShiftId = cashShiftId,
            actorUserId = actorUserId,
            payeeId = expenseJson.optString("payee_id").ifBlank { null },
            payeeNameSnapshot = expenseJson.getString("payee_name_snapshot"),
            payeePhoneSnapshot = expenseJson.optString("payee_phone_snapshot").ifBlank { null },
            payeeAccountSnapshot = expenseJson.optString("payee_account_snapshot").ifBlank { null },
            category = expenseCategory.name,
            amountMinor = expenseJson.getLong("amount_minor"),
            paymentCategory = expenseJson.getString("payment_category"),
            currencyCode = expenseJson.getString("currency_code"),
            reference = expenseJson.optString("reference").ifBlank { null },
            note = expenseJson.optString("note").ifBlank { null },
            recurrenceId = expenseJson.optString("recurrence_id").ifBlank { null },
            occurredAt = expenseJson.getLong("occurred_at"),
            createdAt = expenseJson.getLong("created_at")
        )
        require(expense.amountMinor > 0L && expense.occurredAt == occurredAt)

        val category = PaymentCategory.valueOf(expense.paymentCategory)
        require(category == PaymentCategory.CASH || category == PaymentCategory.ONLINE)
        val settlementsJson = jsonPayload.getJSONArray("settlements")
        require(settlementsJson.length() == 1) {
            "Expense must have exactly one authoritative outflow settlement"
        }
        val settlementJson = settlementsJson.getJSONObject(0)
        val settlement = SettlementEntryEntity(
            id = settlementJson.getString("id"),
            operationId = settlementJson.getString("operation_id"),
            subjectType = SubjectType.valueOf(settlementJson.getString("subject_type")),
            subjectId = settlementJson.getString("subject_id"),
            transactionId = settlementJson.optString("transaction_id").ifBlank { null },
            entryPurpose = EntryPurpose.valueOf(settlementJson.getString("entry_purpose")),
            category = PaymentCategory.valueOf(settlementJson.getString("category")),
            channel = PaymentChannel.valueOf(settlementJson.getString("channel")),
            amountSignedMinor = settlementJson.getLong("amount_signed_minor"),
            currencyCode = settlementJson.getString("currency_code"),
            confirmationType = ConfirmationType.valueOf(settlementJson.getString("confirmation_type")),
            qrProfileIdSnapshot = settlementJson.optString("qr_profile_id_snapshot").ifBlank { null },
            occurredAt = settlementJson.getLong("occurred_at"),
            actorUserId = settlementJson.getString("actor_user_id"),
            providerRef = settlementJson.optString("provider_ref").ifBlank { null }
        )
        val validChannel = when (category) {
            PaymentCategory.CASH -> settlement.channel == PaymentChannel.CASH_TENDER
            PaymentCategory.ONLINE -> settlement.channel == PaymentChannel.MANUAL_QR ||
                settlement.channel == PaymentChannel.SOFTPOS
            else -> false
        }
        require(
            settlement.id == "$operationId:expense-outflow" &&
                settlement.operationId == operationId &&
                settlement.subjectType == SubjectType.EXPENSE &&
                settlement.subjectId == expenseId &&
                settlement.transactionId == null &&
                settlement.entryPurpose == EntryPurpose.EXPENSE_OUTFLOW &&
                settlement.category == category &&
                validChannel &&
                settlement.amountSignedMinor == Math.negateExact(expense.amountMinor) &&
                settlement.currencyCode == expense.currencyCode &&
                settlement.confirmationType == ConfirmationType.CASHIER_CONFIRMED &&
                settlement.actorUserId == actorUserId &&
                settlement.occurredAt == occurredAt
        ) {
            "Expense outflow settlement does not match the expense snapshot and envelope"
        }

        val operation = OperationEntity(
            id = operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = OperationType.RECORD_EXPENSE,
            subjectType = SubjectType.EXPENSE,
            subjectId = expenseId,
            transactionId = null,
            cashShiftId = cashShiftId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            occurredAt = occurredAt,
            requestFingerprint = requestFingerprint,
            commandSchemaVersion = schemaVersion,
            intentPayload = intentPayload,
            status = OperationStatus.SUCCEEDED,
            resultReferenceType = "EXPENSE",
            resultReferenceId = expenseId
        )

        database.withTransaction {
            val cashShift = database.cashShiftDao().getShiftById(cashShiftId)
                ?: error("Expense references a missing cash shift")
            require(cashShift.branchId == branchId) {
                "Expense cash shift belongs to another branch"
            }
            val shiftBranch = database.businessBranchDao().getBranchById(cashShift.branchId)
                ?: error("Expense cash shift references a missing branch")
            require(shiftBranch.businessId == businessId) {
                "Expense cash shift belongs to another business"
            }
            require(cashShift.userId == actorUserId) {
                "Expense cash shift belongs to another actor"
            }
            require(
                database.cashShiftDao().getEventsForShift(cashShift.id)
                    .none { it.type == CashShiftEventType.CLOSED }
            ) { "Expense cash shift is closed" }
            val evidenceShiftId = jsonPayload.optJSONObject("financial_evidence")?.optJSONObject("cash_shift_event")?.optString("shift_id").orEmpty()
            require(evidenceShiftId.isEmpty() || evidenceShiftId == cashShiftId) { "Cash-shift evidence references a shift other than the expense's shift" }
            val evidenceRowShiftId = jsonPayload.optJSONObject("financial_evidence")?.optJSONObject("cash_shift")?.optString("id").orEmpty()
            require(evidenceRowShiftId.isEmpty() || evidenceRowShiftId == cashShiftId) { "Cash-shift row evidence references a shift other than the expense's shift" }

            CustomerIdentityReconciler.reconcileEvidenceCustomers(
                database, businessId,
                jsonPayload.optJSONObject("financial_evidence") ?: JSONObject()
            )
            val resolvedIncomingPayeeId = expense.payeeId?.let { incomingPayeeId ->
                CustomerIdentityReconciler.resolveLocalCustomerIdOrSelf(
                    database = database,
                    businessId = businessId,
                    incomingCustomerId = incomingPayeeId
                )
            }
            val resolvedPayee = expense.payeePhoneSnapshot
                ?.takeIf { it.isNotBlank() }
                ?.let { rawPhone ->
                    CustomerIdentity.resolveOrCreate(
                        customerDao = database.customerDao(),
                        businessId = businessId,
                        rawPhone = rawPhone,
                        rawName = expense.payeeNameSnapshot,
                        now = expense.occurredAt
                    ).customer
                }
            val resolvedIncomingPayee = resolvedIncomingPayeeId?.let { customerId ->
                database.customerDao().getCustomerById(customerId)
            }
            require(
                resolvedIncomingPayee == null ||
                    resolvedPayee == null ||
                    resolvedIncomingPayee.id == resolvedPayee.id
            ) {
                "Conflicting payee identity evidence"
            }
            val materializedExpense = expense.copy(
                payeeId = resolvedPayee?.id ?: resolvedIncomingPayeeId,
                payeePhoneSnapshot = resolvedPayee?.canonicalPhone ?: expense.payeePhoneSnapshot
            )
            val admission = InboundOperationCompletion.ensure(database, operation)
            if (admission == InboundOperationAdmission.ALREADY_SUCCEEDED) {
                return@withTransaction
            }

            val expenseDao = database.expenseDao()
            val existingExpense = expenseDao.getExpenseById(expenseId)
            if (existingExpense == null) {
                expenseDao.insertExpense(materializedExpense)
            } else {
                check(existingExpense == materializedExpense) {
                    "Inbound expense snapshot conflicts with existing immutable expense"
                }
            }

            val transactionDao = database.transactionDao()
            val existingSettlements = transactionDao.getSettlementsForOperation(operationId)
            if (existingSettlements.isEmpty()) {
                transactionDao.insertSettlements(listOf(settlement))
            } else {
                check(existingSettlements == listOf(settlement)) {
                    "Inbound expense settlements conflict with local financial history"
                }
            }

            RemoteEnvelopeEvidenceApplier.apply(
                database = database,
                evidence = jsonPayload.optJSONObject("financial_evidence") ?: JSONObject(),
                operationId = operationId,
                businessId = businessId,
                branchId = branchId,
                actorUserId = actorUserId,
                transactionId = null
            )

            if (admission == InboundOperationAdmission.PENDING) {
                InboundOperationCompletion.completePending(database, operation)
            } else {
                check(database.operationDao().isOperationSucceeded(operationId)) {
                    "Inbound expense operation was not persisted as succeeded"
                }
            }
        }
        InboundProcessResult.Applied
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        InboundProcessResult.Rejected(
            "Expense envelope could not be materialized: ${error.message ?: "integrity validation failed"}"
        )
    }


}
