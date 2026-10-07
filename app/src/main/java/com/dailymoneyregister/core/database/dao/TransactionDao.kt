package com.dailymoneyregister.core.database.dao

import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransactionItems(items: List<TransactionItemEntity>)

    @Query("SELECT * FROM transactions WHERE id = :transactionId LIMIT 1")
    suspend fun getTransactionById(transactionId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id IN (:transactionIds)")
    suspend fun getTransactionsByIds(
        transactionIds: List<String>
    ): List<TransactionEntity>

    @Query("SELECT * FROM transaction_items WHERE transaction_id = :transactionId")
    suspend fun getItemsForTransaction(transactionId: String): List<TransactionItemEntity>

    @Query("SELECT * FROM transactions WHERE branch_id = :branchId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentTransactions(branchId: String, limit: Int = 20): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE branch_id = :branchId ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentTransactions(branchId: String, limit: Int = 20): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE branch_id = :branchId AND created_at >= :sinceTimestamp ORDER BY created_at DESC")
    fun observeTodayTransactions(branchId: String, sinceTimestamp: Long): Flow<List<TransactionEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE branch_id = :branchId
          AND (
              (created_at >= :sinceTimestamp AND created_at < :untilTimestamp)
              OR EXISTS (
                  SELECT 1 FROM amount_correction_events
                  WHERE amount_correction_events.transaction_id = transactions.id
                    AND occurred_at >= :sinceTimestamp AND occurred_at < :untilTimestamp
              )
              OR EXISTS (
                  SELECT 1 FROM payment_method_change_events
                  WHERE payment_method_change_events.transaction_id = transactions.id
                    AND occurred_at >= :sinceTimestamp AND occurred_at < :untilTimestamp
              )
              OR EXISTS (
                  SELECT 1 FROM void_events
                  WHERE void_events.transaction_id = transactions.id
                    AND occurred_at >= :sinceTimestamp AND occurred_at < :untilTimestamp
              )
              OR EXISTS (
                  SELECT 1 FROM refund_events
                  WHERE refund_events.transaction_id = transactions.id
                    AND occurred_at >= :sinceTimestamp AND occurred_at < :untilTimestamp
              )
              OR EXISTS (
                  SELECT 1 FROM settlement_entries
                  WHERE settlement_entries.transaction_id = transactions.id
                    AND occurred_at >= :sinceTimestamp AND occurred_at < :untilTimestamp
              )
          )
        ORDER BY created_at DESC
        """
    )
    fun observeTodayFinancialRecords(
        branchId: String,
        sinceTimestamp: Long,
        untilTimestamp: Long
    ): Flow<List<TransactionFinancialRecord>>

    /**
     * Read-only broad History source for the active branch.
     *
     * One row per immutable transaction. Lifecycle relations are loaded through
     * TransactionFinancialRecord and resolved by FinancialReadModelResolver.
     */
    @Transaction
    @Query(
        """
        SELECT *
        FROM transactions
        WHERE branch_id = :branchId
        ORDER BY created_at DESC
        """
    )
    fun observeHistoryFinancialRecords(
        branchId: String
    ): Flow<List<TransactionFinancialRecord>>

    /**
     * Read-only customer transaction history for the active branch.
     *
     * BOARDLOCK:
     * - TransactionEntity remains immutable.
     * - Lifecycle relations are loaded through TransactionFinancialRecord.
     * - Financial meaning is resolved by FinancialReadModelResolver.
     * - No financial state is written or independently recalculated here.
     */
    @Transaction
    @Query(
        """
        SELECT *
        FROM transactions
        WHERE branch_id = :branchId
          AND customer_id = :customerId
        ORDER BY created_at DESC
        """
    )
    suspend fun getCustomerFinancialRecords(
        branchId: String,
        customerId: String
    ): List<TransactionFinancialRecord>

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :transactionId LIMIT 1")
    suspend fun getFinancialRecordById(
        transactionId: String
    ): TransactionFinancialRecord?

    /**
     * BOARDLOCK read-only batch lookup for Day Closing.
     */
    @Transaction
    @Query("SELECT * FROM transactions WHERE id IN (:transactionIds)")
    suspend fun getFinancialRecordsByIds(
        transactionIds: List<String>
    ): List<TransactionFinancialRecord>

    // Settlements
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlement(settlement: SettlementEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlements(settlements: List<SettlementEntryEntity>)

    @Query("SELECT * FROM settlement_entries WHERE transaction_id = :transactionId")
    suspend fun getSettlementsForTransaction(transactionId: String): List<SettlementEntryEntity>

    @Query("SELECT * FROM settlement_entries WHERE operation_id = :operationId")
    suspend fun getSettlementsForOperation(operationId: String): List<SettlementEntryEntity>

    @Query("SELECT * FROM settlement_entries WHERE operation_id IN (:operationIds)")
    suspend fun getSettlementsForOperations(
        operationIds: List<String>
    ): List<SettlementEntryEntity>

    @Query(
        """
        SELECT * FROM settlement_entries
        WHERE operation_id = :operationId
           OR transaction_id = :transactionId
           OR (subject_type = 'TRANSACTION' AND subject_id = :transactionId)
        """
    )
    suspend fun getSettlementsForCashSaleRecovery(
        operationId: String,
        transactionId: String
    ): List<SettlementEntryEntity>

    @Query("SELECT COALESCE(SUM(amount_signed_minor), 0) FROM settlement_entries WHERE transaction_id = :transactionId")
    suspend fun getEffectiveSettlementTotal(transactionId: String): Long

    // Typed Lifecycle Events
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAmountCorrection(event: AmountCorrectionEventEntity)

    @Query("SELECT * FROM amount_correction_events WHERE transaction_id = :transactionId ORDER BY occurred_at ASC")
    suspend fun getAmountCorrections(transactionId: String): List<AmountCorrectionEventEntity>

    @Query("SELECT * FROM amount_correction_events WHERE operation_id = :operationId ORDER BY occurred_at ASC LIMIT 2")
    suspend fun getAmountCorrectionsByOperationId(
        operationId: String
    ): List<AmountCorrectionEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPaymentMethodChange(event: PaymentMethodChangeEventEntity)

    @Query("SELECT * FROM payment_method_change_events WHERE transaction_id = :transactionId ORDER BY occurred_at ASC")
    suspend fun getPaymentMethodChanges(transactionId: String): List<PaymentMethodChangeEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVoid(event: VoidEventEntity)

    @Query("SELECT * FROM void_events WHERE transaction_id = :transactionId LIMIT 1")
    suspend fun getVoidEvent(transactionId: String): VoidEventEntity?

    @Query("SELECT * FROM void_events WHERE operation_id = :operationId LIMIT 1")
    suspend fun getVoidEventByOperationId(operationId: String): VoidEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRefund(event: RefundEventEntity)

    @Query("SELECT * FROM refund_events WHERE transaction_id = :transactionId ORDER BY occurred_at ASC")
    suspend fun getRefundEvents(transactionId: String): List<RefundEventEntity>

    @Query("SELECT * FROM refund_events WHERE operation_id = :operationId LIMIT 1")
    suspend fun getRefundEventByOperationId(operationId: String): RefundEventEntity?
}
