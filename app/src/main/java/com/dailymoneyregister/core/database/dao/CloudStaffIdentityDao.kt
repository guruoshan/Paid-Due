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
        WHERE membership_id = :membershipId
          AND local_user_id = :localUserId
          AND business_id = :businessId
          AND branch_id = :branchId
        LIMIT 1
    """)
    suspend fun find(
        membershipId: String,
        localUserId: String,
        businessId: String,
        branchId: String
    ): CloudStaffIdentityEntity?

    @Query("""
        SELECT * FROM cloud_staff_identities
        WHERE business_id = :businessId
          AND branch_id = :branchId
          AND local_user_id = :localUserId
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    suspend fun findByUser(
        businessId: String,
        branchId: String,
        localUserId: String
    ): CloudStaffIdentityEntity?

    @Query("""
        SELECT * FROM cloud_staff_identities
        WHERE business_id = :businessId AND branch_id = :branchId
    """)
    suspend fun listForBranch(businessId: String, branchId: String): List<CloudStaffIdentityEntity>
}
