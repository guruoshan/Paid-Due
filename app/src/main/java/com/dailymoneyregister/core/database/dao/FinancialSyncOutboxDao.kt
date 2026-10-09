package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.FinancialSyncOutboxEntity

/**
 * Read-only queue access.
 * Cloud upload and acknowledgement are not enabled yet.
 */
@Dao
interface FinancialSyncOutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutbox(entity: FinancialSyncOutboxEntity): Long

    @Query(
        """
        SELECT * FROM financial_sync_outbox
        WHERE operation_id = :operationId
        LIMIT 1
        """
    )
    suspend fun findByOperationId(
        operationId: String
    ): FinancialSyncOutboxEntity?

    @Query(
        """
        SELECT * FROM financial_sync_outbox
        WHERE status = 'QUEUED'
        ORDER BY occurred_at ASC, operation_id ASC
        LIMIT :limit
        """
    )
    suspend fun getQueued(
        limit: Int
    ): List<FinancialSyncOutboxEntity>

    @Query(
        """
        SELECT * FROM financial_sync_outbox
        WHERE status IN ('QUEUED', 'WAITING_RETRY')
        ORDER BY occurred_at ASC, operation_id ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingUpload(
        limit: Int
    ): List<FinancialSyncOutboxEntity>

    @Query(
        """
        SELECT COUNT(*) FROM financial_sync_outbox
        WHERE status = 'QUEUED'
        """
    )
    suspend fun countQueued(): Int

    @Query(
        """
        UPDATE financial_sync_outbox
        SET status = :status,
            attempt_count = attempt_count + :incrementAttempt
        WHERE operation_id = :operationId
        """
    )
    suspend fun updateStatus(
        operationId: String,
        status: String,
        incrementAttempt: Int = 0
    ): Int

    @Query("UPDATE financial_sync_outbox SET status = 'WAITING_RETRY' WHERE status = 'IN_FLIGHT'")
    suspend fun resetStaleInFlight(): Int

    @Query(
        """
        UPDATE financial_sync_outbox
        SET status = CASE
            WHEN (SELECT role FROM user_accounts WHERE id = actor_user_id) = 'ADMIN'
                 AND operation_id IN (
                    SELECT id FROM operations WHERE operation_type IN (
                        'RECORD_SALE', 'RECORD_CREDIT_REPAYMENT', 'RECORD_COUPON_PURCHASE',
                        'RECORD_AMOUNT_CORRECTION', 'RECORD_PAYMENT_METHOD_CHANGE', 'RECORD_VOID',
                        'RECORD_REFUND', 'RECORD_EXPENSE', 'RECORD_EXPENSE_CORRECTION',
                        'RECORD_EXPENSE_VOID', 'OPEN_CASH_SHIFT', 'COUNT_CASH_SHIFT', 'CLOSE_CASH_SHIFT'
                    )
                 ) THEN status
            WHEN (SELECT role FROM user_accounts WHERE id = actor_user_id) = 'CASHIER'
                 AND operation_id IN (
                    SELECT id FROM operations WHERE operation_type IN (
                        'RECORD_SALE', 'RECORD_CREDIT_REPAYMENT', 'RECORD_COUPON_PURCHASE',
                        'RECORD_AMOUNT_CORRECTION', 'RECORD_PAYMENT_METHOD_CHANGE', 'RECORD_VOID', 'RECORD_REFUND'
                    )
                 ) THEN status
            WHEN (SELECT role FROM user_accounts WHERE id = actor_user_id) = 'CASHIER'
                 AND operation_id IN (
                    SELECT id FROM operations WHERE operation_type IN (
                        'RECORD_EXPENSE', 'RECORD_EXPENSE_CORRECTION', 'RECORD_EXPENSE_VOID',
                        'OPEN_CASH_SHIFT', 'COUNT_CASH_SHIFT', 'CLOSE_CASH_SHIFT'
                    )
                 ) THEN 'NOT_SYNCABLE_YET'
            ELSE 'NOT_APPLICABLE'
        END
        WHERE status IN ('QUEUED', 'WAITING_RETRY', 'IN_FLIGHT')
        """
    )
    suspend fun classifyExistingRows(): Int

    @Query(
        """
        DELETE FROM financial_sync_outbox
        WHERE operation_id = :operationId
          AND status = 'ACKNOWLEDGED'
        """
    )
    suspend fun deleteAcknowledged(
        operationId: String
    ): Int

    @Query(
        """
        SELECT o.operation_type as operation_type, f.status as status, COUNT(*) as count
        FROM financial_sync_outbox f
        JOIN operations o ON f.operation_id = o.id
        GROUP BY o.operation_type, f.status
        """
    )
    suspend fun getStatusCounts(): List<OutboxStatusCount>
}

data class OutboxStatusCount(
    @androidx.room.ColumnInfo(name = "operation_type") val operationType: String,
    @androidx.room.ColumnInfo(name = "status") val status: String,
    @androidx.room.ColumnInfo(name = "count") val count: Int
)
