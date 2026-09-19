package com.dailymoneyregister.core.database.dao

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
     * Authoritative Credit balance derivation:
     * Balance = SUM(all signed credit ledger events for customer)
     * OBLIGATION (+), REPAYMENT (-), ADJUSTMENT (+/-)
     */
    @Query("SELECT COALESCE(SUM(amount_signed_minor), 0) FROM credit_ledger_events WHERE customer_id = :customerId AND currency_code = :currencyCode")
    suspend fun getCreditBalance(customerId: String, currencyCode: String): Long

    @Query("SELECT * FROM credit_ledger_events WHERE customer_id = :customerId ORDER BY occurred_at ASC")
    suspend fun getCreditLedgerForCustomer(customerId: String): List<CreditLedgerEventEntity>

    // Coupon Ledger (Append-only)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCouponWallet(wallet: CouponWalletEntity)

    @Query("SELECT * FROM coupon_wallets WHERE customer_id = :customerId AND currency_code = :currencyCode LIMIT 1")
    suspend fun getCouponWallet(customerId: String, currencyCode: String): CouponWalletEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCouponEvent(event: CouponLedgerEventEntity)

    /**
     * Authoritative Coupon balance derivation:
     * Balance = SUM(all signed coupon ledger events for wallet)
     * PURCHASE (+), BONUS (+), REDEMPTION (-), REFUND (+)
     */
    @Query("SELECT COALESCE(SUM(amount_signed_minor), 0) FROM coupon_ledger_events WHERE wallet_id = :walletId")
    suspend fun getCouponBalance(walletId: String): Long

    @Query("SELECT * FROM coupon_ledger_events WHERE wallet_id = :walletId ORDER BY occurred_at ASC")
    suspend fun getCouponLedgerForWallet(walletId: String): List<CouponLedgerEventEntity>

    // Coupon Authorized Users
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthorizedUser(user: CouponAuthorizedUserEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthorizedUserEvent(event: CouponAuthorizedUserEventEntity)

    @Query("SELECT * FROM coupon_authorized_users WHERE wallet_id = :walletId AND is_active = 1")
    suspend fun getActiveAuthorizedUsers(walletId: String): List<CouponAuthorizedUserEntity>

    @Query("SELECT * FROM coupon_authorized_user_events WHERE authorized_user_id = :userId ORDER BY occurred_at ASC")
    suspend fun getAuthorizedUserEvents(userId: String): List<CouponAuthorizedUserEventEntity>
}
