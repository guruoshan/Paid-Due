package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: CustomerEntity)

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    suspend fun getCustomerById(customerId: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE business_id = :businessId AND canonical_phone = :canonicalPhone LIMIT 1")
    suspend fun getCustomerByPhone(businessId: String, canonicalPhone: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE business_id = :businessId ORDER BY name ASC")
    suspend fun getCustomersForBusiness(businessId: String): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE business_id = :businessId ORDER BY name ASC")
    fun observeCustomersForBusiness(businessId: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE business_id = :businessId AND (name LIKE '%' || :query || '%' OR canonical_phone LIKE '%' || :query || '%') LIMIT 50")
    suspend fun searchCustomers(businessId: String, query: String): List<CustomerEntity>
}
