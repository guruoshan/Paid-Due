package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState

/**
 * Durable Cash Sale payload codec.
 *
 * V1 = legacy Cash Sale payload without an explicit command type.
 * V2 = explicitly identifies itself as CASH_SALE.
 *
 * Recovery fails closed for unsupported versions/types rather than
 * guessing how a financial command should be interpreted.
 */
object CashSalePayloadCodec {

    const val COMMAND_TYPE = "CASH_SALE"

    const val LEGACY_SCHEMA_VERSION = 1
    const val CURRENT_SCHEMA_VERSION = 2

    fun encode(cmd: CashSaleCommand): String {

        val sb = StringBuilder()

        sb.append("v=$CURRENT_SCHEMA_VERSION\n")
        sb.append("commandType=$COMMAND_TYPE\n")

        sb.append("operationId=")
            .append(escape(cmd.operationId.value))
            .append("\n")

        sb.append("businessId=")
            .append(escape(cmd.businessId))
            .append("\n")

        sb.append("branchId=")
            .append(escape(cmd.branchId))
            .append("\n")

        sb.append("actorUserId=")
            .append(escape(cmd.actorUserId))
            .append("\n")

        sb.append("transactionId=")
            .append(escape(cmd.transactionIdVal.value))
            .append("\n")

        sb.append("currencyCode=")
            .append(escape(cmd.currencyCode))
            .append("\n")

        sb.append("currencyDecimalPlaces=")
            .append(cmd.currencyDecimalPlaces)
            .append("\n")

        sb.append("deviceId=")
            .append(escape(cmd.deviceId))
            .append("\n")

        sb.append("occurredAt=")
            .append(cmd.occurredAt)
            .append("\n")

        val d = cmd.draftState

        sb.append("manualAmount=")
            .append(d.manualAmount.amount)
            .append("\n")

        sb.append("manualDescription=")
            .append(escape(d.manualDescription ?: ""))
            .append("\n")

        sb.append("taxRateBps=")
            .append(d.taxRateBps)
            .append("\n")

        sb.append("isTaxEnabled=")
            .append(d.isTaxEnabled)
            .append("\n")

        sb.append("taxRoundingMode=")
            .append(d.taxRoundingMode.name)
            .append("\n")

        sb.append("txShipping=")
            .append(d.txShipping.amount)
            .append("\n")

        sb.append("txPacking=")
            .append(d.txPacking.amount)
            .append("\n")

        sb.append("itemCount=")
            .append(d.items.size)
            .append("\n")

        d.items.forEachIndexed { i, item ->

            sb.append("item[$i].id=")
                .append(escape(item.id))
                .append("\n")

            sb.append("item[$i].name=")
                .append(escape(item.name))
                .append("\n")

            sb.append("item[$i].desc=")
                .append(escape(item.description ?: ""))
                .append("\n")

            sb.append("item[$i].unitPrice=")
                .append(item.unitPrice.amount)
                .append("\n")

            sb.append("item[$i].qty=")
                .append(item.quantity)
                .append("\n")

            sb.append("item[$i].barcode=")
                .append(escape(item.barcode ?: ""))
                .append("\n")

            sb.append("item[$i].itemPacking=")
                .append(item.itemPackingCharge.amount)
                .append("\n")

            sb.append("item[$i].itemShipping=")
                .append(item.itemShippingCharge.amount)
                .append("\n")
        }

        return sb.toString()
    }

    /**
     * True only when this codec can safely and unambiguously recover
     * the supplied durable operation.
     */
    fun canDecode(op: OperationEntity): Boolean {
        return runCatching {
            validateEnvelope(op)
            true
        }.getOrDefault(false)
    }

    /**
     * Convenience decode used by JVM tests.
     */
    fun decode(intentPayload: String): CashSaleCommand {

        val map = parsePayload(intentPayload)

        val schemaVersion =
            requiredInt(map, "v")

        requireSupportedSchema(schemaVersion)

        val operationId =
            required(map, "operationId")

        val businessId =
            required(map, "businessId")

        val branchId =
            required(map, "branchId")

        val actorUserId =
            required(map, "actorUserId")

        val transactionId =
            required(map, "transactionId")

        val deviceId =
            required(map, "deviceId")

        val occurredAt =
            requiredLong(map, "occurredAt")

        val syntheticOperation = OperationEntity(
            id = operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = OperationType.RECORD_SALE,
            subjectType = SubjectType.TRANSACTION,
            subjectId = transactionId,
            transactionId = transactionId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            occurredAt = occurredAt,
            requestFingerprint = "",
            commandSchemaVersion = schemaVersion,
            intentPayload = intentPayload,
            status = OperationStatus.PENDING
        )

        return decodeInternal(
            op = syntheticOperation,
            verifyFingerprint = false
        )
    }

