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
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
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
    private val languageManager: LanguageManager
) {

    private companion object {
        const val DUMMY_PASSWORD_SALT = "00112233445566778899aabbccddeeff"
        const val DUMMY_PASSWORD_HASH =
            "pbkdf2-sha256:600000:c6339c1fdf3e142f79b1a690058124a64946b2e186fda750547c06c94ba2304c"
    }

    private val businessBranchDao =
        database.businessBranchDao()

    private val userDao =
        database.userDao()

    suspend fun loginAdmin(
        username: String,
        passwordPlain: String
    ): AuthResult {

        val business =
            businessBranchDao.getFirstBusiness()
                ?: return AuthResult.Error(
                    "No business configured. Run setup first."
                )

        val branch = businessBranchDao.getFirstBranch(business.id)
            ?: return AuthResult.Error("No branch found for business")
        val canonicalPhone = runCatching {
            PhoneNumberNormalizer.normalize(username, branch.countryCode)
        }.getOrNull()
        val cleanUsername = username.trim()

        val throttleIdentifier =
            "admin:${canonicalPhone ?: cleanUsername}"

        if (
            pinThrottler.isLockedOut(
                throttleIdentifier
            )
        ) {
            return AuthResult.LockedOut(
                pinThrottler
                    .getRemainingLockoutSeconds(
                        throttleIdentifier
                    )
            )
        }

        val user = canonicalPhone?.let { userDao.getUserByCanonicalPhone(business.id, it) }
            ?: userDao.getUserByUsername(business.id, cleanUsername)?.takeIf {
                userDao.getHumanIdentityById(it.identityId ?: it.id)?.canonicalPhone == null
            }

        if (user == null) {
            PasswordHasher.verifyPassword(
                passwordPlain,
                DUMMY_PASSWORD_SALT,
                DUMMY_PASSWORD_HASH
            )

            val lockoutSeconds =
                pinThrottler.recordFailure(
                    throttleIdentifier
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
                    throttleIdentifier
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
                    throttleIdentifier
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
                    throttleIdentifier
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

        pinThrottler.recordSuccess(
            throttleIdentifier
        )

        val membership =
            userDao.getMembership(
                authenticatedUser.id,
                branch.id
            )

        val session =
            ActiveSession(
                business,
                branch,
                authenticatedUser,
                membership
            )

        SessionManager.setSession(session)

        languageManager.setLanguage(
            authenticatedUser.languageCode
        )

        return AuthResult.Success(session)
    }

    suspend fun loginCashier(
        username: String,
        pin: String
    ): AuthResult {

        val business =
            businessBranchDao.getFirstBusiness()
                ?: return AuthResult.Error(
                    "No business configured. Run setup first."
                )

        val branch = businessBranchDao.getFirstBranch(business.id)
            ?: return AuthResult.Error("No branch found for business")
        val canonicalPhone = runCatching {
            PhoneNumberNormalizer.normalize(username, branch.countryCode)
        }.getOrNull()
        val cleanUsername = username.trim()
        val throttleIdentifier = canonicalPhone ?: cleanUsername

        if (
            pinThrottler.isLockedOut(
                throttleIdentifier
            )
        ) {
            return AuthResult.LockedOut(
                pinThrottler
                    .getRemainingLockoutSeconds(
                        cleanUsername
                    )
            )
        }

        val user = canonicalPhone?.let { userDao.getUserByCanonicalPhone(business.id, it) }
            ?: userDao.getUserByUsername(business.id, cleanUsername)?.takeIf {
                userDao.getHumanIdentityById(it.identityId ?: it.id)?.canonicalPhone == null
            }

        if (
            user == null ||
            user.role != UserRole.CASHIER ||
            user.authType != AuthType.PIN
        ) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    throttleIdentifier
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

        if (!user.isActive) {
            return AuthResult.InvalidCredentials(
                "Invalid username or PIN"
            )
        }

        val isValid =
            PinHasher.verifyPin(
                pin,
                user.credentialSalt ?: "",
                user.credentialHash
            )

        if (!isValid) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                throttleIdentifier
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

        val authenticatedUser =
            upgradeCashierCredentialIfNeeded(
                user = user,
                pin = pin
            )

        if (authenticatedUser == null) {
            val lockoutSeconds =
                pinThrottler.recordFailure(
                    throttleIdentifier
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

        pinThrottler.recordSuccess(
            throttleIdentifier
        )

        val session =
            ActiveSession(
                business,
                branch,
                authenticatedUser,
                membership,
                requiresPinChange = authenticatedUser.mustChangePin
            )

        SessionManager.setSession(session)

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
