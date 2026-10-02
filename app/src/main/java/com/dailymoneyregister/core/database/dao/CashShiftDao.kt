package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity

@Dao
interface CashShiftDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShift(shift: CashShiftEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShiftEvent(event: CashShiftEventEntity)

    @Query("SELECT * FROM cash_shifts WHERE id = :shiftId LIMIT 1")
    suspend fun getShiftById(shiftId: String): CashShiftEntity?

    @Query("SELECT * FROM cash_shifts WHERE branch_id = :branchId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestShiftForBranch(branchId: String): CashShiftEntity?

    @Query("SELECT * FROM cash_shift_events WHERE shift_id = :shiftId ORDER BY occurred_at ASC")
    suspend fun getEventsForShift(shiftId: String): List<CashShiftEventEntity>

    @Query("SELECT * FROM cash_shift_events WHERE operation_id = :operationId LIMIT 1")
    suspend fun getEventByOperationId(operationId: String): CashShiftEventEntity?
}