    /**
     * Recovery decode.
     *
     * This path validates:
     * - operation type
     * - schema version
     * - command type
     * - durable metadata consistency
     * - request fingerprint consistency
     */
    fun decode(op: OperationEntity): CashSaleCommand {

        return decodeInternal(
            op = op,
            verifyFingerprint = true
        )
    }

    private fun decodeInternal(
        op: OperationEntity,
        verifyFingerprint: Boolean
    ): CashSaleCommand {

        val map = validateEnvelope(op)

        val operationId =
            required(map, "operationId")

        val businessId =
            required(map, "businessId")

        val branchId =
            required(map, "branchId")

        val actorUserId =
            required(map, "actorUserId")

        val txId =
            required(map, "transactionId")

        val currencyCode =
            required(map, "currencyCode")

        val currencyDecimalPlaces =
            requiredInt(map, "currencyDecimalPlaces")

        val deviceId =
            required(map, "deviceId")

        val occurredAt =
            requiredLong(map, "occurredAt")

        /*
         * The durable payload and operation envelope must describe
         * the same exact business intent.
         */
        require(operationId == op.id) {
            "Cash Sale payload operationId does not match Operation row."
        }

        require(businessId == op.businessId) {
            "Cash Sale payload businessId does not match Operation row."
        }

        require(branchId == op.branchId) {
            "Cash Sale payload branchId does not match Operation row."
        }

        require(actorUserId == op.actorUserId) {
            "Cash Sale payload actorUserId does not match Operation row."
        }

        require(txId == (op.transactionId ?: op.subjectId)) {
            "Cash Sale payload transactionId does not match Operation row."
        }

        require(deviceId == op.deviceId) {
            "Cash Sale payload deviceId does not match Operation row."
        }

        require(occurredAt == op.occurredAt) {
            "Cash Sale payload occurredAt does not match Operation row."
        }

        require(currencyCode.isNotBlank()) {
            "Cash Sale currencyCode is blank."
        }

        require(currencyDecimalPlaces in 0..6) {
            "Cash Sale currencyDecimalPlaces is invalid."
        }

        val manualAmount =
            MoneyMinorUnits(
                requiredLong(map, "manualAmount")
            )

        val manualDescription =
            required(map, "manualDescription")
                .ifBlank { null }

        val taxRateBps =
            requiredInt(map, "taxRateBps")

        val isTaxEnabled =
            requiredBoolean(map, "isTaxEnabled")

        val taxRoundingMode =
            try {
                TaxRoundingMode.valueOf(
                    required(map, "taxRoundingMode")
                )
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "Invalid Cash Sale taxRoundingMode.",
                    error
                )
            }

        val txShipping =
            MoneyMinorUnits(
                requiredLong(map, "txShipping")
            )

        val txPacking =
            MoneyMinorUnits(
                requiredLong(map, "txPacking")
            )

        val itemCount =
            requiredInt(map, "itemCount")

        require(itemCount >= 0) {
            "Cash Sale itemCount cannot be negative."
        }

        val items =
            mutableListOf<DraftItem>()

        for (i in 0 until itemCount) {

            val id =
                required(map, "item[$i].id")

            val name =
                required(map, "item[$i].name")

            val description =
                required(map, "item[$i].desc")
                    .ifBlank { null }

            val unitPrice =
                MoneyMinorUnits(
                    requiredLong(
                        map,
                        "item[$i].unitPrice"
                    )
                )

            val quantity =
                requiredInt(
                    map,
                    "item[$i].qty"
                )

            require(quantity > 0) {
                "Cash Sale item quantity must be greater than zero."
            }

            val barcode =
                required(map, "item[$i].barcode")
                    .ifBlank { null }

            val itemPacking =
                MoneyMinorUnits(
                    requiredLong(
                        map,
                        "item[$i].itemPacking"
                    )
                )

            val itemShipping =
                MoneyMinorUnits(
                    requiredLong(
                        map,
                        "item[$i].itemShipping"
                    )
                )

            items.add(
                DraftItem(
                    id = id,
                    name = name,
                    description = description,
                    unitPrice = unitPrice,
                    quantity = quantity,
                    barcode = barcode,
                    itemPackingCharge = itemPacking,
                    itemShippingCharge = itemShipping
                )
            )
        }

        val draft = DraftState(
            items = items,
            manualAmount = manualAmount,
            manualDescription = manualDescription,
            taxRateBps = taxRateBps,
            isTaxEnabled = isTaxEnabled,
            taxRoundingMode = taxRoundingMode,
            txShipping = txShipping,
            txPacking = txPacking
        )

