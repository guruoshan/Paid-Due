package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType

data class ValidatedPaymentMethodChangeFinancialState(
    val currentCategory: PaymentCategory,
    val currentChannel: PaymentChannel,
    val reclassificationAmountMinor: Long
)

class PaymentMethodChangeAfterVoidException(transactionId: String) :
    IllegalStateException("Voided transaction '$transactionId' cannot change payment method")

class PaymentMethodChangeNothingToReclassifyException(transactionId: String) :
    IllegalStateException("Transaction '$transactionId' has no positive value to reclassify")

class PaymentMethodChangeCreditRepaymentExistsException(transactionId: String) :
    IllegalStateException(
        "Transaction '$transactionId' has credit repayment allocations and cannot change payment method safely"
    )

class PaymentMethodChangeUnsupportedCreditException(transactionId: String) :
    IllegalStateException("Credit transaction '$transactionId' cannot change payment method safely")

class PaymentMethodChangeUnsupportedCouponException(transactionId: String) :
    IllegalStateException("Coupon transaction '$transactionId' cannot change payment method safely")

class PaymentMethodChangeAmbiguousSettlementException(transactionId: String) :
    IllegalStateException(
        "Transaction '$transactionId' has ambiguous payment-method-change financial evidence"
    )

class PaymentMethodChangeNoOpException(transactionId: String) :
    IllegalStateException("Transaction '$transactionId' already uses the requested payment identity")

class PaymentMethodChangeOrderingException(transactionId: String, occurredAt: Long, latestOccurredAt: Long) :
    IllegalStateException(
        "Payment Method Change for transaction '$transactionId' has occurredAt=$occurredAt " +
            "which is not strictly later than the latest existing change at $latestOccurredAt"
    )

/**
 * Authoritative integrity gate for payment-method reclassification.
 *
 * Initial safe production scope intentionally allows only:
 * - SALE transactions funded by one CASH or ONLINE identity
 * - zero or more previously reconciled payment-method changes
 *
 * It intentionally fails closed when Refund, Void, Amount Correction,
 * Credit repayment allocation, Credit funding or Coupon funding exists.
 *
 * This keeps the first writer narrow while preserving an append-only model
 * that can later be extended to more lifecycle combinations.
 */
object PaymentMethodChangeFinancialIntegrity {

    /**
     * New lifecycle events must append after the latest persisted change.
     * Equal timestamps fail closed because the generated event id is not an
     * admission-order contract.
     */
    fun requireChronologicalAppend(
        record: TransactionFinancialRecord,
        occurredAt: Long
    ) {
        val latest = record.paymentMethodChanges.maxWithOrNull(
            compareBy<PaymentMethodChangeEventEntity> { it.occurredAt }
                .thenBy { it.id }
        ) ?: return

        if (occurredAt <= latest.occurredAt) {
            throw PaymentMethodChangeOrderingException(
                transactionId = record.transaction.id,
                occurredAt = occurredAt,
                latestOccurredAt = latest.occurredAt
            )
        }
    }

    fun fingerprint(
        transactionId: String,
        record: TransactionFinancialRecord?,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): String {
        val underlyingState =
            VoidFinancialIntegrity.fingerprint(
                transactionId = transactionId,
                record = record,
                creditAllocations = creditAllocations
            )

        val channelIdentity = buildString {
            record?.paymentMethodChanges
                ?.sortedWith(compareBy({ it.occurredAt }, { it.id }))
                ?.forEach { change ->
                    append(change.id)
                        .append(':')
                        .append(change.previousChannel?.name ?: "<null>")
                        .append('>')
                        .append(change.newChannel?.name ?: "<null>")
                        .append('|')
                }
        }

        return CommandFingerprint.sha256(
            "payment-method-change-financial-state-v1|$underlyingState|$channelIdentity"
        )
    }

