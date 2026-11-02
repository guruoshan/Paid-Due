package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import androidx.room.withTransaction
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord

/**
 * Owns Refund replay verification, pending reconciliation, and recovery.
 * Financial planning and admitted execution remain supplied by the repository.
 */
internal class RefundReplayCoordinator(
    private val database: DmrDatabase,
    private val admissionCoordinator: RefundAdmissionCoordinator,
    private val executeAdmittedRefund: suspend (RefundCommand) -> CommandResult<RefundExecutionResult>,
    private val buildCreditReturnB2Plan: suspend (
        TransactionEntity,
        TransactionFinancialRecord,
        List<CreditRepaymentAllocationEntity>,
        Long,
        String?
    ) -> CreditReturnPlan
) {
    private val transactionDao = database.transactionDao()
    private val ledgerDao = database.ledgerDao()

    internal suspend fun reconcilePendingRefund(
        operation: OperationEntity,
        reason: String
    ): CommandResult<RefundExecutionResult> {

        return database.withTransaction {

            val latest =
                database.operationDao()
                    .getOperationById(operation.id)
                    ?: return@withTransaction CommandResult.Error(
                        IllegalStateException(
                            "Pending Refund operation '${operation.id}' disappeared"
                        )
                    )

            if (
                latest.requestFingerprint !=
                operation.requestFingerprint
            ) {
                return@withTransaction CommandResult.Conflict(
                    "Conflict: Refund intent changed during reconciliation."
                )
            }

            when (latest.status) {

                OperationStatus.SUCCEEDED -> {
                    CommandResult.Success(
                        data =
                            resolveRefundReplay(
                                operation = latest,
                                expectedTransactionId =
                                    latest.subjectId
                            ),
                        isIdempotentReplay = true
                    )
                }

                OperationStatus.FAILED -> {
                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' is already FAILED."
                    )
                }

                OperationStatus.REQUIRES_RECONCILIATION -> {
                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' requires reconciliation."
                    )
                }

                OperationStatus.PENDING -> {

                    val changed =
                        database.operationDao()
                            .markRequiresReconciliationIfPending(
                                operationId =
                                    latest.id,
                                requestFingerprint =
                                    latest.requestFingerprint,
                                reason =
                                    reason
                            )

                    check(changed == 1) {
                        "Refund reconciliation transition failed"
                    }

                    CommandResult.Conflict(
                        "Conflict: Operation ID '${latest.id}' " +
                            "requires reconciliation: $reason"
                    )
                }
            }
        }
    }

    internal suspend fun resolveRefundReplay(
        operation: OperationEntity,
        expectedTransactionId: String
    ): RefundExecutionResult {

        check(
            operation.operationType ==
                OperationType.RECORD_REFUND
        ) {
            "Refund replay operation type mismatch"
        }

        check(
            operation.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Refund replay subject type mismatch"
        }

        check(
            operation.subjectId ==
                expectedTransactionId
        ) {
            "Refund replay subject identity mismatch"
        }

        check(
            operation.transactionId ==
                expectedTransactionId
        ) {
            "Refund replay transaction identity mismatch"
        }

        val payload =
            RefundPayloadCodec.decode(
                operation.intentPayload
            )

        check(
            payload.schemaVersion ==
                operation.commandSchemaVersion
        ) {
            "Refund replay payload version mismatch"
        }

        val event =
            transactionDao
                .getRefundEventByOperationId(
                    operation.id
                )
                ?: throw IllegalStateException(
                    "Refund replay event is missing"
                )

        check(
            event.transactionId ==
                expectedTransactionId
        ) {
            "Refund replay event transaction mismatch"
        }

        check(
            event.operationId ==
                operation.id
        ) {
            "Refund replay event operation mismatch"
        }

        check(
            event.refundAmountMinor ==
                payload.refundAmountMinor
        ) {
            "Refund replay event amount mismatch"
        }

        check(
            event.actorUserId ==
                operation.actorUserId
        ) {
            "Refund replay actor mismatch"
        }

        check(
            event.occurredAt ==
                operation.occurredAt
        ) {
            "Refund replay timestamp mismatch"
        }

        check(
            event.reason ==
                payload.reason
        ) {
            "Refund replay reason mismatch"
        }

        check(
            operation.resultReferenceType ==
                "REFUND_EVENT"
        ) {
            "Refund replay result type mismatch"
        }

        check(
            operation.resultReferenceId ==
                event.id
        ) {
            "Refund replay result reference mismatch"
        }

        val transaction =
            transactionDao.getTransactionById(
                expectedTransactionId
            ) ?: throw IllegalStateException(
                "Refund replay transaction is missing"
            )


        /*
         * BOARDLOCK Credit Return B2-B replay.
         *
         * Legacy Refund replay below intentionally remains unchanged for
         * B1 zero-repayment Credit returns and every non-B2 Refund.
         *
         * B2 is different because one RefundEvent can legitimately
         * materialize multiple REFUND_OUTFLOW settlements:
         *
         *   unpaid Credit reduction
         *   + zero or more customer-paid funding portions.
         *
         * Replay must reconstruct the same authoritative B2-A plan and
         * verify persisted evidence. It must never create new evidence.
         */
        val replayIsCreditDestination =
            payload.destinationCategory ==
                PaymentCategory.CREDIT &&
                payload.destinationChannel ==
                    PaymentChannel.CREDIT_ACCOUNT

        val replayIsCouponDestination =
            payload.destinationCategory ==
                PaymentCategory.COUPON &&
                payload.destinationChannel ==
                    PaymentChannel.COUPON_WALLET

        val replayIsCashDestination =
            payload.destinationCategory ==
                PaymentCategory.CASH &&
                payload.destinationChannel ==
                    PaymentChannel.CASH_TENDER

        val replayIsOnlineDestination =
            payload.destinationCategory ==
                PaymentCategory.ONLINE &&
                (
                    payload.destinationChannel ==
                        PaymentChannel.MANUAL_QR ||
                        payload.destinationChannel ==
                            PaymentChannel.SOFTPOS
                )

        val replayIsCreditReturnB2Destination =
            replayIsCreditDestination ||
                replayIsCashDestination ||
                replayIsOnlineDestination ||
                replayIsCouponDestination

        val replayAllocations =
            if (
                replayIsCreditReturnB2Destination
            ) {
                ledgerDao
                    .getCreditRepaymentAllocationsForTransaction(
                        expectedTransactionId
                    )
            } else {
                emptyList()
            }

        if (
            replayAllocations.isNotEmpty() &&
            replayIsCreditReturnB2Destination
        ) {

            val replayRecord =
                transactionDao
                    .getFinancialRecordById(
                        expectedTransactionId
                    )
                    ?: throw IllegalStateException(
                        "Credit Return B2 replay financial record is missing"
                    )

            val replayPlan =
                buildCreditReturnB2Plan(
                    transaction,
                    replayRecord,
                    replayAllocations,
                    payload.refundAmountMinor,
                    operation.id
                )

            check(
                replayPlan.originalTransactionId ==
                    expectedTransactionId
            ) {
                "Credit Return B2 replay plan transaction mismatch"
            }

            check(
                replayPlan.requestedReturnAmountMinor ==
                    payload.refundAmountMinor
            ) {
                "Credit Return B2 replay plan amount mismatch"
            }

            val settlements =
                transactionDao
                    .getSettlementsForOperation(
                        operation.id
                    )

            val expectedPaidSettlementCount =
                if (replayIsCreditDestination) {
                    replayPlan.paidValuePortions.size
                } else if (
                    replayPlan.paidValueRefundMinor > 0L
                ) {
                    1
                } else {
                    0
                }

            val expectedSettlementCount =
                (
                    if (
                        replayPlan.creditReductionMinor >
                            0L
                    ) {
                        1
                    } else {
                        0
                    }
                ) +
                    expectedPaidSettlementCount

            check(
                settlements.size ==
                    expectedSettlementCount
            ) {
                "Credit Return B2 replay settlement count mismatch"
            }

            check(
                settlements.all {
                    it.entryPurpose ==
                        EntryPurpose.REFUND_OUTFLOW &&
                    it.subjectType ==
                        SubjectType.TRANSACTION &&
                    it.subjectId ==
                        expectedTransactionId &&
                    it.transactionId ==
                        expectedTransactionId &&
                    it.amountSignedMinor <
                        0L &&
                    it.currencyCode ==
                        transaction.currencyCode &&
                    it.actorUserId ==
                        event.actorUserId &&
                    it.occurredAt ==
                        event.occurredAt
                }
            ) {
                "Credit Return B2 replay settlement identity mismatch"
            }

            if (
                replayPlan.creditReductionMinor >
                    0L
            ) {
                val creditSettlementId =
                    RefundIdFactory.creditReturnB2SettlementId(
                        operation.id,
                        "credit",
                        0
                    )

                val creditSettlement =
                    settlements.singleOrNull {
                        it.id ==
                            creditSettlementId
                    } ?: throw IllegalStateException(
                        "Credit Return B2 replay Credit settlement is missing"
                    )

                check(
                    creditSettlement.category ==
                        PaymentCategory.CREDIT &&
                    creditSettlement.channel ==
                        PaymentChannel.CREDIT_ACCOUNT &&
                    creditSettlement.amountSignedMinor ==
                        Math.negateExact(
                            replayPlan
                                .creditReductionMinor
                        )
                ) {
                    "Credit Return B2 replay Credit settlement mismatch"
                }
            }

            if (replayIsCreditDestination) {
                replayPlan
                    .paidValuePortions
                    .forEachIndexed {
                            index,
                            portion ->

                        val settlementId =
                            RefundIdFactory.creditReturnB2SettlementId(
                                operation.id,
                                "paid",
                                index
                            )

                        val paidSettlement =
                            settlements.singleOrNull {
                                it.id ==
                                    settlementId
                            } ?: throw IllegalStateException(
                                "Credit Return B2 replay paid-value settlement is missing"
                            )

                        check(
                            paidSettlement.category ==
                                portion.paymentCategory &&
                            paidSettlement.channel ==
                                portion.paymentChannel &&
                            paidSettlement.amountSignedMinor ==
                                Math.negateExact(
                                    portion.amountMinor
                                )
                        ) {
                            "Credit Return B2 replay paid-value settlement mismatch"
                        }
                    }
            } else if (
                replayPlan.paidValueRefundMinor > 0L
            ) {
                val paidSettlementId =
                    RefundIdFactory.creditReturnB2SettlementId(
                        operation.id,
                        "paid",
                        0
                    )

                val paidSettlement =
                    settlements.singleOrNull {
                        it.id ==
                            paidSettlementId
                    } ?: throw IllegalStateException(
                        "Credit Return B2 replay selected-destination paid-value settlement is missing"
                    )

                check(
                    paidSettlement.category ==
                        payload.destinationCategory &&
                    paidSettlement.channel ==
                        payload.destinationChannel &&
                    paidSettlement.amountSignedMinor ==
                        Math.negateExact(
                            replayPlan
                                .paidValueRefundMinor
                        )
                ) {
                    "Credit Return B2 replay selected-destination paid-value settlement mismatch"
                }
            }

            val replayOutflowAmount =
                settlements.fold(0L) {
                        total,
                        settlement ->

                    Math.addExact(
                        total,
                        Math.negateExact(
                            settlement
                                .amountSignedMinor
                        )
                    )
                }

            check(
                replayOutflowAmount ==
                    payload.refundAmountMinor
            ) {
                "Credit Return B2 replay outflow does not reconcile with RefundEvent"
            }

            val customerId =
                requireNotNull(
                    transaction.customerId
                ) {
                    "Credit Return B2 replay customer is missing"
                }

            val operationCreditEvents =
                ledgerDao
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            operation.id
                    }

            if (
                replayPlan.creditReductionMinor >
                    0L
            ) {
                check(
                    operationCreditEvents.size ==
                        1
                ) {
                    "Credit Return B2 replay Credit evidence count mismatch"
                }

                val creditEvent =
                    operationCreditEvents.single()

                check(
                    creditEvent.id ==
                        RefundIdFactory.creditRefundAdjustmentId(
                            operation.id
                        ) &&
                    creditEvent.customerId ==
                        customerId &&
                    creditEvent.type ==
                        com.dailymoneyregister.core.model.enums
                            .CreditLedgerType
                            .ADJUSTMENT &&
                    creditEvent.transactionId ==
                        expectedTransactionId &&
                    creditEvent.repaymentId ==
                        null &&
                    creditEvent.currencyCode ==
                        transaction.currencyCode &&
                    creditEvent.amountSignedMinor ==
                        Math.negateExact(
                            replayPlan
                                .creditReductionMinor
                        ) &&
                    creditEvent.occurredAt ==
                        operation.occurredAt
                ) {
                    "Credit Return B2 replay Credit adjustment mismatch"
                }
            } else {
                check(
                    operationCreditEvents.isEmpty()
                ) {
                    "Credit Return B2 replay unexpectedly contains Credit adjustment evidence"
                }
            }

            val couponLedger =
                ledgerDao
                    .getCouponEventsForOperation(
                        operation.id
                    )

            if (replayIsCouponDestination) {
                check(
                    replayPlan.paidValueRefundMinor > 0L
                ) {
                    "Credit Return B2 Coupon destination has no paid value to refund"
                }

                check(couponLedger.size == 1) {
                    "Credit Return B2 replay Coupon destination evidence count mismatch"
                }

                val couponEvent =
                    couponLedger.single()

                check(
                    couponEvent.id ==
                        RefundIdFactory.couponRefundEvidenceId(
                            operation.id
                        ) &&
                    couponEvent.type ==
                        com.dailymoneyregister.core.model.enums
                            .CouponLedgerType
                            .REFUND_CREDIT &&
                    couponEvent.transactionId ==
                        expectedTransactionId &&
                    couponEvent.currencyCode ==
                        transaction.currencyCode &&
                    couponEvent.amountSignedMinor ==
                        replayPlan.paidValueRefundMinor &&
                    couponEvent.occurredAt ==
                        operation.occurredAt
                ) {
                    "Credit Return B2 replay Coupon destination evidence mismatch"
                }
            } else if (replayIsCreditDestination) {
                val expectedCouponPortions =
                    replayPlan
                        .paidValuePortions
                        .mapIndexedNotNull {
                                index,
                                portion ->

                            if (
                                portion.paymentCategory ==
                                    PaymentCategory.COUPON
                            ) {
                                Pair(
                                    index,
                                    portion
                                )
                            } else {
                                null
                            }
                        }

                check(
                    couponLedger.size ==
                        expectedCouponPortions.size
                ) {
                    "Credit Return B2 replay Coupon evidence count mismatch"
                }

                if (
                    expectedCouponPortions.isNotEmpty()
                ) {
                    val wallet =
                        checkNotNull(
                            ledgerDao.getCouponWallet(
                                customerId,
                                transaction.currencyCode
                            )
                        ) {
                            "Credit Return B2 replay Coupon wallet is missing"
                        }

                    check(wallet.isActive) {
                        "Credit Return B2 replay Coupon wallet is inactive"
                    }

                    expectedCouponPortions
                        .forEach {
                                indexedPortion ->

                            val index =
                                indexedPortion.first

                            val portion =
                                indexedPortion.second

                            val couponEventId =
                                RefundIdFactory.creditReturnB2CouponEventId(
                                    operation.id,
                                    index
                                )

                            val couponEvent =
                                couponLedger
                                    .singleOrNull {
                                        it.id ==
                                            couponEventId
                                    }
                                    ?: throw IllegalStateException(
                                        "Credit Return B2 replay Coupon compensation is missing"
                                    )

                            check(
                                couponEvent.walletId ==
                                    wallet.id &&
                                couponEvent.type ==
                                    com.dailymoneyregister.core.model.enums
                                        .CouponLedgerType
                                        .REFUND_CREDIT &&
                                couponEvent.transactionId ==
                                    expectedTransactionId &&
                                couponEvent.currencyCode ==
                                    transaction.currencyCode &&
                                couponEvent.amountSignedMinor ==
                                    portion.amountMinor &&
                                couponEvent.occurredAt ==
                                    operation.occurredAt
                            ) {
                                "Credit Return B2 replay Coupon compensation mismatch"
                            }
                        }
                }
            } else {
                check(couponLedger.isEmpty()) {
                    "Credit Return B2 selected Cash/Online destination unexpectedly created Coupon evidence"
                }
            }

            val replayDestinationCouponEvent =
                if (replayIsCouponDestination) {
                    couponLedger.single()
                } else {
                    null
                }

            return RefundExecutionResult(
                transactionId =
                    expectedTransactionId,
                refundEventId =
                    event.id,
                refundAmountMinor =
                    payload.refundAmountMinor,
                couponWalletId =
                    replayDestinationCouponEvent?.walletId,
                couponBalanceAfterMinor =
                    replayDestinationCouponEvent?.let {
                        ledgerDao.getCouponBalanceAfterEvent(
                            it.walletId,
                            it.id
                        )
                    }
            )
        }

        val originalSaleSettlements =
            transactionDao
                .getSettlementsForTransaction(
                    expectedTransactionId
                )

        val originalSaleFunding =
            originalSaleSettlements.filter {
                it.entryPurpose == EntryPurpose.SALE_FUNDING &&
                    it.amountSignedMinor > 0L
            }

        val replayIsUnpaidCreditReturn =
            transaction.customerId != null &&
                originalSaleFunding.size == 1 &&
                originalSaleFunding.single().category == PaymentCategory.CREDIT &&
                originalSaleFunding.single().channel == PaymentChannel.CREDIT_ACCOUNT &&
                ledgerDao
                    .getCreditRepaymentAllocationsForTransaction(
                        expectedTransactionId
                    )
                    .isEmpty()

        if (replayIsUnpaidCreditReturn) {
            val settlements =
                transactionDao
                    .getSettlementsForOperation(operation.id)

            check(settlements.isEmpty()) {
                "Unpaid Credit replay must not contain settlement evidence"
            }

            check(
                ledgerDao
                    .getCouponEventsForOperation(operation.id)
                    .isEmpty()
            ) {
                "Unpaid Credit replay must not contain Coupon evidence"
            }

            val customerId = requireNotNull(transaction.customerId)
            val creditAdjustments =
                ledgerDao
                    .getCreditLedgerForCustomer(customerId)
                    .filter { it.operationId == operation.id }

            check(creditAdjustments.size == 1) {
                "Unpaid Credit replay adjustment count mismatch"
            }

            val adjustment = creditAdjustments.single()
            check(
                adjustment.type ==
                    com.dailymoneyregister.core.model.enums.CreditLedgerType.ADJUSTMENT &&
                    adjustment.amountSignedMinor ==
                        Math.negateExact(payload.refundAmountMinor) &&
                    adjustment.currencyCode == transaction.currencyCode &&
                    adjustment.transactionId == expectedTransactionId &&
                    adjustment.repaymentId == null &&
                    adjustment.occurredAt == operation.occurredAt
            ) {
                "Unpaid Credit replay adjustment mismatch"
            }

            return RefundExecutionResult(
                transactionId = expectedTransactionId,
                refundEventId = event.id,
                refundAmountMinor = payload.refundAmountMinor,
                couponWalletId = null,
                couponBalanceAfterMinor = null
            )
        }

        val settlements =
            transactionDao
                .getSettlementsForOperation(
                    operation.id
                )

        check(settlements.size == 1) {
            "Refund replay settlement count mismatch"
        }

        val outflow =
            settlements.single()

        check(
            outflow.entryPurpose ==
                EntryPurpose.REFUND_OUTFLOW
        ) {
            "Refund replay settlement purpose mismatch"
        }

        check(
            outflow.subjectType ==
                SubjectType.TRANSACTION
        ) {
            "Refund replay settlement subject type mismatch"
        }

        check(
            outflow.subjectId ==
                expectedTransactionId
        ) {
            "Refund replay settlement subject mismatch"
        }

        check(
            outflow.transactionId ==
                expectedTransactionId
        ) {
            "Refund replay settlement transaction mismatch"
        }

        check(
            outflow.amountSignedMinor < 0L
        ) {
            "Refund replay settlement amount is not negative"
        }

        val replayAmount =
            Math.negateExact(
                outflow.amountSignedMinor
            )

        check(
            replayAmount ==
                payload.refundAmountMinor
        ) {
            "Refund replay settlement amount mismatch"
        }

        check(
            outflow.category ==
                payload.destinationCategory
        ) {
            "Refund replay destination category mismatch"
        }

        check(
            outflow.channel ==
                payload.destinationChannel
        ) {
            "Refund replay destination channel mismatch"
        }

        check(
            outflow.currencyCode ==
                transaction.currencyCode
        ) {
            "Refund replay currency mismatch"
        }

        check(
            outflow.actorUserId ==
                event.actorUserId
        ) {
            "Refund replay settlement actor mismatch"
        }

        check(
            outflow.occurredAt ==
                event.occurredAt
        ) {
            "Refund replay settlement timestamp mismatch"
        }

        val couponLedger = ledgerDao.getCouponEventsForOperation(operation.id)
        if (payload.destinationCategory == PaymentCategory.COUPON) {
            check(couponLedger.size == 1)
            val event = couponLedger.single()
            check(event.id == RefundIdFactory.couponRefundEvidenceId(operation.id) &&
                event.type == com.dailymoneyregister.core.model.enums.CouponLedgerType.REFUND_CREDIT &&
                event.transactionId == expectedTransactionId && event.currencyCode == transaction.currencyCode &&
                event.amountSignedMinor == replayAmount && event.occurredAt == operation.occurredAt)
            val replayCustomerId =
                payload.couponDestination
                    ?.customerId
                    ?: requireNotNull(
                        transaction.customerId
                    )

            val wallet =
                checkNotNull(
                    ledgerDao.getCouponWallet(
                        replayCustomerId,
                        transaction.currencyCode
                    )
                )

            check(wallet.id == event.walletId)
        } else check(couponLedger.isEmpty())

        val creditCustomerId =
            transaction.customerId

        val operationCreditEvents =
            if (creditCustomerId == null) {
                emptyList()
            } else {
                ledgerDao
                    .getCreditLedgerForCustomer(
                        creditCustomerId
                    )
                    .filter {
                        it.operationId ==
                            operation.id
                    }
            }

        if (
            payload.destinationCategory ==
                PaymentCategory.CREDIT
        ) {
            val customerId =
                requireNotNull(
                    creditCustomerId
                ) {
                    "Credit Return replay customer is missing"
                }

            check(
                payload.destinationChannel ==
                    PaymentChannel.CREDIT_ACCOUNT
            ) {
                "Credit Return replay channel mismatch"
            }

            check(
                operationCreditEvents.size == 1
            ) {
                "Credit Return replay Credit evidence count mismatch"
            }

            val creditEvent =
                operationCreditEvents.single()

            check(
                creditEvent.id ==
                    RefundIdFactory.creditRefundAdjustmentId(
                        operation.id
                    ) &&
                    creditEvent.customerId ==
                    customerId &&
                    creditEvent.type ==
                    com.dailymoneyregister.core.model.enums
                        .CreditLedgerType
                        .ADJUSTMENT &&
                    creditEvent.transactionId ==
                    expectedTransactionId &&
                    creditEvent.repaymentId ==
                    null &&
                    creditEvent.currencyCode ==
                    transaction.currencyCode &&
                    creditEvent.amountSignedMinor ==
                    Math.negateExact(
                        replayAmount
                    ) &&
                    creditEvent.occurredAt ==
                    operation.occurredAt
            ) {
                "Credit Return replay Credit adjustment mismatch"
            }
        } else {
            check(
                operationCreditEvents.isEmpty()
            ) {
                "Non-Credit Refund unexpectedly created Credit evidence"
            }
        }

        return RefundExecutionResult(
            transactionId =
                expectedTransactionId,
            refundEventId =
                event.id,
            refundAmountMinor = replayAmount,
            couponWalletId = couponLedger.singleOrNull()?.walletId,
            couponBalanceAfterMinor = couponLedger.singleOrNull()?.let {
                ledgerDao.getCouponBalanceAfterEvent(it.walletId, it.id)
            }
        )
    }

    /**
     * Recover only already-admitted PENDING Refund operations.
     *
     * Permission is deliberately NOT checked again because mutable current
     * permissions must not redefine historical durable intent.
     */
    suspend fun recoverPendingRefunds():
        List<RefundExecutionResult> {

        val operationDao =
            database.operationDao()

        val recovered =
            mutableListOf<RefundExecutionResult>()

        val pendingOperations =
            commandExecutor
                .getPendingOperations()
                .filter {
                    it.operationType ==
                        OperationType.RECORD_REFUND
                }

        for (candidate in pendingOperations) {

            val current =
                operationDao.getOperationById(
                    candidate.id
                ) ?: throw IllegalStateException(
                    "Pending Refund operation unavailable during recovery"
                )

            if (
                current.status ==
                OperationStatus.SUCCEEDED
            ) {
                recovered +=
                    resolveRefundReplay(
                        operation =
                            current,
                        expectedTransactionId =
                            current.subjectId
                    )

                continue
            }

            check(
                current.status ==
                    OperationStatus.PENDING
            ) {
                "Refund operation is no longer pending"
            }

            val command =
                try {
                    admissionCoordinator.decodePersistedRefundCommand(
                        current
                    )
                } catch (error: RuntimeException) {

                    val reconciled =
                        reconcilePendingRefund(
                            current,
                            "Invalid durable Refund intent: " +
                                (error.message ?: "decode failure")
                        )

                    throw IllegalStateException(
                        (reconciled as?
                            CommandResult.Conflict)
                            ?.message
                            ?: "Refund requires financial reconciliation",
                        error
                    )
                }

            if (
                command.financialPrecondition ==
                null
            ) {
                val reconciled =
                    reconcilePendingRefund(
                        current,
                        "PENDING Refund lacks a financial-state precondition"
                    )

                throw IllegalStateException(
                    (reconciled as?
                        CommandResult.Conflict)
                        ?.message
                        ?: "Refund requires financial reconciliation"
                )
            }

            when (
                val result =
                    executeAdmittedRefund(command)
            ) {

                is CommandResult.Success -> {
                    recovered += result.data
                }

                is CommandResult.Conflict -> {
                    throw IllegalStateException(
                        result.message
                    )
                }

                is CommandResult.PermissionDenied -> {
                    throw IllegalStateException(
                        "Admitted Refund recovery unexpectedly requested permission"
                    )
                }

                is CommandResult.Error -> {
                    throw result.exception
                }
            }
        }

        return recovered
    }
}
