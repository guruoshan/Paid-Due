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

    private fun isClearlyCloudUnavailable(message: String): Boolean {
        val normalized = message.lowercase()
        if (listOf("invalid login", "invalid credentials", "invalid password", "unauthorized",
                "forbidden", "membership is missing", "does not match", "different business",
                "different branch", "not authorized", "disabled", "inactive").any(normalized::contains)
        ) return false
        return listOf("timeout", "timed out", "unavailable", "network", "connection",
            "resolve host", "unknown host", "socket", "offline", "could not connect",
            "failed to connect", "connection reset", "connection refused", "dns").any(normalized::contains)
    }

    suspend fun loginAdmin(
        username: String,
        passwordPlain: String
    ): AuthResult {

        var business = businessBranchDao.getFirstBusiness()
        if (business == null) {
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
        val user = resolveLoginLookup(identifier, business.id)
        val accountKey = user?.let { accountThrottleKey(business.id, it.id) }
        val accountRemaining = accountKey?.let {
            pinThrottler.getRemainingLockoutSeconds(it)
        } ?: 0L
        remainingLockout = maxOf(remainingLockout, accountRemaining)
        if (remainingLockout > 0L) return AuthResult.LockedOut(remainingLockout)

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

        if (user.role != UserRole.ADMIN || user.authType != AuthType.PASSWORD) {
            val lockoutSeconds = pinThrottler.recordFailure(failureKey)
            return if (lockoutSeconds > 0L) AuthResult.LockedOut(lockoutSeconds)
            else AuthResult.InvalidCredentials("Invalid username or password")
        }

        // Supabase verifies the password and exact tenant scope first. The local
        // password hash is consulted only when the server is unavailable and a
        // previously verified offline lease may be used.
        val membership = userDao.getMembership(user.id, branch.id)
            ?: return AuthResult.Error("Supabase could not confirm Admin authorization for this business branch.")

        val cloudAuth = try {
            withTimeoutOrNull(3_500L) {
                remoteBusinessRestorer.authenticateAdminForSync(
                    usernameOrEmail = username,
                    passwordPlain = passwordPlain,
                    expectedBusinessId = business.id,
                    expectedBranchId = branch.id,
                    expectedUserId = user.id,
                    expectedMembershipId = membership.id
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not turn an explicit/unknown authorization error into an outage.
            // Only recognizable transport failures may use the bounded offline lease.
            val message = error.message.orEmpty()
            if (isClearlyCloudUnavailable(message)) null
            else RemoteBusinessRestorationResult.Failed(
                message.ifBlank { "Supabase rejected Admin authorization." }
            )
        }

        val authenticatedUser = when (cloudAuth) {
            is RemoteBusinessRestorationResult.Success -> user
            else -> {
                val unavailable = cloudAuth == null ||
                    (cloudAuth is RemoteBusinessRestorationResult.Failed &&
                        isClearlyCloudUnavailable(cloudAuth.message))
                if (!unavailable) {
                    val lockoutSeconds = pinThrottler.recordFailure(failureKey)
                    return if (lockoutSeconds > 0L) AuthResult.LockedOut(lockoutSeconds)
                    else AuthResult.InvalidCredentials("Supabase rejected Admin authorization.")
                }

                val localPasswordMatches = user.isActive && PasswordHasher.verifyPassword(
                    passwordPlain, user.credentialSalt ?: "", user.credentialHash
                )
                if (!localPasswordMatches) {
                    val lockoutSeconds = pinThrottler.recordFailure(failureKey)
                    return if (lockoutSeconds > 0L) AuthResult.LockedOut(lockoutSeconds)
                    else AuthResult.InvalidCredentials("Invalid username or password")
                }

                val offlineUser = upgradeAdminCredentialIfNeeded(user, passwordPlain)
                    ?: return AuthResult.InvalidCredentials("Invalid username or password")
                if (!trustedSessionStore.isOfflineLeaseValidFor(business.id, branch.id, offlineUser.id)) {
                    return AuthResult.Error(
                        "Supabase is unavailable and no valid 24-hour offline lease exists. Retry when connected; the credentials have not been classified as invalid."
                    )
                }
                val offlineSession = ActiveSession(
                    business, branch, offlineUser, membership, isOfflineLease = true
                )
                trustedSessionStore.clearAppBackgroundMarker()
                SessionManager.setSession(offlineSession)
                languageManager.setLanguage(offlineUser.languageCode)
                android.util.Log.w("PaidDueFinancialSync", "ADMIN_OFFLINE_LEASE_ACCEPTED")
                return AuthResult.Success(offlineSession)
            }
        }

        clearSuccessfulLoginThrottle(business.id, identifier, user)
        android.util.Log.i(
            "PaidDueFinancialSync",
            "ADMIN_SUPABASE_AUTHENTICATED membership=${membership.id}"
        )

        val session = ActiveSession(business, branch, authenticatedUser, membership)
        SessionManager.setSession(session)
        trustedSessionStore.save(
            businessId = session.business.id,
            branchId = session.branch.id,
            userId = session.user.id
        )
        languageManager.setLanguage(authenticatedUser.languageCode)
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

        // Membership identity is resolved locally only to select the tenant scope.
        // The server verifies the PIN and current membership before a normal session
        // is created; the cached PIN hash is used only when Supabase is unavailable.
        val membership = userDao.getMembership(user.id, branch.id)
            ?: return AuthResult.InvalidCredentials("Cashier is not authorized for this branch")

        val canonicalPhone = identifier.canonicalPhone
            ?: return AuthResult.Error("A valid international phone number is required.")

        val cloudAuth = try {
            withTimeoutOrNull(3_500L) {
                com.dailymoneyregister.core.cloud.sync.CashierCloudSession.authenticate(
                    canonicalPhone = canonicalPhone,
                    pin = pin,
                    expectedMembershipId = membership.id
                )
            } ?: com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome.UNAVAILABLE
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome.UNAVAILABLE
        }

        val authenticatedUser = when (cloudAuth) {
            com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome.AUTHENTICATED -> user
            com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome.REJECTED -> {
                val lockoutSeconds = pinThrottler.recordFailure(failureKey)
                return if (lockoutSeconds > 0L) AuthResult.LockedOut(lockoutSeconds)
                else AuthResult.InvalidCredentials("Supabase rejected cashier authorization.")
            }
            com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome.INVALID_INPUT -> {
                val lockoutSeconds = pinThrottler.recordFailure(failureKey)
                return if (lockoutSeconds > 0L) AuthResult.LockedOut(lockoutSeconds)
                else AuthResult.InvalidCredentials("Invalid phone number or PIN.")
            }
            com.dailymoneyregister.core.cloud.sync.CashierCloudAuthOutcome.UNAVAILABLE -> {
                // Offline login is permitted only when both the cached credential
                // and the previously server-verified, identity-bound lease are valid.
                if (!user.isActive || !PinHasher.verifyPin(pin, user.credentialSalt ?: "", user.credentialHash)) {
                    val lockoutSeconds = pinThrottler.recordFailure(failureKey)
                    return if (lockoutSeconds > 0L) AuthResult.LockedOut(lockoutSeconds)
                    else AuthResult.InvalidCredentials("Invalid username or PIN")
                }
                val offlineUser = upgradeCashierCredentialIfNeeded(user, pin)
                    ?: return AuthResult.InvalidCredentials("Invalid username or PIN")
                val offlineSession = ActiveSession(
                    business, branch, offlineUser, membership,
                    requiresPinChange = offlineUser.mustChangePin,
                    isOfflineLease = true
                )
                if (!trustedSessionStore.isOfflineLeaseValidFor(business.id, branch.id, offlineUser.id)) {
                    return AuthResult.Error(
                        "Supabase is unavailable and no valid 24-hour offline lease exists. Retry when connected; the credentials have not been classified as invalid."
                    )
                }
                clearSuccessfulLoginThrottle(business.id, identifier, user)
                trustedSessionStore.clearAppBackgroundMarker()
                SessionManager.setSession(offlineSession)
                languageManager.setLanguage(offlineUser.languageCode)
                android.util.Log.w("PaidDueFinancialSync", "CASHIER_OFFLINE_LEASE_ACCEPTED")
                return AuthResult.Success(offlineSession)
            }
        }

        // Refresh authorization from Supabase before constructing the local session.
        // A stale Room permission snapshot must never grant a permission removed online.
        val metadata = try {
            withTimeoutOrNull(3_500L) {
                com.dailymoneyregister.core.cloud.sync.CashierCloudSession.fetchMetadata(membership.id)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return AuthResult.Error(
            "Supabase authenticated the cashier but current permissions could not be confirmed. Retry when connected."
        )

        if (
            metadata.optString("membership_id") != membership.id ||
            metadata.optString("business_id") != business.id ||
            metadata.optString("branch_id") != branch.id ||
            metadata.optString("role") != "CASHIER"
        ) {
            com.dailymoneyregister.core.cloud.sync.CashierCloudSession.clear()
            return AuthResult.InvalidCredentials("Supabase rejected cashier membership authorization.")
        }

        val cloudPermissions = metadata.optJSONObject("permissions")
            ?: return AuthResult.Error("Supabase returned no cashier permissions.")
        val requiredPermissionKeys = listOf(
            "can_create_sale", "can_accept_cash", "can_accept_online",
            "can_create_credit_sale", "can_use_coupon", "can_issue_invoice",
            "can_issue_receipt", "can_refund", "can_void", "can_correct_amount",
            "can_change_payment_method", "can_view_broad_history",
            "can_manage_products", "can_view_reports"
        )
        if (requiredPermissionKeys.any { !cloudPermissions.has(it) || cloudPermissions.isNull(it) }) {
            return AuthResult.Error("Supabase returned an incomplete cashier permission set.")
        }
        val authorizedMembership = membership.copy(
            canCreateSale = cloudPermissions.getBoolean("can_create_sale"),
            canAcceptCash = cloudPermissions.getBoolean("can_accept_cash"),
            canAcceptOnline = cloudPermissions.getBoolean("can_accept_online"),
            canCreateCreditSale = cloudPermissions.getBoolean("can_create_credit_sale"),
            canUseCoupon = cloudPermissions.getBoolean("can_use_coupon"),
            canIssueInvoice = cloudPermissions.getBoolean("can_issue_invoice"),
            canIssueReceipt = cloudPermissions.getBoolean("can_issue_receipt"),
            canRefund = cloudPermissions.getBoolean("can_refund"),
            canVoid = cloudPermissions.getBoolean("can_void"),
            canCorrectAmount = cloudPermissions.getBoolean("can_correct_amount"),
            canChangePaymentMethod = cloudPermissions.getBoolean("can_change_payment_method"),
            canViewBroadHistory = cloudPermissions.getBoolean("can_view_broad_history"),
            canManageProducts = cloudPermissions.getBoolean("can_manage_products"),
            canViewReports = cloudPermissions.getBoolean("can_view_reports")
        )
        userDao.updateMembership(authorizedMembership)

        clearSuccessfulLoginThrottle(business.id, identifier, user)
        val session = ActiveSession(
            business,
            branch,
            authenticatedUser,
            authorizedMembership,
            requiresPinChange = authenticatedUser.mustChangePin
        )



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

