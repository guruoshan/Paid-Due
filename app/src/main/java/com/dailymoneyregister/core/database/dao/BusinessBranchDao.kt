package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity

@Dao
interface BusinessBranchDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBusiness(business: BusinessEntity)

    @Update
    suspend fun updateBusiness(business: BusinessEntity)

    @Query("SELECT * FROM businesses LIMIT 1")
    suspend fun getFirstBusiness(): BusinessEntity?

    @Query("SELECT * FROM businesses WHERE id = :businessId")
    suspend fun getBusinessById(businessId: String): BusinessEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBranch(branch: BranchEntity)

    @Update
    suspend fun updateBranch(branch: BranchEntity)

    /** Keeps business and branch display configuration consistent. */
    @Transaction
    suspend fun updateBusinessDetailsAtomically(
        business: BusinessEntity?,
        branch: BranchEntity?
    ) {
        if (business != null) updateBusiness(business)
        if (branch != null) updateBranch(branch)
    }

    @Query("SELECT * FROM branches WHERE id = :branchId")
    suspend fun getBranchById(branchId: String): BranchEntity?

    @Query("SELECT * FROM branches WHERE business_id = :businessId")
    suspend fun getBranchesByBusinessId(businessId: String): List<BranchEntity>

    @Query("SELECT * FROM branches WHERE business_id = :businessId LIMIT 1")
    suspend fun getFirstBranch(businessId: String): BranchEntity?

}
