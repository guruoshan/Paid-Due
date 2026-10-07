package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.security.SessionManager

/**
 * BOARDLOCK authoritative Expense read boundary.
 *
 * Read-only decomposition extracted from RegisterRepository.
 *
 * ExpenseEntity remains authoritative Expense identity/details.
 * Persisted SettlementEntry evidence remains authoritative for funding.
 *
 * This component:
 * - performs no financial writes;
 * - owns no editable financial balance;
 * - creates no competing Expense state;
 * - changes no database evidence;
 * - preserves business/branch fail-closed behavior.
 */
internal class ExpenseReadRepository(
    database: DmrDatabase
) {
    private val expenseDao =
        database.expenseDao()

    private val transactionDao =
        database.transactionDao()

    suspend fun getExpensesForPeriod(
        businessId: String,
        branchId: String,
        fromInclusive: Long,
        toExclusive: Long
    ): List<ExpenseEntity> =
        expenseDao.getExpensesForPeriod(
            businessId = businessId,
            branchId = branchId,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive
        )

    suspend fun getExpenseDetails(
        expenseId: String
    ): ExpenseReadProjection? {

        val session =
            SessionManager.currentSession.value
                ?: return null

        val expense =
            expenseDao
                .getExpenseById(expenseId)
                ?: return null

        /*
         * Fail closed.
         *
         * Expense Details may expose only the authenticated business
         * and active branch.
         */
        if (
            expense.businessId != session.business.id ||
            expense.branchId != session.branch.id
        ) {
            return null
        }

        val settlements =
            transactionDao
                .getSettlementsForOperation(
                    expense.operationId
                )

        check(settlements.size == 1) {
            "Expense details settlement count mismatch for ${expense.id}"
        }

        return ExpenseReadProjectionResolver.resolve(
            expense = expense,
            settlement = settlements.single()
        )
    }
}
