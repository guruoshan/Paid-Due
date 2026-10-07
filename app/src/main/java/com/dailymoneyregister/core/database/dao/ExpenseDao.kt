package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.ExpenseEventEntity
import com.dailymoneyregister.core.database.entity.ExpensePayeeEntity
import com.dailymoneyregister.core.database.entity.ExpenseRecurrenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayee(payee: ExpensePayeeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExpense(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExpenseEvent(event: ExpenseEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecurrence(recurrence: ExpenseRecurrenceEntity)

    @Query("SELECT * FROM expense_payees WHERE id = :payeeId LIMIT 1")
    suspend fun getPayeeById(payeeId: String): ExpensePayeeEntity?

    @Query(
        """
        SELECT * FROM expense_payees
        WHERE business_id = :businessId
          AND (
              normalized_name LIKE '%' || :query || '%'
              OR canonical_phone LIKE '%' || :query || '%'
              OR account_reference LIKE '%' || :query || '%'
          )
        ORDER BY name COLLATE NOCASE ASC
        LIMIT 50
        """
    )
    suspend fun searchPayees(
        businessId: String,
        query: String
    ): List<ExpensePayeeEntity>

    @Query("SELECT * FROM expenses WHERE id = :expenseId LIMIT 1")
    suspend fun getExpenseById(expenseId: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE operation_id = :operationId LIMIT 1")
    suspend fun getExpenseByOperationId(operationId: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE operation_id IN (:operationIds)")
    suspend fun getExpensesByOperationIds(
        operationIds: List<String>
    ): List<ExpenseEntity>


    /** Read-only authoritative Expense history for one universal customer/contact. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE business_id = :businessId
          AND branch_id = :branchId
          AND payee_id = :customerId
        ORDER BY occurred_at DESC
        """
    )
    suspend fun getExpensesForCustomer(
        businessId: String,
        branchId: String,
        customerId: String
    ): List<ExpenseEntity>
    @Query(
        """
        SELECT * FROM expenses
        WHERE business_id = :businessId
          AND branch_id = :branchId
          AND occurred_at >= :fromInclusive
          AND occurred_at < :toExclusive
        ORDER BY occurred_at DESC
        """
    )
    suspend fun getExpensesForPeriod(
        businessId: String,
        branchId: String,
        fromInclusive: Long,
        toExclusive: Long
    ): List<ExpenseEntity>
    /**
     * BOARDLOCK Expense Code 2 read-only projection source.
     *
     * Observes the same authoritative expenses table used by the Expense
     * command writer. No second financial state is created.
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE business_id = :businessId
          AND branch_id = :branchId
          AND occurred_at >= :fromInclusive
          AND occurred_at < :toExclusive
        ORDER BY occurred_at DESC
        """
    )
    fun observeExpensesForPeriod(
        businessId: String,
        branchId: String,
        fromInclusive: Long,
        toExclusive: Long
    ): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE cash_shift_id = :shiftId
        ORDER BY occurred_at ASC
        """
    )
    suspend fun getExpensesForShift(
        shiftId: String
    ): List<ExpenseEntity>

    @Query(
        """
        SELECT * FROM expense_events
        WHERE expense_id = :expenseId
        ORDER BY occurred_at ASC, id ASC
        """
    )
    suspend fun getEventsForExpense(
        expenseId: String
    ): List<ExpenseEventEntity>

    @Query(
        """
        SELECT * FROM expense_recurrences
        WHERE business_id = :businessId
          AND branch_id = :branchId
          AND is_active = 1
        ORDER BY next_due_at ASC
        """
    )
    suspend fun getActiveRecurrences(
        businessId: String,
        branchId: String
    ): List<ExpenseRecurrenceEntity>
}
