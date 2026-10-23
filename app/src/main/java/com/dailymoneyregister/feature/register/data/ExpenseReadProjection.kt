package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.ExpenseEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.financial.FinancialFundingPart
import com.dailymoneyregister.core.model.money.MoneyMinorUnits

/**
 * BOARDLOCK Expense Code 2.
 *
 * Read-only cross-screen projection of the authoritative ExpenseEntity.
 *
 * This class NEVER owns financial state and NEVER writes financial state.
 * ExpenseEntity remains the single persisted Expense source of truth.
 *
 * Recent, History, Expense Details and communication surfaces may consume
 * this projection instead of manufacturing TransactionEntity rows.
 */
data class ExpenseReadProjection(
    val expenseId: String,
    val operationId: String,
    val businessId: String,
    val branchId: String,
    val cashShiftId: String?,
    val actorUserId: String,
    val amount: MoneyMinorUnits,
    val currencyCode: String,
    val category: String,
    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,
    val payeeName: String?,
    val payeePhone: String?,
    val reference: String?,
    val note: String?,
    val occurredAt: Long,
    val createdAt: Long
) {

    init {
        require(amount.amount > 0L) {
            "Expense projection amount must be positive before display sign is applied."
        }

        require(
            paymentCategory == PaymentCategory.CASH ||
                paymentCategory == PaymentCategory.ONLINE
        ) {
            "Expense projection supports Cash or Online only."
        }
    }

    /**
     * Expense is an outflow.
     *
     * Stored ExpenseEntity amount remains positive.
     * Presentation/aggregation projection applies the negative sign.
     */
    val signedEffect: MoneyMinorUnits
        get() =
            MoneyMinorUnits(
                Math.negateExact(amount.amount)
            )

    /**
     * External funding/outflow identity used by Recent.
     *
     * Do not create a SettlementEntry or any other financial row here.
     */
    val fundingBreakdown: List<FinancialFundingPart>
        get() =
            listOf(
                FinancialFundingPart(
                    category = paymentCategory,
                    channel = paymentChannel,
                    amountMinor = amount.amount
                )
            )

    val displayReference: String
        get() =
            reference
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: expenseId
}

/**
 * Sole mapper from persisted Expense evidence into cross-screen presentation.
 *
 * No database writes.
 * No TransactionEntity.
 * No balance state.
 */
object ExpenseReadProjectionResolver {

    fun resolve(
        expense: ExpenseEntity,
        settlement: SettlementEntryEntity,
        events: List<ExpenseEventEntity> = emptyList()
    ): ExpenseReadProjection {

        check(
            settlement.operationId == expense.operationId ||
                events.any { it.operationId == settlement.operationId }
        ) {
            "Expense settlement operation mismatch for ${expense.id}"
        }

        check(settlement.subjectType == SubjectType.EXPENSE) {
            "Expense settlement subject type mismatch for ${expense.id}"
        }
        check(settlement.subjectId == expense.id) {
            "Expense settlement subject ID mismatch for ${expense.id}"
        }
        check(
            settlement.entryPurpose == EntryPurpose.EXPENSE_OUTFLOW ||
                (events.isNotEmpty() && settlement.entryPurpose in setOf(
                    EntryPurpose.EXPENSE_CORRECTION,
                    EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION,
                    EntryPurpose.VOID_REVERSAL
                ))
        ) {
            "Expense settlement purpose mismatch for ${expense.id}"
        }
        check(settlement.transactionId == null) {
            "Expense settlement must not reference TransactionEntity for ${expense.id}"
        }
        check(settlement.category.name == expense.paymentCategory) {
            "Expense settlement payment category does not match the current expense snapshot"
        }

        if (events.isEmpty()) {
            val expectedOutflow = Math.negateExact(expense.amountMinor)
            check(settlement.amountSignedMinor == expectedOutflow) {
                "Expense settlement amount mismatch for ${expense.id}"
            }
        } else {
            val event = events.firstOrNull { it.operationId == settlement.operationId }
            if (
                event != null &&
                event.previousAmountMinor != null &&
                event.newAmountMinor != null &&
                settlement.entryPurpose == EntryPurpose.EXPENSE_CORRECTION
            ) {
                val expectedDelta = Math.subtractExact(
                    event.previousAmountMinor,
                    event.newAmountMinor
                )
                check(settlement.amountSignedMinor == expectedDelta) {
                    "Expense correction signed settlement does not match the event delta"
                }
            }
        }

        check(
            settlement.category == PaymentCategory.CASH ||
                settlement.category == PaymentCategory.ONLINE
        ) {
            "Expense '${expense.id}' has unsupported payment category ${settlement.category}"
        }

        check(
            when (settlement.category) {

                PaymentCategory.CASH ->
                    settlement.channel ==
                        PaymentChannel.CASH_TENDER

                PaymentCategory.ONLINE ->
                    settlement.channel ==
                        PaymentChannel.MANUAL_QR ||
                        settlement.channel ==
                            PaymentChannel.SOFTPOS

                PaymentCategory.CREDIT,
                PaymentCategory.COUPON ->
                    false
            }
        ) {
            "Expense settlement payment channel mismatch for ${expense.id}"
        }

        val effectiveAmountMinor = events
            .asSequence()
            .filter { !it.eventType.uppercase().contains("VOID") }
            .sortedWith(compareBy<ExpenseEventEntity>({ it.occurredAt }, { it.id }))
            .mapNotNull { it.newAmountMinor }
            .lastOrNull() ?: expense.amountMinor

        return ExpenseReadProjection(
            expenseId =
                expense.id,

            operationId =
                expense.operationId,

            businessId =
                expense.businessId,

            branchId =
                expense.branchId,

            cashShiftId =
                expense.cashShiftId,

            actorUserId =
                expense.actorUserId,

            amount =
                MoneyMinorUnits(
                    effectiveAmountMinor
                ),

            currencyCode =
                expense.currencyCode,

            category =
                expense.category,

            paymentCategory =
                settlement.category,

            paymentChannel =
                settlement.channel,

            payeeName =
                expense.payeeNameSnapshot
                    .trim()
                    .takeIf { it.isNotEmpty() },

            payeePhone =
                expense.payeePhoneSnapshot
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },

            reference =
                expense.reference
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },

            note =
                expense.note
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },

            occurredAt =
                expense.occurredAt,

            createdAt =
                expense.createdAt
        )
    }
}