    fun validate(
        record: TransactionFinancialRecord,
        creditAllocations: List<CreditRepaymentAllocationEntity>
    ): ValidatedPaymentMethodChangeFinancialState {
        val transaction = record.transaction

        if (transaction.type != TransactionType.SALE) {
            throw PaymentMethodChangeUnsupportedCouponException(transaction.id)
        }

        if (record.voidEvents.isNotEmpty()) {
            throw PaymentMethodChangeAfterVoidException(transaction.id)
        }

        if (creditAllocations.isNotEmpty()) {
            throw PaymentMethodChangeCreditRepaymentExistsException(transaction.id)
        }

        /*
         * Initial writer scope is deliberately conservative. The event and
         * settlement model is future-compatible with these combinations,
         * but we do not silently define cross-lifecycle ordering today.
         */
        if (record.amountCorrections.isNotEmpty() || record.refundEvents.isNotEmpty()) {
            throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
        }

        val changes =
            record.paymentMethodChanges.sortedWith(
                compareBy({ it.occurredAt }, { it.id })
            )

        val changeOperationIds = changes.map { it.operationId }
        if (changeOperationIds.size != changeOperationIds.distinct().size) {
            throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
        }

        val changeOperationSet = changeOperationIds.toSet()

        record.settlements
            .filter { it.entryPurpose == EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION }
            .forEach { settlement ->
                if (settlement.operationId !in changeOperationSet) {
                    throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
                }
            }

        /*
         * Strip already-proven reclassification pairs and reuse the shared
         * Void validator to prove the underlying original sale funding.
         */
        val baseRecord =
            record.copy(
                paymentMethodChanges = emptyList(),
                settlements = record.settlements.filter {
                    it.entryPurpose != EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION
                }
            )

        val base =
            try {
                VoidFinancialIntegrity.validate(
                    record = baseRecord,
                    creditAllocations = creditAllocations
                )
            } catch (_: VoidAlreadyExistsException) {
                throw PaymentMethodChangeAfterVoidException(transaction.id)
            } catch (_: VoidNothingReversibleException) {
                throw PaymentMethodChangeNothingToReclassifyException(transaction.id)
            } catch (_: VoidCreditRepaymentExistsException) {
                throw PaymentMethodChangeCreditRepaymentExistsException(transaction.id)
            } catch (_: VoidUnsupportedCreditException) {
                throw PaymentMethodChangeUnsupportedCreditException(transaction.id)
            } catch (_: VoidUnsupportedCouponException) {
                throw PaymentMethodChangeUnsupportedCouponException(transaction.id)
            } catch (_: VoidAmbiguousSettlementException) {
                throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            }

        requireSupportedIdentity(
            transactionId = transaction.id,
            category = base.fundingCategory,
            channel = base.fundingChannel
        )

        val amount = base.reversibleAmountMinor
        if (amount <= 0L) {
            throw PaymentMethodChangeNothingToReclassifyException(transaction.id)
        }

        var currentCategory = base.fundingCategory
        var currentChannel = base.fundingChannel
        val settlementsByOperation = record.settlements.groupBy { it.operationId }

        changes.forEach { change ->
            val previousChannel =
                change.previousChannel
                    ?: throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            val newChannel =
                change.newChannel
                    ?: throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)

            requireSupportedIdentity(transaction.id, change.previousMethod, previousChannel)
            requireSupportedIdentity(transaction.id, change.newMethod, newChannel)

            if (
                change.previousMethod != currentCategory ||
                previousChannel != currentChannel
            ) {
                throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            }

            if (change.newMethod == currentCategory && newChannel == currentChannel) {
                throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            }

            val matches = settlementsByOperation[change.operationId].orEmpty()
            if (matches.size != 2) {
                throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            }

            matches.forEach { settlement ->
                requireReclassificationSettlement(
                    transactionId = transaction.id,
                    currencyCode = transaction.currencyCode,
                    changeActorUserId = change.actorUserId,
                    changeOccurredAt = change.occurredAt,
                    settlement = settlement
                )
            }

            val negativeAmount = try {
                Math.negateExact(amount)
            } catch (_: ArithmeticException) {
                throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            }

            val debit = matches.singleOrNull {
                it.amountSignedMinor == negativeAmount &&
                    it.category == currentCategory &&
                    it.channel == currentChannel
            }

            val credit = matches.singleOrNull {
                it.amountSignedMinor == amount &&
                    it.category == change.newMethod &&
                    it.channel == newChannel
            }

            if (debit == null || credit == null) {
                throw PaymentMethodChangeAmbiguousSettlementException(transaction.id)
            }

            currentCategory = change.newMethod
            currentChannel = newChannel
        }

        return ValidatedPaymentMethodChangeFinancialState(
            currentCategory = currentCategory,
            currentChannel = currentChannel,
            reclassificationAmountMinor = amount
        )
    }

    private fun requireSupportedIdentity(
        transactionId: String,
        category: PaymentCategory,
        channel: PaymentChannel
    ) {
        when (category) {
            PaymentCategory.CASH -> {
                if (channel != PaymentChannel.CASH_TENDER) {
                    throw PaymentMethodChangeAmbiguousSettlementException(transactionId)
                }
            }

            PaymentCategory.ONLINE -> {
                if (
                    channel != PaymentChannel.MANUAL_QR &&
                    channel != PaymentChannel.SOFTPOS
                ) {
                    throw PaymentMethodChangeAmbiguousSettlementException(transactionId)
                }
            }

            PaymentCategory.CREDIT -> {
                throw PaymentMethodChangeUnsupportedCreditException(transactionId)
            }

            PaymentCategory.COUPON -> {
                throw PaymentMethodChangeUnsupportedCouponException(transactionId)
            }
        }
    }

    private fun requireReclassificationSettlement(
        transactionId: String,
        currencyCode: String,
        changeActorUserId: String,
        changeOccurredAt: Long,
        settlement: SettlementEntryEntity
    ) {
        if (
            settlement.subjectType != SubjectType.TRANSACTION ||
            settlement.subjectId != transactionId ||
            settlement.transactionId != transactionId ||
            settlement.entryPurpose != EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION ||
            settlement.currencyCode != currencyCode ||
            settlement.confirmationType != ConfirmationType.ADMIN_CONFIRMED ||
            settlement.actorUserId != changeActorUserId ||
            settlement.occurredAt != changeOccurredAt
        ) {
            throw PaymentMethodChangeAmbiguousSettlementException(transactionId)
        }
    }
}
