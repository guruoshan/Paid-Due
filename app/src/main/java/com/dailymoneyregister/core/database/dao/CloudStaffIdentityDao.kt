package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity

@Dao
interface CloudStaffIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(identities: List<CloudStaffIdentityEntity>)

    @Query("""
        SELECT * FROM cloud_staff_identities
        WHERE local_user_id = :localUserId
          AND business_id = :businessId
          AND branch_id = :branchId
          AND role = 'CASHIER'
          AND is_active = 1
          AND is_removed = 0
        LIMIT 1
    """)
    suspend fun findActiveCashier(
        localUserId: String,
        businessId: String,
        branchId: String
    ): CloudStaffIdentityEntity?

    @Query("""
        SELECT * FROM cloud_staff_identities
        WHERE business_id = :businessId AND branch_id = :branchId
    """)
    suspend fun listForBranch(businessId: String, branchId: String): List<CloudStaffIdentityEntity>
}
