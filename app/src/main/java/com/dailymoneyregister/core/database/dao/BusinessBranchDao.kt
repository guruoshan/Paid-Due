package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessBranchDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBusiness(business: BusinessEntity)

    @Update
    suspend fun updateBusiness(business: BusinessEntity)

    @Query("SELECT * FROM businesses LIMIT 1")
    suspend fun getFirstBusiness(): BusinessEntity?

    @Query("SELECT * FROM businesses LIMIT 1")
    fun observeFirstBusiness(): Flow<BusinessEntity?>

    @Query("SELECT * FROM businesses WHERE id = :businessId")
    suspend fun getBusinessById(businessId: String): BusinessEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBranch(branch: BranchEntity)

    @Update
    suspend fun updateBranch(branch: BranchEntity)

    @Query("SELECT * FROM branches WHERE id = :branchId")
    suspend fun getBranchById(branchId: String): BranchEntity?

    @Query("SELECT * FROM branches WHERE business_id = :businessId")
    suspend fun getBranchesByBusinessId(businessId: String): List<BranchEntity>

    @Query("SELECT * FROM branches WHERE business_id = :businessId LIMIT 1")
    suspend fun getFirstBranch(businessId: String): BranchEntity?

    @Query("SELECT * FROM branches WHERE business_id = :businessId LIMIT 1")
    fun observeFirstBranch(businessId: String): Flow<BranchEntity?>
}
