package com.dailymoneyregister.core.database.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEntity
import com.dailymoneyregister.core.database.entity.CouponAuthorizedUserEventEntity
import com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity

@Dao
interface LedgerDao {

    // Credit Ledger (Append-only)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCreditEvent(event: CreditLedgerEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCreditRepayment(repayment: CreditRepaymentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCreditRepaymentAllocations(allocations: List<CreditRepaymentAllocationEntity>)
    /**
     * Exact authoritative repayment evidence lookup.
     * Read-only. Used for replay/recovery verification.
     */
    @Query(
        "SELECT * FROM credit_repayments WHERE id = :repaymentId LIMIT 1"
    )
    suspend fun getCreditRepaymentById(
        repaymentId: String
    ): CreditRepaymentEntity?

    /**
     * All repayment records created by one authoritative operation.
     * More than one row is inconsistent evidence.
     */
    @Query(
        "SELECT * FROM credit_repayments WHERE operation_id = :operationId"
    )
    suspend fun getCreditRepaymentsForOperation(
        operationId: String
    ): List<CreditRepaymentEntity>

    @Query(
        "SELECT * FROM credit_repayments WHERE operation_id IN (:operationIds)"
    )
    suspend fun getCreditRepaymentsForOperations(
        operationIds: List<String>
    ): List<CreditRepaymentEntity>

    @Query(
        """
        SELECT repayment.*
        FROM credit_repayments AS repayment
        INNER JOIN operations AS operation
            ON operation.id = repayment.operation_id
        WHERE operation.branch_id = :branchId
          AND operation.status = 'SUCCEEDED'
          AND repayment.occurred_at >= :sinceTimestamp
          AND repayment.occurred_at < :untilTimestamp
        ORDER BY repayment.occurred_at DESC, repayment.id DESC
        """
    )
    fun observeCreditRepaymentsForPeriod(
        branchId: String,
        sinceTimestamp: Long,
        untilTimestamp: Long
    ): Flow<List<CreditRepaymentEntity>>

    /**
     * Exact FIFO allocation evidence for one repayment.
     */
    @Query(
        """
        SELECT a.*
        FROM credit_repayment_allocations AS a
        JOIN credit_ledger_events AS obligation
          ON obligation.transaction_id = a.original_transaction_id
         AND obligation.type = 'OBLIGATION'
        WHERE a.repayment_id = :repaymentId
        ORDER BY obligation.occurred_at ASC, a.original_transaction_id ASC
        """
    )
    suspend fun getCreditRepaymentAllocations(
        repaymentId: String
    ): List<CreditRepaymentAllocationEntity>


    @Query(
        "SELECT * FROM credit_repayment_allocations " +
            "WHERE original_transaction_id = :transactionId"
    )
    suspend fun getCreditRepaymentAllocationsForTransaction(
        transactionId: String
    ): List<CreditRepaymentAllocationEntity>

    /**
     * Read-only lookup for authoritative repayment evidence already referenced
     * by credit_repayment_allocations. This does not derive or mutate balances.
     */
    @Query("SELECT * FROM credit_repayments WHERE id IN (:repaymentIds)")
    suspend fun getCreditRepaymentsByIds(
        repaymentIds: List<String>
    ): List<CreditRepaymentEntity>

    /**
     * Authoritative Credit balance derivation:
     * Balance = SUM(all signed credit ledger events for customer)
     * OBLIGATION (+), REPAYMENT (-), ADJUSTMENT (+/-)
     */
    @Query("SELECT COALESCE(SUM(amount_signed_minor), 0) FROM credit_ledger_events WHERE customer_id = :customerId AND currency_code = :currencyCode")
    suspend fun getCreditBalance(customerId: String, currencyCode: String): Long

    @Query("SELECT * FROM credit_ledger_events WHERE customer_id = :customerId ORDER BY occurred_at ASC, id ASC")
    suspend fun getCreditLedgerForCustomer(customerId: String): List<CreditLedgerEventEntity>

    @Query("SELECT * FROM credit_ledger_events WHERE operation_id IN (:operationIds)")
    suspend fun getCreditEventsForOperations(
        operationIds: List<String>
    ): List<CreditLedgerEventEntity>

    // Coupon Ledger (Append-only)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCouponWallet(wallet: CouponWalletEntity)

    @Query("SELECT * FROM coupon_wallets WHERE customer_id = :customerId AND currency_code = :currencyCode LIMIT 1")
    suspend fun getCouponWallet(customerId: String, currencyCode: String): CouponWalletEntity?

    /**
     * Read-only lookup from authoritative Coupon lifecycle evidence.
     */
    @Query("SELECT * FROM coupon_wallets WHERE id = :walletId LIMIT 1")
    suspend fun getCouponWalletById(walletId: String): CouponWalletEntity?

    @Query("SELECT * FROM coupon_wallets WHERE id IN (:walletIds)")
    suspend fun getCouponWalletsByIds(
        walletIds: List<String>
    ): List<CouponWalletEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCouponEvent(event: CouponLedgerEventEntity)

    /**
     * Authoritative Coupon balance derivation:
     * Balance = SUM(all signed coupon ledger events for wallet)
     * PURCHASE (+), BONUS (+), REDEMPTION (-), REFUND (+)
     */
    @Query("SELECT COALESCE(SUM(amount_signed_minor), 0) FROM coupon_ledger_events WHERE wallet_id = :walletId")
    suspend fun getCouponBalance(walletId: String): Long

    @Query("SELECT * FROM coupon_ledger_events WHERE wallet_id = :walletId ORDER BY occurred_at ASC, id ASC")
    suspend fun getCouponLedgerForWallet(walletId: String): List<CouponLedgerEventEntity>

    @Query("SELECT * FROM coupon_ledger_events WHERE operation_id = :operationId")
    suspend fun getCouponEventsForOperation(operationId: String): List<CouponLedgerEventEntity>

    @Query("SELECT * FROM coupon_ledger_events WHERE operation_id IN (:operationIds)")
    suspend fun getCouponEventsForOperations(
        operationIds: List<String>
    ): List<CouponLedgerEventEntity>

    @Query("SELECT COALESCE(SUM(amount_signed_minor), 0) FROM coupon_ledger_events WHERE wallet_id = :walletId AND rowid <= (SELECT rowid FROM coupon_ledger_events WHERE id = :eventId)")
    suspend fun getCouponBalanceAfterEvent(walletId: String, eventId: String): Long

    // Coupon Authorized Users
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthorizedUser(user: CouponAuthorizedUserEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthorizedUserEvent(event: CouponAuthorizedUserEventEntity)

    @Query("SELECT * FROM coupon_authorized_users WHERE wallet_id = :walletId")
    suspend fun getAuthorizedUsersForWallet(walletId: String): List<CouponAuthorizedUserEntity>

    @Query("SELECT * FROM coupon_authorized_users WHERE wallet_id = :walletId AND is_active = 1")
    suspend fun getActiveAuthorizedUsers(walletId: String): List<CouponAuthorizedUserEntity>

    @Query("SELECT * FROM coupon_authorized_user_events WHERE authorized_user_id = :userId ORDER BY rowid ASC")
    suspend fun getAuthorizedUserEvents(userId: String): List<CouponAuthorizedUserEventEntity>
}

