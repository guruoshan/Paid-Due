package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.serialization.DurableEnvelopeCodec

/**
 * Durable intent for creating a cashier Register Shift and its OPENED event.
 */
data class OpenRegisterShiftCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    val shiftId: CashShiftId,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis(),
    val reportSnapshot: RegisterShiftCloseReportSnapshot? = null
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

/**
 * Durable cash-count evidence for an existing OPEN cashier shift.
 *
 * countedCashMinor is reconciliation evidence only. It does not rewrite
 * sales, settlements, refunds, expenses, Coupon, Credit, or any other
 * authoritative financial transaction.
 */
data class CountRegisterShiftCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    val shiftId: CashShiftId,
    val countedCashMinor: Long,
    override val actorUserId: String,
    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis()
) : OperationCommand {

    init {
        require(businessId.isNotBlank()) { "businessId cannot be blank" }
        require(branchId.isNotBlank()) { "branchId cannot be blank" }
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank" }
        require(deviceId.isNotBlank()) { "deviceId cannot be blank" }
        require(countedCashMinor >= 0L) { "countedCashMinor cannot be negative" }
    }

    override val operationType = OperationType.COUNT_CASH_SHIFT
    override val subjectType = SubjectType.CASH_SHIFT
    override val subjectId = shiftId.value
    override val transactionId: String? = null
    override val commandSchemaVersion = RegisterShiftLifecyclePayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() = CommandFingerprint.sha256(
            "COUNT_REGISTER_SHIFT|v=$commandSchemaVersion|" +
                "op=${operationId.value}|business=$businessId|branch=$branchId|" +
                "shift=${shiftId.value}|counted=$countedCashMinor|" +
                "actor=$actorUserId|device=$deviceId|occurredAt=$occurredAt"
        )

    override val intentPayload: String
        get() = RegisterShiftLifecyclePayloadCodec.encodeCount(this)
}

/**
 * Durable immutable close intent.
 *
 * A close event changes only the lifecycle state of the shift.
 * It never edits, transfers, deletes, or recreates financial activity.
 */
data class CloseRegisterShiftCommand(
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

    override val operationType = OperationType.CLOSE_CASH_SHIFT
    override val subjectType = SubjectType.CASH_SHIFT
    override val subjectId = shiftId.value
    override val transactionId: String? = null
    override val commandSchemaVersion = RegisterShiftLifecyclePayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() = CommandFingerprint.sha256(
            "CLOSE_REGISTER_SHIFT|v=$commandSchemaVersion|" +
                "op=${operationId.value}|business=$businessId|branch=$branchId|" +
                "shift=${shiftId.value}|actor=$actorUserId|device=$deviceId|" +
                "occurredAt=$occurredAt"
        )

    override val intentPayload: String
        get() = RegisterShiftLifecyclePayloadCodec.encodeClose(this)
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

        validateCommon(operation, command)

        require(operation.operationType == OperationType.OPEN_CASH_SHIFT) {
            "Register Shift operation type mismatch"
        }

        return command
    }

    private fun validateCommon(
        operation: OperationEntity,
        command: OpenRegisterShiftCommand
    ) {
        require(command.operationId.value == operation.id) {
            "Register Shift operation identity mismatch"
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
    }

    private fun parse(payload: String): Map<String, String> =
        RegisterShiftCodecSupport.parse(payload)

    private fun required(
        values: Map<String, String>,
        key: String
    ): String = RegisterShiftCodecSupport.required(values, key)
}

object RegisterShiftLifecyclePayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    private const val COUNT_COMMAND_TYPE = "COUNT_REGISTER_SHIFT"
    private const val CLOSE_COMMAND_TYPE = "CLOSE_REGISTER_SHIFT"

    fun encodeCount(command: CountRegisterShiftCommand): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("commandType=$COUNT_COMMAND_TYPE")
        appendLine("operationId=${DurableEnvelopeCodec.escape(command.operationId.value)}")
        appendLine("businessId=${DurableEnvelopeCodec.escape(command.businessId)}")
        appendLine("branchId=${DurableEnvelopeCodec.escape(command.branchId)}")
        appendLine("shiftId=${DurableEnvelopeCodec.escape(command.shiftId.value)}")
        appendLine("countedCashMinor=${command.countedCashMinor}")
        appendLine("actorUserId=${DurableEnvelopeCodec.escape(command.actorUserId)}")
        appendLine("deviceId=${DurableEnvelopeCodec.escape(command.deviceId)}")
        appendLine("occurredAt=${command.occurredAt}")
    }

    fun encodeClose(command: CloseRegisterShiftCommand): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("commandType=$CLOSE_COMMAND_TYPE")
        appendLine("operationId=${DurableEnvelopeCodec.escape(command.operationId.value)}")
        appendLine("businessId=${DurableEnvelopeCodec.escape(command.businessId)}")
        appendLine("branchId=${DurableEnvelopeCodec.escape(command.branchId)}")
        appendLine("shiftId=${DurableEnvelopeCodec.escape(command.shiftId.value)}")
        appendLine("actorUserId=${DurableEnvelopeCodec.escape(command.actorUserId)}")
        appendLine("deviceId=${DurableEnvelopeCodec.escape(command.deviceId)}")
        appendLine("occurredAt=${command.occurredAt}")
        command.reportSnapshot?.let {
            appendLine("reportSnapshotJson=${DurableEnvelopeCodec.escape(it.toJsonString())}")
        }
    }

    fun decodeCount(operation: OperationEntity): CountRegisterShiftCommand {
        val values = RegisterShiftCodecSupport.parse(operation.intentPayload)

        require(values["v"] == CURRENT_SCHEMA_VERSION.toString()) {
            "Unsupported Register Shift count schema"
        }
        require(values["commandType"] == COUNT_COMMAND_TYPE) {
            "Unexpected Register Shift count command type"
        }

        val command = CountRegisterShiftCommand(
            operationId = OperationId(RegisterShiftCodecSupport.required(values, "operationId")),
            businessId = RegisterShiftCodecSupport.required(values, "businessId"),
            branchId = RegisterShiftCodecSupport.required(values, "branchId"),
            shiftId = CashShiftId(RegisterShiftCodecSupport.required(values, "shiftId")),
            countedCashMinor =
                RegisterShiftCodecSupport.required(values, "countedCashMinor").toLongOrNull()
                    ?: error("Register Shift counted cash is invalid"),
            actorUserId = RegisterShiftCodecSupport.required(values, "actorUserId"),
            deviceId = RegisterShiftCodecSupport.required(values, "deviceId"),
            occurredAt =
                RegisterShiftCodecSupport.required(values, "occurredAt").toLongOrNull()
                    ?: error("Register Shift count occurredAt is invalid")
        )

        validateLifecycleOperation(
            operation = operation,
            operationId = command.operationId,
            businessId = command.businessId,
            branchId = command.branchId,
            shiftId = command.shiftId,
            actorUserId = command.actorUserId,
            deviceId = command.deviceId,
            occurredAt = command.occurredAt,
            operationType = OperationType.COUNT_CASH_SHIFT,
            fingerprint = command.requestFingerprint
        )

        return command
    }

    fun decodeClose(operation: OperationEntity): CloseRegisterShiftCommand {
        val values = RegisterShiftCodecSupport.parse(operation.intentPayload)

        require(values["v"] == CURRENT_SCHEMA_VERSION.toString()) {
            "Unsupported Register Shift close schema"
        }
        require(values["commandType"] == CLOSE_COMMAND_TYPE) {
            "Unexpected Register Shift close command type"
        }

        val command = CloseRegisterShiftCommand(
            operationId = OperationId(RegisterShiftCodecSupport.required(values, "operationId")),
            businessId = RegisterShiftCodecSupport.required(values, "businessId"),
            branchId = RegisterShiftCodecSupport.required(values, "branchId"),
            shiftId = CashShiftId(RegisterShiftCodecSupport.required(values, "shiftId")),
            actorUserId = RegisterShiftCodecSupport.required(values, "actorUserId"),
            deviceId = RegisterShiftCodecSupport.required(values, "deviceId"),
            occurredAt =
                RegisterShiftCodecSupport.required(values, "occurredAt").toLongOrNull()
                    ?: error("Register Shift close occurredAt is invalid"),
            reportSnapshot = values["reportSnapshotJson"]?.let(RegisterShiftCloseReportSnapshot::fromJsonString)
        )

        validateLifecycleOperation(
            operation = operation,
            operationId = command.operationId,
            businessId = command.businessId,
            branchId = command.branchId,
            shiftId = command.shiftId,
            actorUserId = command.actorUserId,
            deviceId = command.deviceId,
            occurredAt = command.occurredAt,
            operationType = OperationType.CLOSE_CASH_SHIFT,
            fingerprint = command.requestFingerprint
        )

        return command
    }

    private fun validateLifecycleOperation(
        operation: OperationEntity,
        operationId: OperationId,
        businessId: String,
        branchId: String,
        shiftId: CashShiftId,
        actorUserId: String,
        deviceId: String,
        occurredAt: Long,
        operationType: OperationType,
        fingerprint: String
    ) {
        require(operation.id == operationId.value) {
            "Register Shift lifecycle operation identity mismatch"
        }
        require(operation.operationType == operationType) {
            "Register Shift lifecycle operation type mismatch"
        }
        require(operation.subjectType == SubjectType.CASH_SHIFT) {
            "Register Shift lifecycle subject type mismatch"
        }
        require(operation.subjectId == shiftId.value) {
            "Register Shift lifecycle subject identity mismatch"
        }
        require(operation.businessId == businessId) {
            "Register Shift lifecycle business identity mismatch"
        }
        require(operation.branchId == branchId) {
            "Register Shift lifecycle branch identity mismatch"
        }
        require(operation.actorUserId == actorUserId) {
            "Register Shift lifecycle actor identity mismatch"
        }
        require(operation.deviceId == deviceId) {
            "Register Shift lifecycle device identity mismatch"
        }
        require(operation.occurredAt == occurredAt) {
            "Register Shift lifecycle timestamp identity mismatch"
        }
        require(operation.transactionId == null) {
            "Register Shift lifecycle cannot reference a transaction"
        }
        require(operation.commandSchemaVersion == CURRENT_SCHEMA_VERSION) {
            "Register Shift lifecycle schema mismatch"
        }
        require(operation.requestFingerprint == fingerprint) {
            "Register Shift lifecycle request fingerprint mismatch"
        }
    }
}

private object RegisterShiftCodecSupport {

    fun parse(payload: String): Map<String, String> = payload
        .lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed Register Shift payload" }

            line.substring(0, separator) to
                DurableEnvelopeCodec.unescape(
                    line.substring(separator + 1)
                )
        }

    fun required(
        values: Map<String, String>,
        key: String
    ): String =
        values[key]
            ?.takeIf { it.isNotBlank() }
            ?: error("Register Shift payload is missing $key")
}