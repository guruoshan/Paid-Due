package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

object CouponCreditRepaymentPayloadCodec {

    const val COMMAND_TYPE =
        "COUPON_CREDIT_REPAYMENT"

    const val CURRENT_SCHEMA_VERSION =
        1

    fun encode(
        command: CouponCreditRepaymentCommand
    ): String =
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
            appendLine("walletId=${command.walletId}")
            appendLine("authorizedUserId=${command.authorizedUserId}")
            appendLine("authorizedUserName=${command.authorizedUserName}")
            appendLine("deviceId=${command.deviceId}")
            append("occurredAt=${command.occurredAt}")
        }

    fun decode(
        operation: OperationEntity
    ): CouponCreditRepaymentCommand {

        check(
            operation.operationType ==
                OperationType.RECORD_CREDIT_REPAYMENT
        ) {
            "Durable Coupon Credit repayment operation type mismatch."
        }

        check(
            operation.subjectType ==
                SubjectType.REPAYMENT
        ) {
            "Durable Coupon Credit repayment subject type mismatch."
        }

        check(operation.transactionId == null) {
            "Coupon Credit repayment must not masquerade as a sale."
        }

        check(
            operation.commandSchemaVersion ==
                CURRENT_SCHEMA_VERSION
        ) {
            "Unsupported Coupon Credit repayment schema."
        }

        val map =
            operation.intentPayload
                .lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->

                    val index =
                        line.indexOf('=')

                    require(index > 0) {
                        "Malformed Coupon Credit repayment payload."
                    }

                    line.substring(0, index) to
                        line.substring(index + 1)
                }

        check(
            required(map, "v").toInt() ==
                CURRENT_SCHEMA_VERSION
        ) {
            "Coupon Credit repayment payload version mismatch."
        }

        check(
            required(map, "commandType") ==
                COMMAND_TYPE
        ) {
            "Coupon Credit repayment command type mismatch."
        }

        val branchId =
            operation.branchId
                ?: error(
                    "Durable Coupon Credit repayment has no branch."
                )

        val command =
            CouponCreditRepaymentCommand(
                operationId =
                    OperationId(operation.id),

                businessId =
                    operation.businessId,

                branchId =
                    branchId,

                actorUserId =
                    operation.actorUserId,

                repaymentId =
                    required(map, "repaymentId"),

                customerId =
                    required(map, "customerId"),

                amountMinor =
                    required(map, "amountMinor")
                        .toLongOrNull()
                        ?: error(
                            "Invalid Coupon Credit repayment amount."
                        ),

                currencyCode =
                    required(map, "currencyCode"),

                walletId =
                    required(map, "walletId"),

                authorizedUserId =
                    required(map, "authorizedUserId"),

                authorizedUserName =
                    required(map, "authorizedUserName"),

                deviceId =
                    operation.deviceId,

                occurredAt =
                    operation.occurredAt
            )

        check(
            required(map, "operationId") ==
                operation.id
        )

        check(
            required(map, "businessId") ==
                operation.businessId
        )

        check(
            required(map, "branchId") ==
                branchId
        )

        check(
            required(map, "actorUserId") ==
                operation.actorUserId
        )

        check(
            required(map, "deviceId") ==
                operation.deviceId
        )

        check(
            required(map, "occurredAt")
                .toLongOrNull() ==
                operation.occurredAt
        ) {
            "Coupon Credit repayment occurredAt mismatch."
        }

        check(
            command.repaymentId ==
                operation.subjectId
        ) {
            "Coupon Credit repayment subject mismatch."
        }

        check(
            command.requestFingerprint ==
                operation.requestFingerprint
        ) {
            "Coupon Credit repayment fingerprint mismatch."
        }

        check(
            encode(command) ==
                operation.intentPayload
        ) {
            "Coupon Credit repayment durable payload is not canonical."
        }

        return command
    }

    private fun required(
        map: Map<String, String>,
        key: String
    ): String =
        map[key]
            ?: error(
                "Coupon Credit repayment payload is missing '$key'."
            )
}
