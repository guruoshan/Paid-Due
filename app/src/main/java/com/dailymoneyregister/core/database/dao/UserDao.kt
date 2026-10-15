package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.HumanIdentityEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.UserRole
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHumanIdentity(identity: HumanIdentityEntity)

    @Update
    suspend fun updateHumanIdentity(identity: HumanIdentityEntity)

    @Query("SELECT * FROM human_identities WHERE id = :identityId")
    suspend fun getHumanIdentityById(identityId: String): HumanIdentityEntity?

    @Query("SELECT * FROM human_identities WHERE canonical_phone = :canonicalPhone LIMIT 1")
    suspend fun getHumanIdentityByPhone(canonicalPhone: String): HumanIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserAccountEntity)

    @Update
    suspend fun updateUser(user: UserAccountEntity)

    @Query(
        """
        UPDATE user_accounts
        SET language_code = :languageCode,
            updated_at = :updatedAt
        WHERE id = :userId
          AND business_id = :businessId
        """
    )
    suspend fun updateLanguageForUser(
        userId: String,
        businessId: String,
        languageCode: String,
        updatedAt: Long
    ): Int

    @Query(
        """
        SELECT *
        FROM user_accounts
        WHERE id = :userId
          AND business_id = :businessId
        LIMIT 1
        """
    )
    suspend fun getUserByIdInBusiness(
        userId: String,
        businessId: String
    ): UserAccountEntity?

    /**
     * Credential upgrades must not overwrite unrelated User fields.
     *
     * The old hash/salt act as a compare-and-set guard so a
     * concurrent credential change cannot be silently overwritten.
     */
    @Query(
        """
        UPDATE user_accounts
        SET credential_hash = :newHash,
            credential_salt = :newSalt,
            updated_at = :updatedAt
        WHERE id = :userId
          AND credential_hash = :expectedHash
          AND (
                credential_salt = :expectedSalt
                OR (
                    credential_salt IS NULL
                    AND :expectedSalt IS NULL
                )
              )
        """
    )
    suspend fun updateCredentialIfCurrent(
        userId: String,
        expectedHash: String,
        expectedSalt: String?,
        newHash: String,
        newSalt: String,
        updatedAt: Long
    ): Int

    @Query("SELECT * FROM user_accounts WHERE id = :userId")
    suspend fun getUserById(userId: String): UserAccountEntity?

    @Query("SELECT * FROM user_accounts WHERE business_id = :businessId AND username = :username LIMIT 1")
    suspend fun getUserByUsername(businessId: String, username: String): UserAccountEntity?

    @Query("SELECT u.* FROM user_accounts u INNER JOIN human_identities h ON h.id = u.identity_id WHERE h.canonical_phone = :canonicalPhone AND u.business_id = :businessId LIMIT 1")
    suspend fun getUserByCanonicalPhone(businessId: String, canonicalPhone: String): UserAccountEntity?

    @Query("UPDATE user_accounts SET credential_hash = :newHash, credential_salt = :newSalt, must_change_pin = 0, updated_at = :updatedAt WHERE id = :userId AND credential_hash = :expectedHash AND (credential_salt = :expectedSalt OR (credential_salt IS NULL AND :expectedSalt IS NULL))")
    suspend fun replaceCredentialAndClearPinChange(userId: String, expectedHash: String, expectedSalt: String?, newHash: String, newSalt: String, updatedAt: Long): Int

    @Query("SELECT * FROM user_accounts WHERE business_id = :businessId AND role = 'ADMIN' LIMIT 1")
    suspend fun getFirstAdmin(businessId: String): UserAccountEntity?

    @Query("SELECT * FROM user_accounts WHERE business_id = :businessId AND role = 'CASHIER'")
    suspend fun getCashiers(businessId: String): List<UserAccountEntity>

    @Query("""
        SELECT DISTINCT u.*
        FROM user_accounts u
        INNER JOIN user_branch_memberships m ON m.user_id = u.id
        WHERE u.business_id = :businessId
          AND u.role = 'CASHIER'
          AND u.is_active = 1
          AND m.branch_id = :branchId
          AND m.role = 'CASHIER'
        ORDER BY u.display_name COLLATE NOCASE
    """)
    suspend fun getCashiersForBranch(
        businessId: String,
        branchId: String
    ): List<UserAccountEntity>

    @Query("SELECT * FROM user_accounts WHERE business_id = :businessId AND role = 'CASHIER'")
    fun observeCashiers(businessId: String): Flow<List<UserAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembership(membership: UserBranchMembershipEntity)

    @Update
    suspend fun updateMembership(membership: UserBranchMembershipEntity)

    @Query("SELECT * FROM user_branch_memberships WHERE user_id = :userId AND branch_id = :branchId LIMIT 1")
    suspend fun getMembership(userId: String, branchId: String): UserBranchMembershipEntity?

    @Query("SELECT * FROM user_branch_memberships WHERE user_id = :userId")
    suspend fun getMembershipsForUser(userId: String): List<UserBranchMembershipEntity>

    @Query("SELECT * FROM user_branch_memberships WHERE branch_id = :branchId")
    suspend fun getMembershipsForBranch(branchId: String): List<UserBranchMembershipEntity>
}
