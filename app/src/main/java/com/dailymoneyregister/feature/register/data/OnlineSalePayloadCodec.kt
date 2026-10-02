package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.serialization.DurableEnvelopeCodec
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState

object OnlineSalePayloadCodec {
    const val COMMAND_TYPE = "ONLINE_SALE"
    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(cmd: OnlineSaleCommand): String = buildString {
        appendLine("v=$CURRENT_SCHEMA_VERSION")
        appendLine("commandType=$COMMAND_TYPE")
        appendLine("operationId=${DurableEnvelopeCodec.escape(cmd.operationId.value)}")
        appendLine("businessId=${DurableEnvelopeCodec.escape(cmd.businessId)}")
        appendLine("branchId=${DurableEnvelopeCodec.escape(cmd.branchId)}")
        appendLine("actorUserId=${DurableEnvelopeCodec.escape(cmd.actorUserId)}")
        appendLine("transactionId=${DurableEnvelopeCodec.escape(cmd.transactionIdVal.value)}")
        appendLine("currencyCode=${DurableEnvelopeCodec.escape(cmd.currencyCode)}")
        appendLine("currencyDecimalPlaces=${cmd.currencyDecimalPlaces}")
        appendLine("deviceId=${DurableEnvelopeCodec.escape(cmd.deviceId)}")
        appendLine("occurredAt=${cmd.occurredAt}")
        val d = cmd.draftState
        appendLine("manualAmount=${d.manualAmount.amount}")
        appendLine("manualDescription=${DurableEnvelopeCodec.escape(d.manualDescription ?: "")}")
        appendLine("taxRateBps=${d.taxRateBps}")
        appendLine("isTaxEnabled=${d.isTaxEnabled}")
        appendLine("taxRoundingMode=${d.taxRoundingMode.name}")
        appendLine("txShipping=${d.txShipping.amount}")
        appendLine("txPacking=${d.txPacking.amount}")
        appendLine("itemCount=${d.items.size}")
        d.items.forEachIndexed { index, item ->
            appendLine("item[$index].id=${DurableEnvelopeCodec.escape(item.id)}")
            appendLine("item[$index].name=${DurableEnvelopeCodec.escape(item.name)}")
            appendLine("item[$index].desc=${DurableEnvelopeCodec.escape(item.description ?: "")}")
            appendLine("item[$index].unitPrice=${item.unitPrice.amount}")
            appendLine("item[$index].qty=${item.quantity}")
            appendLine("item[$index].barcode=${DurableEnvelopeCodec.escape(item.barcode ?: "")}")
            appendLine("item[$index].itemPacking=${item.itemPackingCharge.amount}")
            appendLine("item[$index].itemShipping=${item.itemShippingCharge.amount}")
        }
    }

    fun canDecode(op: OperationEntity): Boolean = runCatching { decode(op) }.isSuccess

    fun decode(intentPayload: String): OnlineSaleCommand = decodeInternal(
        op = OperationEntity(
            id = required(parse(intentPayload), "operationId"),
            businessId = required(parse(intentPayload), "businessId"),
            branchId = required(parse(intentPayload), "branchId"),
            operationType = OperationType.RECORD_SALE,
            subjectType = SubjectType.TRANSACTION,
            subjectId = required(parse(intentPayload), "transactionId"),
            transactionId = required(parse(intentPayload), "transactionId"),
            actorUserId = required(parse(intentPayload), "actorUserId"),
            deviceId = required(parse(intentPayload), "deviceId"),
            occurredAt = requiredLong(parse(intentPayload), "occurredAt"),
            requestFingerprint = "",
            commandSchemaVersion = CURRENT_SCHEMA_VERSION,
            intentPayload = intentPayload,
            status = OperationStatus.PENDING
        ),
        verifyFingerprint = false
    )

    fun decode(op: OperationEntity): OnlineSaleCommand = decodeInternal(op, true)

