package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.ProductEntity

@Dao
interface ProductDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProduct(product: ProductEntity)

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("SELECT * FROM products WHERE id = :productId LIMIT 1")
    suspend fun getProductById(productId: String): ProductEntity?

    @Query("SELECT * FROM products WHERE branch_id = :branchId AND barcode = :barcode LIMIT 1")
    suspend fun getProductByBarcode(branchId: String, barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE branch_id = :branchId AND name LIKE :query || '%' LIMIT 20")
    suspend fun searchProductsByName(branchId: String, query: String): List<ProductEntity>
}
