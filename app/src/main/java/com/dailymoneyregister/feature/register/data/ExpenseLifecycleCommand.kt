package com.dailymoneyregister.feature.register.data

import android.util.Base64
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

/**
 * Durable intent for append-only expense correction/void events.
 * Financial values are derived from the persisted expense snapshot at execution
 * time and are never accepted from the presentation layer as "previous" values.
 */
data class ExpenseLifecycleCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long,
    val expenseId: String,
    val action: Action,
    val correctedAmountMinor: Long? = null,
    val reason: String
) : OperationCommand {
    enum class Action { CORRECT, VOID }

    init {
        require(businessId.isNotBlank() && branchId.isNotBlank() && actorUserId.isNotBlank())
        require(expenseId.isNotBlank() && deviceId.isNotBlank())
        require(reason.isNotBlank()) { "A reason is required for an expense correction or void." }
        if (action == Action.CORRECT) {
            require(correctedAmountMinor != null && correctedAmountMinor > 0L) {
                "Corrected expense amount must be positive."
            }
        } else {
            require(correctedAmountMinor == null) { "Expense void cannot carry a corrected amount." }
        }
    }

    override val operationType: OperationType
        get() = if (action == Action.CORRECT) OperationType.RECORD_EXPENSE_CORRECTION else OperationType.RECORD_EXPENSE_VOID
    override val subjectType = SubjectType.EXPENSE
    override val subjectId: String get() = expenseId
    override val transactionId: String? = null
    override val commandSchemaVersion = 1
    override val requestFingerprint: String
        get() = CommandFingerprint.sha256(
            "expense-lifecycle-v1|biz:$businessId|branch:$branchId|actor:$actorUserId|" +
                "expense:$expenseId|action:$action|amount:${correctedAmountMinor ?: ""}|" +
                "reason:$reason|device:$deviceId|time:$occurredAt"
        )
    override val intentPayload: String
        get() = ExpenseLifecyclePayloadCodec.encode(this)
}

object ExpenseLifecyclePayloadCodec {
    private const val PREFIX = "expense-lifecycle-v1"
    fun encode(command: ExpenseLifecycleCommand): String =
        listOf(
            PREFIX, command.operationId.value, command.businessId, command.branchId,
            command.actorUserId, command.deviceId, command.occurredAt.toString(),
            command.expenseId, command.action.name, command.correctedAmountMinor?.toString().orEmpty(), command.reason
        ).joinToString("|") { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }

    fun decode(payload: String): ExpenseLifecycleCommand {
        val parts = payload.split('|').map { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
        require(parts.size == 11 && parts[0] == PREFIX) { "Invalid expense lifecycle durable payload." }
        return ExpenseLifecycleCommand(
            operationId = OperationId(parts[1]), businessId = parts[2], branchId = parts[3],
            actorUserId = parts[4], deviceId = parts[5], occurredAt = parts[6].toLong(),
            expenseId = parts[7], action = ExpenseLifecycleCommand.Action.valueOf(parts[8]),
            correctedAmountMinor = parts[9].takeIf { it.isNotBlank() }?.toLong(), reason = parts[10]
        )
    }
}