    private fun decodeInternal(op: OperationEntity, verifyFingerprint: Boolean): OnlineSaleCommand {
        val map = validate(op)
        val operationId = required(map, "operationId")
        val businessId = required(map, "businessId")
        val branchId = required(map, "branchId")
        val actorUserId = required(map, "actorUserId")
        val transactionId = required(map, "transactionId")
        val currencyCode = required(map, "currencyCode")
        val decimalPlaces = requiredInt(map, "currencyDecimalPlaces")
        val deviceId = required(map, "deviceId")
        val occurredAt = requiredLong(map, "occurredAt")
        require(operationId == op.id) { "Online Sale payload operationId does not match Operation row." }
        require(businessId == op.businessId) { "Online Sale payload businessId does not match Operation row." }
        require(branchId == op.branchId) { "Online Sale payload branchId does not match Operation row." }
        require(actorUserId == op.actorUserId) { "Online Sale payload actorUserId does not match Operation row." }
        require(transactionId == (op.transactionId ?: op.subjectId)) { "Online Sale payload transactionId does not match Operation row." }
        require(deviceId == op.deviceId) { "Online Sale payload deviceId does not match Operation row." }
        require(occurredAt == op.occurredAt) { "Online Sale payload occurredAt does not match Operation row." }
        require(currencyCode.isNotBlank()) { "Online Sale currencyCode is blank." }
        require(decimalPlaces in 0..6) { "Online Sale currencyDecimalPlaces is invalid." }
        val itemCount = requiredInt(map, "itemCount")
        require(itemCount >= 0) { "Online Sale itemCount cannot be negative." }
        val items = (0 until itemCount).map { index ->
            DraftItem(
                id = required(map, "item[$index].id"),
                name = required(map, "item[$index].name"),
                description = required(map, "item[$index].desc").ifBlank { null },
                unitPrice = MoneyMinorUnits(requiredLong(map, "item[$index].unitPrice")),
                quantity = requiredInt(map, "item[$index].qty"),
                barcode = required(map, "item[$index].barcode").ifBlank { null },
                itemPackingCharge = MoneyMinorUnits(requiredLong(map, "item[$index].itemPacking")),
                itemShippingCharge = MoneyMinorUnits(requiredLong(map, "item[$index].itemShipping"))
            )
        }
        val command = OnlineSaleCommand(
            operationId = OperationId(operationId),
            businessId = businessId,
            branchId = branchId,
            actorUserId = actorUserId,
            transactionIdVal = TransactionId(transactionId),
            draftState = DraftState(
                items = items,
                manualAmount = MoneyMinorUnits(requiredLong(map, "manualAmount")),
                manualDescription = required(map, "manualDescription").ifBlank { null },
                taxRateBps = requiredInt(map, "taxRateBps"),
                isTaxEnabled = requiredBoolean(map, "isTaxEnabled"),
                taxRoundingMode = TaxRoundingMode.valueOf(required(map, "taxRoundingMode")),
                txShipping = MoneyMinorUnits(requiredLong(map, "txShipping")),
                txPacking = MoneyMinorUnits(requiredLong(map, "txPacking"))
            ),
            currencyCode = currencyCode,
            currencyDecimalPlaces = decimalPlaces,
            deviceId = deviceId,
            occurredAt = occurredAt
        )
        if (verifyFingerprint) require(command.requestFingerprint == op.requestFingerprint) {
            "Online Sale recovery fingerprint does not match the persisted Operation."
        }
        return command
    }

    private fun validate(op: OperationEntity): Map<String, String> {
        require(op.operationType == OperationType.RECORD_SALE) { "Operation '${op.id}' is not a sale operation." }
        require(op.subjectType == SubjectType.TRANSACTION) { "Online Sale operation subject type mismatch." }
        val map = parse(op.intentPayload)
        require(requiredInt(map, "v") == CURRENT_SCHEMA_VERSION) { "Unsupported Online Sale payload schema version." }
        require(required(map, "commandType") == COMMAND_TYPE) { "Operation '${op.id}' is not an ONLINE_SALE command." }
        require(op.commandSchemaVersion == CURRENT_SCHEMA_VERSION) { "Online Sale schema version mismatch." }
        return map
    }

    private fun parse(payload: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        payload.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val index = line.indexOf('=')
            require(index > 0) { "Malformed Online Sale payload line." }
            val key = line.substring(0, index)
            require(key !in map) { "Duplicate Online Sale payload key '$key'." }
            map[key] = DurableEnvelopeCodec.unescape(line.substring(index + 1))
        }
        return map
    }

    private fun required(map: Map<String, String>, key: String): String =
        map[key] ?: throw IllegalArgumentException("Online Sale payload is missing '$key'.")
    private fun requiredInt(map: Map<String, String>, key: String): Int =
        required(map, key).toIntOrNull() ?: throw IllegalArgumentException("Online Sale payload '$key' is not a valid Int.")
    private fun requiredLong(map: Map<String, String>, key: String): Long =
        required(map, key).toLongOrNull() ?: throw IllegalArgumentException("Online Sale payload '$key' is not a valid Long.")
    private fun requiredBoolean(map: Map<String, String>, key: String): Boolean =
        required(map, key).toBooleanStrictOrNull() ?: throw IllegalArgumentException("Online Sale payload '$key' is not a valid Boolean.")
}
