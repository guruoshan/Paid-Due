package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.RecentActivityReadModelResolver
import com.dailymoneyregister.core.security.SessionManager

/**
 * BOARDLOCK authoritative Transaction Details read boundary.
 *
 * Read-only decomposition extracted from RegisterRepository.
 *
 * This component:
 * - performs no financial writes;
 * - owns no independent financial state;
 * - calculates no competing balance;
 * - changes no persisted evidence;
 * - preserves active-branch fail-closed behavior;
 * - preserves repayment-allocation integrity checks.
 */
internal class TransactionDetailsReadRepository(
    database: DmrDatabase
) {
    private val transactionDao =
        database.transactionDao()

    private val ledgerDao =
        database.ledgerDao()

    suspend fun getFinancialTransactionById(
        transactionId: String
    ): FinancialTransactionView? =
        transactionDao
            .getFinancialRecordById(
                transactionId
            )
            ?.let(
                FinancialReadModelResolver::resolve
            )

    suspend fun getTransactionItems(
        transactionId: String
    ): List<TransactionItemEntity> =
        transactionDao
            .getItemsForTransaction(
                transactionId
            )

    suspend fun getTransactionDetails(
        transactionId: String
    ): TransactionDetailsReadModel? {

        val session =
            SessionManager.currentSession.value
                ?: return null

        val record =
            transactionDao
                .getFinancialRecordById(
                    transactionId
                )
                ?: return null

        if (
            record.transaction.branchId !=
            session.branch.id
        ) {
            return null
        }

        val financial =
            FinancialReadModelResolver.resolve(
                record
            )

        val activities =
            RecentActivityReadModelResolver.resolve(
                record = record,
                periodStartMillis = Long.MIN_VALUE,
                periodEndMillis = Long.MAX_VALUE
            )

        val items =
            transactionDao
                .getItemsForTransaction(
                    transactionId
                )

        val creditRepaymentAllocations =
            ledgerDao
                .getCreditRepaymentAllocationsForTransaction(
                    transactionId
                )

        val repaymentIds =
            creditRepaymentAllocations
                .map { it.repaymentId }
                .distinct()

        val creditRepayments =
            if (repaymentIds.isEmpty()) {

                emptyList()
            }
            else {

                ledgerDao
                    .getCreditRepaymentsByIds(
                        repaymentIds
                    )
            }

        if (
            creditRepayments
                .map { it.id }
                .toSet() !=
            repaymentIds.toSet()
        ) {
            return null
        }

        val actionEligibility =
            TransactionActionEligibilityResolver.resolve(
                record = record,
                creditAllocations =
                    creditRepaymentAllocations
            )

        return TransactionDetailsReadModel(
            financial = financial,
            activities = activities,
            items = items,
            creditRepaymentAllocations =
                creditRepaymentAllocations,
            creditRepayments =
                creditRepayments,
            amountCorrections =
                record.amountCorrections,
            paymentMethodChanges =
                record.paymentMethodChanges,
            voidEvents =
                record.voidEvents,
            refundEvents =
                record.refundEvents,
            settlements =
                record.settlements,
            actionEligibility =
                actionEligibility
        )
    }
}
