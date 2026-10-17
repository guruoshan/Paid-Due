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
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.dailymoneyregister.core.cloud.BusinessRestorer
import com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull

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
        val cleanUsername = if (mode == "ADMIN") username.trim().lowercase() else username.trim()
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

        var business = businessBranchDao.getFirstBusiness()
        var ownerRestorationAttempted = false
        if (business == null) {
            ownerRestorationAttempted = true
            val restoreResult = remoteBusinessRestorer.restoreAdminIfEmpty(username, passwordPlain)
            if (restoreResult is com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult.Failed) {
                return AuthResult.Error(restoreResult.message)
            }
            business = businessBranchDao.getFirstBusiness()
        }

        if (business == null) {
            return AuthResult.Error(
                "No business configured. Run setup first."
            )
        }

        val branch = businessBranchDao.getFirstBranch(business.id)
            ?: return AuthResult.Error("No branch found for business")
        val identifier = normalizeLoginIdentifier("ADMIN", username, business.id, branch)
        var remainingLockout = pinThrottler.getRemainingLockoutSeconds(identifier.preResolutionKey)
        var user = resolveLoginLookup(identifier, business.id)
        val accountKey = user?.let { accountThrottleKey(business.id, it.id) }
        val accountRemaining = accountKey?.let {
            pinThrottler.getRemainingLockoutSeconds(it)
        } ?: 0L
        remainingLockout = maxOf(remainingLockout, accountRemaining)
        if (remainingLockout > 0L) return AuthResult.LockedOut(remainingLockout)

        /*
         * An existing business without a local owner account is a normal
         * state on a device that previously had only a staff session.
         * Restore the owner only from an authenticated cloud identity that
         * matches this exact business and branch; never reset local data.
         */
        if (user == null && !ownerRestorationAttempted) {
            val restored = withTimeoutOrNull(3_500L) {
                remoteBusinessRestorer.restoreAdminIfEmpty(username, passwordPlain)
            }
            if (
                restored is com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult.Success &&
                restored.businessId == business.id &&
                restored.branchId == branch.id
            ) {
                user = resolveLoginLookup(identifier, business.id)
            } else if (restored is com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult.Failed) {
                android.util.Log.w(
                    "PaidDueAuth",
                    "OWNER_RESTORATION_ON_EXISTING_DEVICE_FAILED"
                )
            }
        }

        val failureKey = resolvedFailureKey(business.id, identifier, user)

        if (user == null) {
            PasswordHasher.verifyPassword(
                passwordPlain,
                DUMMY_PASSWORD_SALT,
                DUMMY_PASSWORD_HASH
            )

            val lockoutSeconds =
                pinThrottler.recordFailure(
                    failureKey
                )

            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(
                    lockoutSeconds
                )
            } else {
                AuthResult.InvalidCredentials(
                    "Invalid username or password"
                )
            }
        }

        if (
            !user.isActive ||
            user.role != UserRole.ADMIN ||
            user.authType != AuthType.PASSWORD
        ) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    failureKey
                )

            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(
                    lockoutSeconds
                )
            } else {
                AuthResult.InvalidCredentials(
                    "Invalid username or password"
                )
            }
        }

        val isValid =
            PasswordHasher.verifyPassword(
                passwordPlain,
                user.credentialSalt ?: "",
                user.credentialHash
            )

        if (!isValid) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    failureKey
                )

            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(
                    lockoutSeconds
                )
            } else {
                AuthResult.InvalidCredentials(
                    "Invalid username or password"
                )
            }
        }

        val authenticatedUser =
            upgradeAdminCredentialIfNeeded(
                user = user,
                passwordPlain = passwordPlain
            )

        if (authenticatedUser == null) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    failureKey
                )

            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(
                    lockoutSeconds
                )
            } else {
                AuthResult.InvalidCredentials(
                    "Invalid username or password"
                )
            }
        }

        clearSuccessfulLoginThrottle(business.id, identifier, authenticatedUser)

        val membership =
            userDao.getMembership(
                authenticatedUser.id,
                branch.id
            )

        // Admin login remains offline-first: a cloud failure must not invalidate
        // the already-verified local login. When online, establish/verify Supabase
        // Auth before SessionManager starts the sync scheduler. The backend RPC
        // checks that this exact user, membership, business, and branch match.
        if (membership != null) {
            val cloudAuth = withTimeoutOrNull(3_500L) {
                remoteBusinessRestorer.authenticateAdminForSync(
                    usernameOrEmail = username,
                    passwordPlain = passwordPlain,
                    expectedBusinessId = business.id,
                    expectedBranchId = branch.id,
                    expectedUserId = authenticatedUser.id,
                    expectedMembershipId = membership.id
                )
            }
            when {
                cloudAuth is RemoteBusinessRestorationResult.Success -> {
                    android.util.Log.i(
                        "PaidDueFinancialSync",
                        "ADMIN_SUPABASE_AUTHENTICATED membership=${membership.id}"
                    )
                }
                else -> {
                    // No secrets or credential details in logs. Local login remains
                    // available, but the sync engine will fail closed without a
                    // matching server-authenticated Admin session.
                    android.util.Log.w(
                        "PaidDueFinancialSync",
                        "ADMIN_SUPABASE_AUTH_UNAVAILABLE"
                    )
                }
            }
        } else {
            android.util.Log.w(
                "PaidDueFinancialSync",
                "ADMIN_SUPABASE_AUTH_SKIPPED_MEMBERSHIP_MISSING"
            )
        }

        val session =
            ActiveSession(
                business,
                branch,
                authenticatedUser,
                membership
            )

        SessionManager.setSession(session)

        trustedSessionStore.save(
            businessId = session.business.id,
            branchId = session.branch.id,
            userId = session.user.id
        )

        languageManager.setLanguage(
            authenticatedUser.languageCode
        )

        return AuthResult.Success(session)
    }

    suspend fun loginCashier(
        username: String,
        pin: String
    ): AuthResult {

        var business = businessBranchDao.getFirstBusiness()
        if (business == null) {
            val restoreResult = remoteBusinessRestorer.restoreCashierIfEmpty(username, pin)
            if (restoreResult is com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult.Failed) {
                return AuthResult.Error(restoreResult.message)
            }
            business = businessBranchDao.getFirstBusiness()
        }

        if (business == null) {
            return AuthResult.Error(
                "No business configured. Run setup first."
            )
        }

        val branch = businessBranchDao.getFirstBranch(business.id)
            ?: return AuthResult.Error("No branch found for business")
        val identifier = normalizeLoginIdentifier("CASHIER", username, business.id, branch)
        var remainingLockout = pinThrottler.getRemainingLockoutSeconds(identifier.preResolutionKey)
        // BOARDLOCK_CASHIER_MISSING_ACCOUNT_RECONCILIATION
        // Preserve account lockout before any remote recovery attempt.
        val preRemaining = pinThrottler.getRemainingLockoutSeconds(identifier.preResolutionKey)
        if (preRemaining > 0L) return AuthResult.LockedOut(preRemaining)
        var user = resolveLoginLookup(identifier, business.id)
        if (user == null && identifier.canonicalPhone != null) {
            val recovery = remoteBusinessRestorer.restoreCashierIfEmpty(username, pin, branch.countryCode)
            when (recovery) {
                is RemoteBusinessRestorationResult.Success -> {
                    if (recovery.businessId != business.id || recovery.branchId != branch.id) {
                        return AuthResult.Error("Cashier belongs to a different business or branch")
                    }
                    user = resolveLoginLookup(identifier, business.id)
                    if (user == null) {
                        return AuthResult.Error("Cashier recovery did not restore the local account")
                    }
                }
                is RemoteBusinessRestorationResult.Failed -> {
                    // Keep detailed remote error out of the wrong-account response.
                    return AuthResult.Error(recovery.message)
                }
            }
        }
        val accountKey = user?.let { accountThrottleKey(business.id, it.id) }
        val accountRemaining = accountKey?.let {
            pinThrottler.getRemainingLockoutSeconds(it)
        } ?: 0L
        remainingLockout = maxOf(remainingLockout, accountRemaining)
        if (remainingLockout > 0L) return AuthResult.LockedOut(remainingLockout)

        val failureKey = resolvedFailureKey(business.id, identifier, user)

        if (
            user == null ||
            user.role != UserRole.CASHIER ||
            user.authType != AuthType.PIN
        ) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    failureKey
                )

            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(
                    lockoutSeconds
                )
            } else {
                AuthResult.InvalidCredentials(
                    "Invalid username or PIN"
                )
            }
        }

        var isValid = user.isActive && PinHasher.verifyPin(
            pin,
            user.credentialSalt ?: "",
            user.credentialHash
        )

        // A cashier may have accepted a fresh invite on the web landing page.
        // The cloud PIN and membership can then be newer than this device's local
        // projection. Only a successful cloud authentication can authorize the
        // local credential/permission refresh; never overwrite from an unverified PIN.
        if (!isValid && identifier.canonicalPhone != null) {
            when (
                val recovery = remoteBusinessRestorer.restoreCashierIfEmpty(
                    username,
                    pin,
                    branch.countryCode
                )
            ) {
                is RemoteBusinessRestorationResult.Success -> {
                    if (
                        recovery.businessId != business.id ||
                        recovery.branchId != branch.id
                    ) {
                        return AuthResult.Error(
                            "Cashier belongs to a different business or branch"
                        )
                    }
                    user = userDao.getUserById(recovery.userId)
                    if (
                        user == null ||
                        user.role != UserRole.CASHIER ||
                        user.authType != AuthType.PIN
                    ) {
                        return AuthResult.Error(
                            "Cloud cashier account could not be reconciled safely"
                        )
                    }
                    isValid = user.isActive && PinHasher.verifyPin(
                        pin,
                        user.credentialSalt ?: "",
                        user.credentialHash
                    )
                }
                is RemoteBusinessRestorationResult.Failed -> Unit
            }
        }

        if (user == null || !user.isActive || !isValid) {
            val lockoutSeconds = pinThrottler.recordFailure(failureKey)
            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(lockoutSeconds)
            } else {
                AuthResult.InvalidCredentials("Invalid username or PIN")
            }
        }

        val verifiedUser = user
            ?: return AuthResult.InvalidCredentials("Invalid username or PIN")

        val authenticatedUser =
            upgradeCashierCredentialIfNeeded(
                user = verifiedUser,
                pin = pin
            )

        if (authenticatedUser == null) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    failureKey
                )

            return if (lockoutSeconds > 0L) {
                AuthResult.LockedOut(
                    lockoutSeconds
                )
            } else {
                AuthResult.InvalidCredentials(
                    "Invalid username or PIN"
                )
            }
        }

        val membership =
            userDao.getMembership(
                authenticatedUser.id,
                branch.id
            )
                ?: return AuthResult.InvalidCredentials(
                    "Cashier is not authorized for this branch"
                )

        clearSuccessfulLoginThrottle(business.id, identifier, user)

        val session =
            ActiveSession(
                business,
                branch,
                authenticatedUser,
                membership,
                requiresPinChange = authenticatedUser.mustChangePin
            )

        // Cashier cloud authentication is required for remote sync.
        // Local/offline PIN authentication remains unchanged.
        var sessionToUse = session
        if (
            com.dailymoneyregister.core.cloud.sync.CashierCloudSession
                .tokenFor(membership.id) == null && identifier.canonicalPhone != null
        ) {
            val cloudAuthenticated = runCatching {
                com.dailymoneyregister.core.cloud.sync.CashierCloudSession.authenticate(
                    canonicalPhone = identifier.canonicalPhone,
                    pin = pin,
                    expectedMembershipId = membership.id
                )
            }.onFailure {
                android.util.Log.w("PaidDueFinancialSync", "CASHIER_CLOUD_AUTH_RETRY_FAILED")
            }.getOrDefault(false)

            if (!cloudAuthenticated) {
                // A web-accepted re-invitation creates a new authoritative membership ID.
                // Authenticate without assuming the stale local ID, then reconcile only
                // after the server confirms the same business and branch.
                when (
                    val recovery = remoteBusinessRestorer.restoreCashierIfEmpty(
                        username,
                        pin,
                        branch.countryCode
                    )
                ) {
                    is RemoteBusinessRestorationResult.Success -> {
                        if (
                            recovery.businessId == business.id &&
                            recovery.branchId == branch.id
                        ) {
                            val refreshedUser = userDao.getUserById(recovery.userId)
                            val refreshedMembership = userDao.getMembership(
                                recovery.userId,
                                branch.id
                            )
                            if (
                                refreshedUser != null &&
                                refreshedUser.role == UserRole.CASHIER &&
                                refreshedUser.isActive &&
                                refreshedMembership != null &&
                                com.dailymoneyregister.core.cloud.sync.CashierCloudSession
                                    .tokenFor(refreshedMembership.id) != null
                            ) {
                                sessionToUse = session.copy(
                                    user = refreshedUser,
                                    membership = refreshedMembership
                                )
                            }
                        }
                    }
                    is RemoteBusinessRestorationResult.Failed -> Unit
                }
            }
        }

        SessionManager.setSession(sessionToUse)

        trustedSessionStore.save(
            businessId = session.business.id,
            branchId = session.branch.id,
            userId = session.user.id
        )

        languageManager.setLanguage(
            authenticatedUser.languageCode
        )

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