        val command = CashSaleCommand(
            operationId = OperationId(operationId),
            businessId = businessId,
            branchId = branchId,
            actorUserId = actorUserId,
            transactionIdVal = TransactionId(txId),
            draftState = draft,
            currencyCode = currencyCode,
            currencyDecimalPlaces = currencyDecimalPlaces,
            deviceId = deviceId,
            occurredAt = occurredAt
        )

        if (verifyFingerprint) {
            require(
                command.requestFingerprint ==
                    op.requestFingerprint
            ) {
                "Cash Sale recovery fingerprint does not match the persisted Operation."
            }
        }

        return command
    }

    /**
     * Validates which financial command this payload actually represents.
     */
    private fun validateEnvelope(
        op: OperationEntity
    ): Map<String, String> {

        require(
            op.operationType ==
                OperationType.RECORD_SALE
        ) {
            "Operation '${op.id}' is not a sale operation."
        }

        val map =
            parsePayload(op.intentPayload)

        val payloadVersion =
            requiredInt(map, "v")

        requireSupportedSchema(payloadVersion)

        require(
            payloadVersion ==
                op.commandSchemaVersion
        ) {
            "Operation '${op.id}' schema version does not match its durable payload."
        }

        when (payloadVersion) {

            LEGACY_SCHEMA_VERSION -> {

                /*
                 * V1 predates commandType.
                 *
                 * Only accept it when it has the complete known
                 * Cash Sale shape. This preserves recovery of
                 * already-persisted legacy Cash Sales.
                 */
                require(
                    map["commandType"] == null
                ) {
                    "Legacy Cash Sale payload contains an unexpected commandType."
                }

                requireLegacyCashShape(map)
            }

            CURRENT_SCHEMA_VERSION -> {

                require(
                    required(
                        map,
                        "commandType"
                    ) == COMMAND_TYPE
                ) {
                    "Sale operation '${op.id}' is not a CASH_SALE command."
                }
            }
        }

        return map
    }

    private fun requireLegacyCashShape(
        map: Map<String, String>
    ) {

        val requiredKeys = listOf(
            "operationId",
            "businessId",
            "branchId",
            "actorUserId",
            "transactionId",
            "currencyCode",
            "currencyDecimalPlaces",
            "deviceId",
            "occurredAt",
            "manualAmount",
            "manualDescription",
            "taxRateBps",
            "isTaxEnabled",
            "taxRoundingMode",
            "txShipping",
            "txPacking",
            "itemCount"
        )

        requiredKeys.forEach { key ->
            require(map.containsKey(key)) {
                "Legacy Cash Sale payload is missing '$key'."
            }
        }
    }

    private fun requireSupportedSchema(
        version: Int
    ) {

        require(
            version == LEGACY_SCHEMA_VERSION ||
                version == CURRENT_SCHEMA_VERSION
        ) {
            "Unsupported Cash Sale payload schema version: $version"
        }
    }

    private fun parsePayload(
        payload: String
    ): Map<String, String> {

        val map =
            mutableMapOf<String, String>()

        payload.lines().forEach { line ->

            if (line.isBlank()) {
                return@forEach
            }

            val idx =
                line.indexOf('=')

            require(idx > 0) {
                "Malformed Cash Sale payload line."
            }

            val key =
                line.substring(
                    0,
                    idx
                ).trim()

            val value =
                line.substring(idx + 1)

            require(
                !map.containsKey(key)
            ) {
                "Duplicate Cash Sale payload key '$key'."
            }

            map[key] =
                unescape(value)
        }

        return map
    }

    private fun required(
        map: Map<String, String>,
        key: String
    ): String {

        return map[key]
            ?: throw IllegalArgumentException(
                "Cash Sale payload is missing '$key'."
            )
    }

    private fun requiredInt(
        map: Map<String, String>,
        key: String
    ): Int {

        return required(map, key)
            .toIntOrNull()
            ?: throw IllegalArgumentException(
                "Cash Sale payload '$key' is not a valid Int."
            )
    }

    private fun requiredLong(
        map: Map<String, String>,
        key: String
    ): Long {

        return required(map, key)
            .toLongOrNull()
            ?: throw IllegalArgumentException(
                "Cash Sale payload '$key' is not a valid Long."
            )
    }

    private fun requiredBoolean(
        map: Map<String, String>,
        key: String
    ): Boolean {

        return required(map, key)
            .toBooleanStrictOrNull()
            ?: throw IllegalArgumentException(
                "Cash Sale payload '$key' is not a valid Boolean."
            )
    }

    private fun escape(
        value: String
    ): String {

        return value
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun unescape(
        value: String
    ): String {

        return value.replace(
            "\\n",
            "\n"
        )
    }
}
