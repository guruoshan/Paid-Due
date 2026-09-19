package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.OperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: OperationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperationIfAbsent(operation: OperationEntity): Long

    @Query("SELECT * FROM operations WHERE id = :operationId LIMIT 1")
    suspend fun getOperationById(operationId: String): OperationEntity?

    @Query("SELECT * FROM operations WHERE status = 'PENDING' ORDER BY occurred_at ASC")
    suspend fun getPendingOperations(): List<OperationEntity>

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
