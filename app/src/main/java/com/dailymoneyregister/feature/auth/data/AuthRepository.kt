package com.dailymoneyregister.feature.auth.data

import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.PasswordHasher
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.core.security.AuthorityConnectivity
import com.dailymoneyregister.core.security.AuthorityDecision
import com.dailymoneyregister.core.security.RemoteAuthorityResult
import com.dailymoneyregister.core.security.OnlineOfflineAuthorityPolicy
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.dailymoneyregister.core.cloud.BusinessRestorer
import com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult
import javax.inject.Inject

sealed class AuthResult {
    data class Success(
        val session: ActiveSession
    ) : AuthResult()

    data class InvalidCredentials(
        val message: String
    ) : AuthResult()

    data class LockedOut(
        val remainingSeconds: Long
    ) : AuthResult()

    data class Error(
        val message: String
    ) : AuthResult()
}

class AuthRepository @Inject constructor(
    private val database: DmrDatabase,
    private val pinThrottler: PinThrottler,
    private val languageManager: LanguageManager,
    private val trustedSessionStore: TrustedSessionStore,
    private val remoteBusinessRestorer: BusinessRestorer
) {

    constructor(
        database: DmrDatabase,
        pinThrottler: PinThrottler,
        languageManager: LanguageManager,
        trustedSessionStore: TrustedSessionStore
    ) : this(
        database,
        pinThrottler,
        languageManager,
        trustedSessionStore,
        object : BusinessRestorer {
            override suspend fun restoreCashierIfEmpty(
                usernameOrPhone: String,
                pin: String,
                countryCode: String
            ) = RemoteBusinessRestorationResult.Failed("Cloud restoration unavailable in this test fixture")

            override suspend fun restoreAdminIfEmpty(
                usernameOrEmail: String,
                passwordPlain: String
            ) = RemoteBusinessRestorationResult.Failed("Cloud restoration unavailable in this test fixture")
        }
    )

    private companion object {
        const val DUMMY_PASSWORD_SALT = "00112233445566778899aabbccddeeff"
        const val DUMMY_PASSWORD_HASH =
            "pbkdf2-sha256:600000:c6339c1fdf3e142f79b1a690058124a64946b2e186fda750547c06c94ba2304c"
    }

    private val businessBranchDao =
        database.businessBranchDao()

    private val userDao =
        database.userDao()

    private data class LoginIdentifier(
        val cleanUsername: String,
        val canonicalPhone: String?,
        val preResolutionKey: String
    )

    private fun preResolutionThrottleKey(
        mode: String,
        businessId: String,
        branchId: String,
        canonicalPhone: String?,
        cleanUsername: String
    ): String {
        val identifierType = if (canonicalPhone != null) "PHONE" else "USERNAME"
        val normalizedIdentifier = canonicalPhone ?: cleanUsername
        return "pre:v1:$mode:$businessId:$branchId:$identifierType:$normalizedIdentifier"
    }

    private fun accountThrottleKey(
        businessId: String,
        userId: String
    ): String = "acct:v1:$businessId:$userId"

    private fun normalizeLoginIdentifier(
        mode: String,
        username: String,
        businessId: String,
        branch: com.dailymoneyregister.core.database.entity.BranchEntity
    ): LoginIdentifier {
        val canonicalPhone = runCatching {
            PhoneNumberNormalizer.normalize(username, branch.countryCode)
        }.getOrNull()
        val cleanUsername = username.trim()
        val preResolutionKey = preResolutionThrottleKey(
            mode = mode,
            businessId = businessId,
            branchId = branch.id,
            canonicalPhone = canonicalPhone,
            cleanUsername = cleanUsername
        )
        return LoginIdentifier(cleanUsername, canonicalPhone, preResolutionKey)
    }

    private suspend fun resolveLoginLookup(
        identifier: LoginIdentifier,
        businessId: String
    ): UserAccountEntity? {
        return identifier.canonicalPhone?.let {
            userDao.getUserByCanonicalPhone(businessId, it)
        } ?: userDao.getUserByUsername(businessId, identifier.cleanUsername)
    }

    /**
     * Authoritative lockout query for the login UI. It applies the same
     * normalization and account lookup semantics as the login methods while
     * returning only timing state, never account-existence information.
     */
    suspend fun getLoginLockoutRemaining(
        mode: String,
        username: String
    ): Long {
        val business = businessBranchDao.getFirstBusiness()
        if (business == null) {
            val canonicalPhone = runCatching {
                PhoneNumberNormalizer.normalize(username, "OM")
            }.getOrNull()
            val cleanUsername = username.trim()
            val preRemaining = pinThrottler.getRemainingLockoutSeconds(
                preResolutionThrottleKey(mode, "UNCONFIGURED", "UNCONFIGURED", canonicalPhone, cleanUsername)
            )
            return preRemaining
        }
        val branch = businessBranchDao.getFirstBranch(business.id) ?: return 0L
        val identifier = normalizeLoginIdentifier(mode, username, business.id, branch)
        val preRemaining = pinThrottler.getRemainingLockoutSeconds(identifier.preResolutionKey)
        val user = resolveLoginLookup(identifier, business.id)
        val accountRemaining = user?.let {
            pinThrottler.getRemainingLockoutSeconds(
                accountThrottleKey(business.id, it.id)
            )
        } ?: 0L
        return maxOf(preRemaining, accountRemaining)
    }

    private fun resolvedFailureKey(
        businessId: String,
        identifier: LoginIdentifier,
        user: UserAccountEntity?
    ): String = user?.let {
        accountThrottleKey(businessId, it.id)
    } ?: identifier.preResolutionKey

    private fun clearSuccessfulLoginThrottle(
        businessId: String,
        identifier: LoginIdentifier,
        user: UserAccountEntity?
    ) {
        user?.let {
            pinThrottler.recordSuccess(accountThrottleKey(businessId, it.id))
        }
        pinThrottler.recordSuccess(identifier.preResolutionKey)
    }

    suspend fun loginAdmin(
        username: String,
        passwordPlain: String
    ): AuthResult {
        // Server authentication is mandatory even when this device already
        // has a local business/account. Local hashes are not online authority.
        val remote = remoteBusinessRestorer.restoreAdminIfEmpty(username, passwordPlain)
        if (remote is RemoteBusinessRestorationResult.Failed) {
            return AuthResult.Error(remote.message)
        }
        if (OnlineOfflineAuthorityPolicy.evaluate(
                connectivity = AuthorityConnectivity.ONLINE,
                remoteResult = RemoteAuthorityResult.AUTHORIZED,
                lastServerValidatedAtMillis = System.currentTimeMillis(),
                nowMillis = System.currentTimeMillis(),
                offlineGraceMillis = 0L
            ) != AuthorityDecision.ALLOW_ONLINE) {
            return AuthResult.Error("Server authorization is required to sign in")
        }
        val authority = remote as? RemoteBusinessRestorationResult.Success
            ?: return AuthResult.Error("Server did not authorize this Admin")

        val business = businessBranchDao.getBusinessById(authority.businessId)
            ?: return AuthResult.Error("Authorized business is not restored on this device")
        val branch = businessBranchDao.getBranchById(authority.branchId)
            ?: return AuthResult.Error("Authorized branch is not restored on this device")
        val user = userDao.getUserById(authority.userId)
            ?: return AuthResult.Error("Authorized Admin is not restored on this device")
        if (branch.businessId != business.id || user.businessId != business.id ||
            user.role != UserRole.ADMIN || user.authType != AuthType.PASSWORD || !user.isActive) {
            return AuthResult.Error("Local Admin identity does not match server authority")
        }

        val session = ActiveSession(
            business = business,
            branch = branch,
            user = user,
            membership = userDao.getMembership(user.id, branch.id),
            requiresPinChange = false
        )
        SessionManager.setSession(session)
        trustedSessionStore.save(session.business.id, session.branch.id, session.user.id)
        languageManager.setLanguage(user.languageCode)
        return AuthResult.Success(session)
    }

    suspend fun loginCashier(
        username: String,
        pin: String
    ): AuthResult {
        // Server PIN authentication and live membership validation happen
        // before any local account can establish an app session.
        val localBusiness = businessBranchDao.getFirstBusiness()
        val localBranch = localBusiness?.let { businessBranchDao.getFirstBranch(it.id) }
        val remote = remoteBusinessRestorer.restoreCashierIfEmpty(
            username,
            pin,
            localBranch?.countryCode ?: "OM"
        )
        if (remote is RemoteBusinessRestorationResult.Failed) {
            return AuthResult.Error(remote.message)
        }
        if (OnlineOfflineAuthorityPolicy.evaluate(
                connectivity = AuthorityConnectivity.ONLINE,
                remoteResult = RemoteAuthorityResult.AUTHORIZED,
                lastServerValidatedAtMillis = System.currentTimeMillis(),
                nowMillis = System.currentTimeMillis(),
                offlineGraceMillis = 0L
            ) != AuthorityDecision.ALLOW_ONLINE) {
            return AuthResult.Error("Server authorization is required to sign in")
        }
        val authority = remote as? RemoteBusinessRestorationResult.Success
            ?: return AuthResult.Error("Server did not authorize this cashier")

        val business = businessBranchDao.getBusinessById(authority.businessId)
            ?: return AuthResult.Error("Authorized business is not restored on this device")
        val branch = businessBranchDao.getBranchById(authority.branchId)
            ?: return AuthResult.Error("Authorized branch is not restored on this device")
        val user = userDao.getUserById(authority.userId)
            ?: return AuthResult.Error("Authorized cashier is not restored on this device")
        if (branch.businessId != business.id || user.businessId != business.id ||
            user.role != UserRole.CASHIER || user.authType != AuthType.PIN || !user.isActive) {
            return AuthResult.Error("Local cashier identity does not match server authority")
        }
        val membership = userDao.getMembership(user.id, branch.id)
            ?: return AuthResult.Error("Cashier is not authorized for this branch")

        val session = ActiveSession(
            business = business,
            branch = branch,
            user = user,
            membership = membership,
            requiresPinChange = user.mustChangePin
        )
        SessionManager.setSession(session)
        trustedSessionStore.save(session.business.id, session.branch.id, session.user.id)
        languageManager.setLanguage(user.languageCode)
        return AuthResult.Success(session)
    }

    suspend fun changeCashierPin(newPin: String): AuthResult {
        val session = SessionManager.currentSession.value
            ?: return AuthResult.Error("No authenticated session")
        if (session.user.role != UserRole.CASHIER || !session.requiresPinChange) {
            return AuthResult.Error("PIN change is not required")
        }
        if (!PinHasher.isValidPin(newPin)) {
            return AuthResult.InvalidCredentials("Cashier PIN must be exactly 6 numeric digits")
        }
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hashPin(newPin, salt)
        val updated = userDao.replaceCredentialAndClearPinChange(
            session.user.id,
            session.user.credentialHash,
            session.user.credentialSalt,
            hash,
            salt,
            System.currentTimeMillis()
        )
        if (updated != 1) return AuthResult.Error("Credential changed concurrently; sign in again")
        val refreshed = userDao.getUserById(session.user.id)
            ?: return AuthResult.Error("Authenticated user no longer exists")
        SessionManager.setSession(session.copy(user = refreshed, requiresPinChange = false))
        return AuthResult.Success(SessionManager.currentSession.value!!)
    }

    private suspend fun upgradeAdminCredentialIfNeeded(
        user: UserAccountEntity,
        passwordPlain: String
    ): UserAccountEntity? {

        if (
            !PasswordHasher.needsUpgrade(
                user.credentialHash
            )
        ) {
            return user
        }

        val newSalt =
            PasswordHasher.generateSalt()

        val newHash =
            PasswordHasher.rehashVerifiedPassword(
                password = passwordPlain,
                saltHex = newSalt
            )

        val updatedAt =
            System.currentTimeMillis()

        val rowsUpdated =
            userDao.updateCredentialIfCurrent(
                userId = user.id,
                expectedHash = user.credentialHash,
                expectedSalt = user.credentialSalt,
                newHash = newHash,
                newSalt = newSalt,
                updatedAt = updatedAt
            )

        if (rowsUpdated == 1) {
            return userDao.getUserById(user.id)
                ?: user.copy(
                    credentialHash = newHash,
                    credentialSalt = newSalt,
                    updatedAt = updatedAt
                )
        }

        // Another writer changed the credential first.
        // Re-read authoritative state and only keep the
        // authentication if the supplied password still matches.
        val latest =
            userDao.getUserById(user.id)
                ?: return null

        return if (
            PasswordHasher.verifyPassword(
                passwordPlain,
                latest.credentialSalt ?: "",
                latest.credentialHash
            )
        ) {
            latest
        } else {
            null
        }
    }

    private suspend fun upgradeCashierCredentialIfNeeded(
        user: UserAccountEntity,
        pin: String
    ): UserAccountEntity? {

        if (
            !PinHasher.needsUpgrade(
                user.credentialHash
            )
        ) {
            return user
        }

        val newSalt =
            PinHasher.generateSalt()

        val newHash =
            PinHasher.hashPin(
                pin = pin,
                saltHex = newSalt
            )

        val updatedAt =
            System.currentTimeMillis()

        val rowsUpdated =
            userDao.updateCredentialIfCurrent(
                userId = user.id,
                expectedHash = user.credentialHash,
                expectedSalt = user.credentialSalt,
                newHash = newHash,
                newSalt = newSalt,
                updatedAt = updatedAt
            )

        if (rowsUpdated == 1) {
            return userDao.getUserById(user.id)
                ?: user.copy(
                    credentialHash = newHash,
                    credentialSalt = newSalt,
                    updatedAt = updatedAt
                )
        }

        val latest =
            userDao.getUserById(user.id)
                ?: return null

        return if (
            PinHasher.verifyPin(
                pin,
                latest.credentialSalt ?: "",
                latest.credentialHash
            )
        ) {
            latest
        } else {
            null
        }
    }

    fun getThrottler(): PinThrottler =
        pinThrottler
}

