package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId

/**
 * Captures and decodes the durable Refund admission snapshot.
 * The implementation is intentionally moved without changing validation,
 * fingerprinting, or persisted command semantics.
 */
internal class RefundAdmissionCoordinator(
    database: DmrDatabase
) {
    private val transactionDao = database.transactionDao()
    private val ledgerDao = database.ledgerDao()

    internal suspend fun prepareRefundAdmission(
        command: RefundCommand
    ): RefundCommand {

        val record =
            transactionDao.getFinancialRecordById(
                command.subjectId
            )

        val allocations =
            ledgerDao
                .getCreditRepaymentAllocationsForTransaction(
                    command.subjectId
                )

        val stateFingerprint =
            RefundFinancialIntegrity.fingerprint(
                transactionId = command.subjectId,
                record = record,
                creditAllocations = allocations
            )

        /*
         * Always persist the raw financial-state fingerprint.
         *
         * A currently invalid Refund is allowed to enter the durable
         * command spine, then fail atomically during authoritative
         * execution. We never invent a funding destination.
         */
        val validated =
            if (
                record != null &&
                record.transaction.branchId ==
                    command.branchId
            ) {
                try {
                    RefundFinancialIntegrity.validate(
                        record = record,
                        creditAllocations = allocations,
                        refundDestination = command.destinationCategory
                    )
                } catch (_: RuntimeException) {
                    null
                }
            } else {
                null
            }

        return command.copy(
            financialPrecondition =
                RefundFinancialPrecondition(
                    stateFingerprint =
                        stateFingerprint,
                    expectedRemainingRefundableMinor =
                        validated?.remainingRefundableMinor,
                    fundingCategory =
                        validated?.fundingCategory,
                    fundingChannel =
                        validated?.fundingChannel
                )
        )
    }

    internal fun decodePersistedRefundCommand(
        operation: OperationEntity
    ): RefundCommand {

        check(
            operation.operationType ==
                OperationType.RECORD_REFUND
        ) {
            "Durable Refund operation type mismatch"
        }

        check(
            operation.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Durable Refund subject type mismatch"
        }

        check(
            operation.transactionId ==
                operation.subjectId
        ) {
            "Durable Refund transaction identity mismatch"
        }

        val branchId =
            operation.branchId
                ?: throw IllegalStateException(
                    "Durable Refund has no branch"
                )

        val payload =
            RefundPayloadCodec.decode(
                operation.intentPayload
            )

        check(
            operation.commandSchemaVersion ==
                payload.schemaVersion
        ) {
            "Durable Refund schema version mismatch"
        }

        val command =
            RefundCommand(
                operationId =
                    OperationId(operation.id),
                businessId =
                    operation.businessId,
                branchId =
                    branchId,
                subjectId =
                    operation.subjectId,
                actorUserId =
                    operation.actorUserId,
                deviceId =
                    operation.deviceId,
                occurredAt =
                    operation.occurredAt,
                refundAmountMinor =
                    payload.refundAmountMinor,
                destinationCategory =
                    payload.destinationCategory,
                destinationChannel =
                    payload.destinationChannel,
                reason =
                    payload.reason,
                financialPrecondition =
                    payload.financialPrecondition,
                couponDestination =
                    payload.couponDestination
            )

        check(
            command.requestFingerprint ==
                operation.requestFingerprint
        ) {
            "Durable Refund request fingerprint mismatch"
        }

        if (
            operation.status ==
            OperationStatus.PENDING
        ) {
            check(
                operation.resultReferenceType == null &&
                    operation.resultReferenceId == null
            ) {
                "Pending Refund already has a result reference"
            }
        }

        return command
    }
}
