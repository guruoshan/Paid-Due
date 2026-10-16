package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.OperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationDao {
    /** Device-local operation IDs for Recent Transactions; shared History intentionally uses no device filter. */
    @Query(
        """
        SELECT id FROM operations
        WHERE branch_id = :branchId
          AND device_id = :deviceId
          AND occurred_at >= :fromInclusive
          AND occurred_at < :toExclusive
          AND status = 'SUCCEEDED'
        ORDER BY occurred_at ASC, id ASC
        """
    )
    fun observeSucceededOperationIdsForDevice(
        branchId: String,
        deviceId: String,
        fromInclusive: Long,
        toExclusive: Long
    ): Flow<List<String>>

    @Query("SELECT * FROM operations WHERE operation_type = 'RECORD_COUPON_PURCHASE' AND status = 'REQUIRES_RECONCILIATION'")
    suspend fun getCouponOperationsRequiringReconciliation(): List<OperationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: OperationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperationIfAbsent(operation: OperationEntity): Long

    @Query("SELECT * FROM operations WHERE id = :operationId LIMIT 1")
    suspend fun getOperationById(operationId: String): OperationEntity?

    /**
     * Immutable operation membership for one cashier shift.
     *
     * cash_shift_id is frozen when the financial intent is admitted.
     * Historical pre-attribution operations remain NULL and are intentionally
     * excluded rather than guessed into a shift.
     */
    @Query(
        """
        SELECT *
        FROM operations
        WHERE cash_shift_id = :cashShiftId
          AND status = 'SUCCEEDED'
        ORDER BY occurred_at ASC, id ASC
        """
    )
    suspend fun getSucceededOperationsForCashShift(
        cashShiftId: String
    ): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE status = 'PENDING' ORDER BY occurred_at ASC")
    suspend fun getPendingOperations(): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE operation_type = 'RECORD_SALE' AND status = 'REQUIRES_RECONCILIATION' ORDER BY occurred_at ASC")
    suspend fun getSaleOperationsRequiringReconciliation(): List<OperationEntity>

    @Query(
        """
        UPDATE operations
        SET status = 'REQUIRES_RECONCILIATION', reason = :reason
        WHERE id = :operationId
          AND status = 'PENDING'
          AND request_fingerprint = :requestFingerprint
        """
    )
    suspend fun markRequiresReconciliationIfPending(
        operationId: String,
        requestFingerprint: String,
        reason: String
    ): Int

    @Query("SELECT * FROM operations WHERE transaction_id = :transactionId ORDER BY occurred_at ASC")
    suspend fun getOperationsForTransaction(
        transactionId: String
    ): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE transaction_id = :transactionId ORDER BY occurred_at ASC")
    fun observeOperationsForTransaction(
        transactionId: String
    ): Flow<List<OperationEntity>>

    @Query("SELECT COUNT(*) FROM operations WHERE id = :operationId AND status = 'SUCCEEDED'")
    suspend fun isOperationSucceeded(operationId: String): Boolean

    @Query(
        """
        UPDATE operations
        SET status = 'SUCCEEDED',
            result_reference_type = :resultReferenceType,
            result_reference_id = :resultReferenceId,
            reason = NULL
        WHERE id = :operationId
          AND status = 'PENDING'
          AND request_fingerprint = :requestFingerprint
        """
    )
    suspend fun markSucceededIfPending(
        operationId: String,
        requestFingerprint: String,
        resultReferenceType: String,
        resultReferenceId: String
    ): Int

    @Query(
        """
        UPDATE operations
        SET status = 'FAILED',
            reason = :reason
        WHERE id = :operationId
          AND status = 'PENDING'
          AND request_fingerprint = :requestFingerprint
        """
    )
    suspend fun markFailedIfPending(
        operationId: String,
        requestFingerprint: String,
        reason: String
    ): Int
}
