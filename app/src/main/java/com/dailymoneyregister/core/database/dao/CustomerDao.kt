package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CustomerIdAliasEntity
import com.dailymoneyregister.core.database.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

/** One row of the Customers list: the customer plus a count of linked invoices. */
data class CustomerSummary(
    val id: String,
    val name: String,
    val phone: String,
    val invoiceCount: Int,
    val lastInvoiceAt: Long?,
    /** True when authoritative Credit ledger evidence exists for this customer. */
    val hasCredit: Boolean,
    /** True when an authoritative Coupon wallet exists for this customer. */
    val hasCoupon: Boolean,
    /** Derived only from authoritative Expense or Credit repayment evidence. */
    val hasPayments: Boolean
)

@Dao
interface CustomerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCustomerIdAlias(alias: CustomerIdAliasEntity): Long

    @Query("SELECT * FROM customer_id_aliases WHERE business_id = :businessId AND alias_customer_id = :aliasCustomerId LIMIT 1")
    suspend fun getCustomerIdAliasByAliasId(businessId: String, aliasCustomerId: String): CustomerIdAliasEntity?

    @Query("SELECT * FROM customer_id_aliases WHERE business_id = :businessId AND local_customer_id = :localCustomerId ORDER BY (alias_customer_id = canonical_customer_id) DESC, updated_at DESC LIMIT 1")
    suspend fun getCustomerIdAliasByLocalId(businessId: String, localCustomerId: String): CustomerIdAliasEntity?

    @Query("SELECT * FROM customer_id_aliases WHERE business_id = :businessId AND canonical_customer_id = :canonicalCustomerId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getCustomerIdAliasByCanonicalId(businessId: String, canonicalCustomerId: String): CustomerIdAliasEntity?

    @Query("SELECT * FROM customer_id_aliases WHERE business_id = :businessId AND canonical_phone = :canonicalPhone ORDER BY updated_at DESC LIMIT 1")
    suspend fun getCustomerIdAliasByPhone(businessId: String, canonicalPhone: String): CustomerIdAliasEntity?


    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    suspend fun getCustomerById(customerId: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id IN (:customerIds)")
    suspend fun getCustomersByIds(
        customerIds: List<String>
    ): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE business_id = :businessId AND canonical_phone = :canonicalPhone LIMIT 1")
    suspend fun getCustomerByPhone(businessId: String, canonicalPhone: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE business_id = :businessId ORDER BY name ASC")
    suspend fun getCustomersForBusiness(businessId: String): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE business_id = :businessId ORDER BY name ASC")
    fun observeCustomersForBusiness(businessId: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE business_id = :businessId AND (name LIKE '%' || :query || '%' OR canonical_phone LIKE '%' || :query || '%') LIMIT 50")
    suspend fun searchCustomers(businessId: String, query: String): List<CustomerEntity>

    // ---- Customers screen (read-only) -------------------------------------------

    /** Every customer of the business with a count of linked invoices (0 is allowed). */
    @Query(
        """
        SELECT c.id AS id,
               c.name AS name,
               c.canonical_phone AS phone,
               COUNT(d.id) AS invoiceCount,
               MAX(d.created_at) AS lastInvoiceAt,
               EXISTS(
                   SELECT 1
                   FROM credit_ledger_events AS cle
                   WHERE cle.customer_id = c.id
               ) AS hasCredit,
               EXISTS(
                   SELECT 1
                   FROM coupon_wallets AS cw
                   WHERE cw.customer_id = c.id
               ) AS hasCoupon,
               (
                   EXISTS(
                       SELECT 1
                       FROM expenses AS e
                       WHERE e.payee_id = c.id
                         AND e.business_id = c.business_id
                   )
                   OR
                   EXISTS(
                       SELECT 1
                       FROM credit_ledger_events AS cle_payment
                       WHERE cle_payment.customer_id = c.id
                         AND cle_payment.type = 'REPAYMENT'
                   )
               ) AS hasPayments
        FROM customers AS c
        LEFT JOIN documents AS d
               ON d.customer_id = c.id AND d.document_type = 'INVOICE'
        WHERE c.business_id = :businessId
        GROUP BY c.id, c.name, c.canonical_phone
        ORDER BY c.name COLLATE NOCASE ASC
        """
    )
    suspend fun getCustomerSummaries(businessId: String): List<CustomerSummary>

    /** Invoices linked to one customer, newest first. */
    @Query(
        """
        SELECT * FROM documents
        WHERE customer_id = :customerId AND document_type = 'INVOICE'
        ORDER BY created_at DESC
        """
    )
    suspend fun getInvoicesForCustomer(customerId: String): List<DocumentEntity>

    // ---- Linking older invoices (used by InvoiceLinkBackfillRepository) ----------

    /** Invoices of the business that were issued before customer linking existed. */
    @Query(
        """
        SELECT d.* FROM documents AS d
        INNER JOIN operations AS o ON o.id = d.operation_id
        WHERE d.document_type = 'INVOICE'
          AND d.customer_id IS NULL
          AND o.business_id = :businessId
        ORDER BY d.created_at DESC
        """
    )
    suspend fun getUnlinkedInvoices(businessId: String): List<DocumentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM documents AS d
        INNER JOIN operations AS o ON o.id = d.operation_id
        WHERE d.document_type = 'INVOICE'
          AND d.customer_id IS NULL
          AND o.business_id = :businessId
        """
    )
    suspend fun countUnlinkedInvoices(businessId: String): Int

    /**
     * Sets the link on one invoice. Only ever fills an empty link on an
     * invoice; it never changes an existing link. Returns rows changed (0 or 1).
     */
    @Query(
        """
        UPDATE documents SET customer_id = :customerId
        WHERE id = :documentId
          AND document_type = 'INVOICE'
          AND customer_id IS NULL
        """
    )
    suspend fun linkInvoiceToCustomer(documentId: String, customerId: String): Int
}
