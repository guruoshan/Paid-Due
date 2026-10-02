package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.dao.ProductDao
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.core.model.ids.IdGenerator
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Lightweight item memory: lets the cashier type a few letters of a
 * previously-used item name and get it (with its last price) back.
 *
 * This is display/autofill convenience only - it never feeds financial
 * calculations directly. The cashier always sees and can edit the price
 * before it's added to a bill.
 */
class ProductRepository @Inject constructor(
    private val productDao: ProductDao
) {
    suspend fun search(branchId: String, query: String): List<ProductEntity> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            productDao.searchProductsByName(branchId, trimmed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Remembers an item just added to a bill, for next time.
     * Matches by exact (case-insensitive) name within the branch;
     * updates the remembered price/description if the name repeats,
     * otherwise creates a new entry. Best-effort: failures are swallowed
     * so a memory hiccup never blocks adding an item to a sale.
     */
    suspend fun remember(
        branchId: String,
        name: String,
        description: String?,
        unitPriceMinor: Long
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        try {
            val existing = productDao.searchProductsByName(branchId, trimmedName)
                .firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }

            val now = System.currentTimeMillis()

            if (existing != null) {
                productDao.updateProduct(
                    existing.copy(
                        description = description,
                        unitPriceMinor = unitPriceMinor,
                        updatedAt = now
                    )
                )
            } else {
                productDao.insertProduct(
                    ProductEntity(
                        id = IdGenerator.newId(),
                        branchId = branchId,
                        name = trimmedName,
                        description = description,
                        barcode = null,
                        unitPriceMinor = unitPriceMinor,
                        mediaId = null,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort memory only; never blocks the sale.
        }
    }
}
