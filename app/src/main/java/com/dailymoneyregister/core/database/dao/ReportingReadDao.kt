package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.ExpenseEventEntity
import com.dailymoneyregister.core.database.entity.ExpenseEventEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity

/**
 * BOARDLOCK Reporting read-only DAO.
 *
 * SELECT queries only. No insert, update or delete exists here.
 * Every period filter uses the half-open range:
 *
 *     fromInclusive <= occurred_at < toExclusive
 *
 * Each activity stream is filtered by ITS OWN timestamp, never by the
 * original sale date.
 *
 * Callers must chunk large id lists (the Reporting repository uses 500).
 */
@Dao
interface ReportingReadDao {

    // ---- Sales (original activity, by transaction creation time) ----------

    @Query(
        """
        SELECT id
        FROM transactions
        WHERE branch_id IN (:branchIds)
          AND type = 'SALE'
          AND created_at >= :fromInclusive
          AND created_at < :toExclusive
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun getSaleTransactionIdsCreatedInPeriod(
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<String>

    @Query(
        """
        SELECT *
        FROM transaction_items
        WHERE transaction_id IN (:transactionIds)
        ORDER BY transaction_id ASC, id ASC
        """
    )
    suspend fun getItemsForTransactions(
        transactionIds: List<String>
    ): List<TransactionItemEntity>

    @Query(
        """
        SELECT *
        FROM credit_ledger_events
        WHERE transaction_id IN (:transactionIds)
        ORDER BY occurred_at ASC, id ASC
        """
    )
    suspend fun getCreditEventsForTransactions(
        transactionIds: List<String>
    ): List<CreditLedgerEventEntity>

    // ---- Lifecycle events (by the event's own timestamp) -------------------

    @Query(
        """
        SELECT e.*
        FROM refund_events AS e
        INNER JOIN transactions AS t ON t.id = e.transaction_id
        WHERE t.branch_id IN (:branchIds)
          AND e.occurred_at >= :fromInclusive
          AND e.occurred_at < :toExclusive
        ORDER BY e.occurred_at ASC, e.id ASC
        """
    )
    suspend fun getRefundEventsInPeriod(
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<RefundEventEntity>

    @Query(
        """
        SELECT e.*
        FROM void_events AS e
        INNER JOIN transactions AS t ON t.id = e.transaction_id
        WHERE t.branch_id IN (:branchIds)
          AND e.occurred_at >= :fromInclusive
          AND e.occurred_at < :toExclusive
        ORDER BY e.occurred_at ASC, e.id ASC
        """
    )
    suspend fun getVoidEventsInPeriod(
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<VoidEventEntity>

    @Query(
        """
        SELECT e.*
        FROM amount_correction_events AS e
        INNER JOIN transactions AS t ON t.id = e.transaction_id
        WHERE t.branch_id IN (:branchIds)
          AND e.occurred_at >= :fromInclusive
          AND e.occurred_at < :toExclusive
        ORDER BY e.occurred_at ASC, e.id ASC
        """
    )
    suspend fun getAmountCorrectionsInPeriod(
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<AmountCorrectionEventEntity>

    @Query(
        """
        SELECT e.*
        FROM payment_method_change_events AS e
        INNER JOIN transactions AS t ON t.id = e.transaction_id
        WHERE t.branch_id IN (:branchIds)
          AND e.occurred_at >= :fromInclusive
          AND e.occurred_at < :toExclusive
        ORDER BY e.occurred_at ASC, e.id ASC
        """
    )
    suspend fun getPaymentMethodChangesInPeriod(
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<PaymentMethodChangeEventEntity>

    // ---- Credit repayments (operation supplies branch + success proof) -----

    @Query(
        """
        SELECT r.*
        FROM credit_repayments AS r
        INNER JOIN operations AS o ON o.id = r.operation_id
        WHERE o.business_id = :businessId
          AND o.branch_id IN (:branchIds)
          AND o.status = 'SUCCEEDED'
          AND r.occurred_at >= :fromInclusive
          AND r.occurred_at < :toExclusive
        ORDER BY r.occurred_at ASC, r.id ASC
        """
    )
    suspend fun getCreditRepaymentsInPeriod(
        businessId: String,
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<CreditRepaymentEntity>

    @Query(
        """
        SELECT *
        FROM credit_repayment_allocations
        WHERE repayment_id IN (:repaymentIds)
        ORDER BY repayment_id ASC, occurred_at ASC, id ASC
        """
    )
    suspend fun getAllocationsForRepayments(
        repaymentIds: List<String>
    ): List<CreditRepaymentAllocationEntity>

    // ---- Coupon ledger (operation supplies branch + success proof) ---------

    @Query(
        """
        SELECT e.*
        FROM coupon_ledger_events AS e
        INNER JOIN operations AS o ON o.id = e.operation_id
        WHERE o.business_id = :businessId
          AND o.branch_id IN (:branchIds)
          AND o.status = 'SUCCEEDED'
          AND e.occurred_at >= :fromInclusive
          AND e.occurred_at < :toExclusive
        ORDER BY e.occurred_at ASC, e.id ASC
        """
    )
    suspend fun getCouponEventsInPeriod(
        businessId: String,
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<CouponLedgerEventEntity>

    // ---- Expenses ----------------------------------------------------------

    @Query(
        """
        SELECT *
        FROM expenses
        WHERE business_id = :businessId
          AND branch_id IN (:branchIds)
          AND occurred_at >= :fromInclusive
          AND occurred_at < :toExclusive
        ORDER BY occurred_at ASC, id ASC
        """
    )
    suspend fun getExpensesInPeriod(
        businessId: String,
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<ExpenseEntity>

    /**
     * Returns the exact unsupported expense lifecycle records so the integrity
     * error can identify the evidence instead of presenting a generic failure.
     */
    @Query(
        """
        SELECT ev.*
        FROM expense_events AS ev
        INNER JOIN expenses AS e ON e.id = ev.expense_id
        WHERE e.business_id = :businessId
          AND e.branch_id IN (:branchIds)
          AND ev.occurred_at >= :fromInclusive
          AND ev.occurred_at < :toExclusive
        ORDER BY ev.occurred_at ASC, ev.id ASC
        """
    )
    suspend fun getExpenseEventsInPeriod(
        businessId: String,
        branchIds: List<String>,
        fromInclusive: Long,
        toExclusive: Long
    ): List<ExpenseEventEntity>

    // ---- Batch lookups -----------------------------------------------------

    @Query("SELECT * FROM operations WHERE id IN (:operationIds)")
    suspend fun getOperationsByIds(
        operationIds: List<String>
    ): List<OperationEntity>

    @Query(
        """
        SELECT *
        FROM user_accounts
        WHERE business_id = :businessId
          AND id IN (:userIds)
        """
    )
    suspend fun getUsersByIdsInBusiness(
        businessId: String,
        userIds: List<String>
    ): List<UserAccountEntity>
}