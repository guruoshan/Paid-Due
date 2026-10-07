package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.SessionManager
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Read-only broad History.
 *
 * BOARDLOCK:
 * - TransactionFinancialRecord + FinancialReadModelResolver remain
 *   authoritative for transaction financial meaning.
 * - ExpenseEntity remains authoritative for Expense financial meaning.
 * - ExpenseReadProjection is a read-only projection.
 * - No Expense is copied into TransactionEntity.
 * - No independent balance or lifecycle state is maintained here.
 */
class HistoryRepository @Inject constructor(
    private val database: DmrDatabase
) {

    fun observeHistory():
        Flow<List<HistoryActivityRow>> =
        flow {

            val session =
                SessionManager.currentSession.value
                    ?: throw SecurityException(
                        "History requires an authenticated session"
                    )

            val membership =
                SessionManager.activeMembership
                    ?: throw SecurityException(
                        "History requires active branch membership"
                    )

            if (
                membership.branchId !=
                session.branch.id
            ) {
                throw SecurityException(
                    "History membership does not match active branch"
                )
            }

            PermissionChecker.assertPermitted(
                session.user,
                membership,
                PermissionAction.VIEW_BROAD_HISTORY
            )

            val openedBusinessId =
                session.business.id

            val openedBranchId =
                session.branch.id

            /*
             * Expense History needs a bounded Room query.
             *
             * Use the beginning of the Unix epoch rather than manufacturing
             * a second all-expenses DAO contract. toExclusive remains
             * Long.MAX_VALUE so all persisted historical Expense evidence
             * for this branch participates.
             */
            val expenseFromInclusive =
                0L

            val expenseToExclusive =
                Long.MAX_VALUE

            val transactionFlow =
                database
                    .transactionDao()
                    .observeHistoryFinancialRecords(
                        openedBranchId
                    )

            val expenseFlow =
                database
                    .expenseDao()
                    .observeExpensesForPeriod(
                        businessId =
                            openedBusinessId,
                        branchId =
                            openedBranchId,
                        fromInclusive =
                            expenseFromInclusive,
                        toExclusive =
                            expenseToExclusive
                    )

            emitAll(
                combine(
                    transactionFlow,
                    expenseFlow
                ) { records, expenses ->

                    val liveSession =
                        SessionManager.currentSession.value
                            ?: throw SecurityException(
                                "History session ended"
                            )

                    val liveMembership =
                        SessionManager.activeMembership
                            ?: throw SecurityException(
                                "History membership ended"
                            )

                    if (
                        liveSession.business.id !=
                        openedBusinessId ||
                        liveSession.branch.id !=
                        openedBranchId ||
                        liveMembership.branchId !=
                        openedBranchId
                    ) {
                        throw SecurityException(
                            "History session changed"
                        )
                    }

                    PermissionChecker.assertPermitted(
                        liveSession.user,
                        liveMembership,
                        PermissionAction.VIEW_BROAD_HISTORY
                    )

                    val transactionRows =
                        records.map { record ->

                            if (
                                record.transaction.branchId !=
                                openedBranchId
                            ) {
                                throw IllegalStateException(
                                    "History record escaped active branch"
                                )
                            }

                            val customer =
                                record.customer
                                    ?.takeIf {
                                        it.id ==
                                            record.transaction.customerId &&
                                            it.businessId ==
                                            openedBusinessId
                                    }

                            HistoryActivityRow.Transaction(
                                HistoryTransactionRow(
                                    financial =
                                        FinancialReadModelResolver.resolve(
                                            record
                                        ),
                                    customerName =
                                        customer?.name,
                                    canonicalPhone =
                                        customer?.canonicalPhone
                                )
                            )
                        }

                    val expenseSettlementsByOperation =
                        expenses
                            .map { it.operationId }
                            .distinct()
                            .chunked(HISTORY_READ_BATCH_SIZE)
                            .flatMap { operationIds ->
                                database
                                    .transactionDao()
                                    .getSettlementsForOperations(
                                        operationIds
                                    )
                            }
                            .groupBy { it.operationId }

                    val expenseRows =
                        expenses.map { expense ->

                            if (
                                expense.businessId !=
                                openedBusinessId ||
                                expense.branchId !=
                                openedBranchId
                            ) {
                                throw IllegalStateException(
                                    "Expense History record escaped active scope"
                                )
                            }

                            val settlements =
                                expenseSettlementsByOperation[
                                    expense.operationId
                                ].orEmpty()

                            check(settlements.size == 1) {
                                "Expense History settlement count mismatch for ${expense.id}"
                            }

                            val projection =
                                ExpenseReadProjectionResolver.resolve(
                                    expense = expense,
                                    settlement = settlements.single()
                                )

                            /*
                             * Defense in depth:
                             * the persisted Expense was already scope checked above,
                             * and the resulting projection must preserve that scope.
                             */
                            check(
                                projection.businessId ==
                                    openedBusinessId &&
                                    projection.branchId ==
                                        openedBranchId
                            ) {
                                "Expense History projection escaped active scope"
                            }

                            HistoryActivityRow.Expense(
                                projection
                            )
                        }

                    (
                        transactionRows +
                            expenseRows
                    ).sortedWith(
                        compareByDescending<HistoryActivityRow> {
                            it.occurredAt
                        }.thenByDescending {
                            it.activityKey
                        }
                    )
                }
            )
        }
}

private const val HISTORY_READ_BATCH_SIZE = 900

