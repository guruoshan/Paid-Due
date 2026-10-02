package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

object CreditRepaymentPayloadCodec {

    const val COMMAND_TYPE = "CREDIT_REPAYMENT"
    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(command: CreditRepaymentCommand): String =
        buildString {
            appendLine("v=$CURRENT_SCHEMA_VERSION")
            appendLine("commandType=$COMMAND_TYPE")
            appendLine("operationId=${command.operationId.value}")
            appendLine("businessId=${command.businessId}")
            appendLine("branchId=${command.branchId}")
            appendLine("actorUserId=${command.actorUserId}")
            appendLine("repaymentId=${command.repaymentId}")
            appendLine("customerId=${command.customerId}")
            appendLine("amountMinor=${command.amountMinor}")
            appendLine("currencyCode=${command.currencyCode}")
            appendLine("paymentCategory=${command.paymentCategory.name}")
            appendLine("paymentChannel=${command.paymentChannel.name}")
            appendLine("deviceId=${command.deviceId}")
            append("occurredAt=${command.occurredAt}")
        }

    fun decode(operation: OperationEntity): CreditRepaymentCommand {

        check(operation.operationType == OperationType.RECORD_CREDIT_REPAYMENT) {
            "Durable Credit repayment operation type mismatch."
        }

        check(operation.subjectType == SubjectType.REPAYMENT) {
            "Durable Credit repayment subject type mismatch."
        }

        check(operation.transactionId == null) {
            "Credit repayment must not masquerade as a sale transaction."
        }

        check(operation.commandSchemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported Credit repayment command schema."
        }

        val map =
            operation.intentPayload
                .lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val index = line.indexOf('=')

                    require(index > 0) {
                        "Malformed Credit repayment durable payload."
                    }

                    line.substring(0, index) to
                        line.substring(index + 1)
                }

        check(required(map, "v").toInt() == CURRENT_SCHEMA_VERSION) {
            "Credit repayment payload version mismatch."
        }

        check(required(map, "commandType") == COMMAND_TYPE) {
            "Credit repayment command type mismatch."
        }

        val branchId =
            operation.branchId
                ?: error("Durable Credit repayment has no branch.")

        val command =
            CreditRepaymentCommand(
                operationId = OperationId(operation.id),
                businessId = operation.businessId,
                branchId = branchId,
                actorUserId = operation.actorUserId,

                repaymentId =
                    required(map, "repaymentId"),

                customerId =
                    required(map, "customerId"),

                amountMinor =
                    required(map, "amountMinor").toLongOrNull()
                        ?: error("Invalid Credit repayment amount."),

                currencyCode =
                    required(map, "currencyCode"),

                paymentCategory =
                    enumValueOf<PaymentCategory>(
                        required(map, "paymentCategory")
                    ),

                paymentChannel =
                    enumValueOf<PaymentChannel>(
                        required(map, "paymentChannel")
                    ),

                deviceId = operation.deviceId,
                occurredAt = operation.occurredAt
            )

        check(required(map, "operationId") == operation.id)
        check(required(map, "businessId") == operation.businessId)
        check(required(map, "branchId") == branchId)
        check(required(map, "actorUserId") == operation.actorUserId)
        check(required(map, "deviceId") == operation.deviceId)

        check(
            required(map, "occurredAt").toLongOrNull() ==
                operation.occurredAt
        ) {
            "Credit repayment occurredAt mismatch."
        }

        check(command.repaymentId == operation.subjectId) {
            "Credit repayment subject identity mismatch."
        }

        check(command.requestFingerprint == operation.requestFingerprint) {
            "Credit repayment request fingerprint mismatch."
        }

        check(encode(command) == operation.intentPayload) {
            "Credit repayment durable payload is not canonical."
        }

        return command
    }

    private fun required(
        map: Map<String, String>,
        key: String
    ): String =
        map[key]
            ?: error(
                "Credit repayment payload is missing '$key'."
            )
}
