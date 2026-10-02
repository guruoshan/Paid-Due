package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.serialization.DurableEnvelopeCodec

/** Durable intent for creating a register shift identity and its OPENED event. */
data class OpenRegisterShiftCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    val shiftId: CashShiftId,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis()
) : OperationCommand {

    init {
        require(businessId.isNotBlank()) { "businessId cannot be blank" }
        require(branchId.isNotBlank()) { "branchId cannot be blank" }
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank" }
        require(deviceId.isNotBlank()) { "deviceId cannot be blank" }
    }

    override val operationType = OperationType.OPEN_CASH_SHIFT
    override val subjectType = SubjectType.CASH_SHIFT
    override val subjectId = shiftId.value
    override val transactionId: String? = null
    override val commandSchemaVersion = RegisterShiftPayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() = CommandFingerprint.sha256(
            "OPEN_REGISTER_SHIFT|v=$commandSchemaVersion|" +
                "op=${operationId.value}|business=$businessId|branch=$branchId|" +
                "shift=${shiftId.value}|actor=$actorUserId|device=$deviceId|" +
                "occurredAt=$occurredAt"
        )

    override val intentPayload: String
        get() = RegisterShiftPayloadCodec.encode(this)
}

object RegisterShiftPayloadCodec {
    const val COMMAND_TYPE = "OPEN_REGISTER_SHIFT"
    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(command: OpenRegisterShiftCommand): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("commandType=$COMMAND_TYPE")
        appendLine("operationId=${DurableEnvelopeCodec.escape(command.operationId.value)}")
        appendLine("businessId=${DurableEnvelopeCodec.escape(command.businessId)}")
        appendLine("branchId=${DurableEnvelopeCodec.escape(command.branchId)}")
        appendLine("shiftId=${DurableEnvelopeCodec.escape(command.shiftId.value)}")
        appendLine("actorUserId=${DurableEnvelopeCodec.escape(command.actorUserId)}")
        appendLine("deviceId=${DurableEnvelopeCodec.escape(command.deviceId)}")
        appendLine("occurredAt=${command.occurredAt}")
    }

    fun decode(operation: OperationEntity): OpenRegisterShiftCommand {
        val values = parse(operation.intentPayload)
        require(values["v"] == CURRENT_SCHEMA_VERSION.toString()) {
            "Unsupported Register Shift command schema"
        }
        require(values["commandType"] == COMMAND_TYPE) {
            "Unexpected Register Shift command type"
        }

        val command = OpenRegisterShiftCommand(
            operationId = OperationId(required(values, "operationId")),
            businessId = required(values, "businessId"),
            branchId = required(values, "branchId"),
            shiftId = CashShiftId(required(values, "shiftId")),
            actorUserId = required(values, "actorUserId"),
            deviceId = required(values, "deviceId"),
            occurredAt = required(values, "occurredAt").toLongOrNull()
                ?: error("Register Shift occurredAt is invalid")
        )

        require(command.operationId.value == operation.id) {
            "Register Shift operation identity mismatch"
        }
        require(operation.operationType == OperationType.OPEN_CASH_SHIFT) {
            "Register Shift operation type mismatch"
        }
        require(operation.subjectType == SubjectType.CASH_SHIFT) {
            "Register Shift subject type mismatch"
        }
        require(operation.subjectId == command.shiftId.value) {
            "Register Shift subject identity mismatch"
        }
        require(operation.branchId == command.branchId) {
            "Register Shift branch identity mismatch"
        }
        require(operation.transactionId == null) {
            "Register Shift cannot reference a transaction"
        }
        require(operation.businessId == command.businessId) {
            "Register Shift business identity mismatch"
        }
        require(operation.actorUserId == command.actorUserId) {
            "Register Shift actor identity mismatch"
        }
        require(operation.deviceId == command.deviceId) {
            "Register Shift device identity mismatch"
        }
        require(operation.occurredAt == command.occurredAt) {
            "Register Shift timestamp identity mismatch"
        }
        require(operation.commandSchemaVersion == CURRENT_SCHEMA_VERSION) {
            "Register Shift operation schema mismatch"
        }
        require(operation.requestFingerprint == command.requestFingerprint) {
            "Register Shift request fingerprint mismatch"
        }

        return command
    }

    private fun parse(payload: String): Map<String, String> = payload
        .lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed Register Shift payload" }
            line.substring(0, separator) to
                DurableEnvelopeCodec.unescape(line.substring(separator + 1))
        }

    private fun required(values: Map<String, String>, key: String): String =
        values[key]?.takeIf { it.isNotBlank() }
            ?: error("Register Shift payload is missing $key")
}
