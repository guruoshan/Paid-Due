package com.dailymoneyregister.feature.invite.data
import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.HumanIdentityEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import javax.inject.Inject

data class InvitePreview(
    val invite: StaffInvite,
    val businessName: String,
    val branchName: String
)

sealed interface ActivationResult {

    data class Success(
        val session: ActiveSession
    ) : ActivationResult

    data object InvalidInvite :
        ActivationResult

    data object PhoneAlreadyRegistered :
        ActivationResult

    data object Failed :
        ActivationResult
}

/**
 * Cross-device employee activation.
 *
 * Supabase owns invite validity, Cashier identity, membership and invite
 * consumption. Room materializes the authoritative result for local/offline
 * operation after the remote transaction commits.
 */
class InviteActivationRepository @Inject constructor(
    private val database: DmrDatabase,
    private val inviteService: InviteService,
    private val remoteInviteStore: RemoteInviteStore,
    private val recoveryStore: InviteActivationRecoveryStore,
    private val trustedSessionStore: TrustedSessionStore,
    private val languageManager: LanguageManager
) {

    private val userDao =
        database.userDao()

    private val businessDao =
        database.businessBranchDao()

    suspend fun preview(
        token: String
    ): InvitePreview? {

        if (token.isBlank()) {
            return null
        }

        val hash =
            inviteService.hashToken(token)

        val remote =
            remoteInviteStore
                .previewByTokenHash(hash)
                ?: return null

        val invite = remote.invite

        if (invite.role != UserRole.CASHIER) {
            return null
        }

        return InvitePreview(
            invite = invite,
            businessName = remote.businessName,
            branchName = remote.branchName
        )
    }

    suspend fun activate(
        token: String,
        pin: String
    ): ActivationResult {

        if (
            token.isBlank() ||
            !PinHasher.isValidPin(pin)
        ) {
            return ActivationResult.Failed
        }

        val tokenHash =
            inviteService.hashToken(token)

        /*
         * If no recovery evidence exists, this is a new activation.
         * Preview and local-conflict checks happen BEFORE the remote
         * transaction is allowed to consume the invitation.
         *
         * If recovery evidence exists, the backend may already have
         * committed. Preview may therefore legitimately return no row.
         */
        val existingRecovery =
            recoveryStore.load(
                tokenHash
            )

        val canonicalPhone =
            if (
                existingRecovery == null
            ) {

                val invitation =
                    preview(token)
                        ?: return ActivationResult.InvalidInvite

                if (
                    hasPreActivationLocalConflict(
                        invitation
                    )
                ) {
                    return ActivationResult.PhoneAlreadyRegistered
                }

                invitation
                    .invite
                    .canonicalPhone

            } else {

                existingRecovery
                    .canonicalPhone
            }

        val credential =
            try {

                recoveryStore.getOrCreate(
                    tokenHash =
                        tokenHash,

                    pin =
                        pin,

                    canonicalPhone =
                        canonicalPhone
                )

            } catch (e: Exception) {
                return ActivationResult.Failed
            }

        val remoteResult =
            try {
                remoteInviteStore.activate(
                    token = token,
                    pin = pin
                )
            } catch (e: Exception) {
                /*
                 * Keep recovery evidence.
                 *
                 * The network may have failed after the PostgreSQL
                 * transaction committed. The next attempt must reuse
                 * exactly the same verifier/salt.
                 */
                return ActivationResult.Failed
            }

        return when (remoteResult) {

            RemoteStaffActivationResult.InvalidInvite -> {

                runCatching {
                    recoveryStore.clear(tokenHash)
                }

                ActivationResult.InvalidInvite
            }

            RemoteStaffActivationResult.PhoneAlreadyRegistered -> {

                runCatching {
                    recoveryStore.clear(tokenHash)
                }

                ActivationResult.PhoneAlreadyRegistered
            }

            is RemoteStaffActivationResult.Activated -> {

                val session =
                    try {
                        materializeLocal(
                            activation =
                                remoteResult.activation,
                            credentialHash =
                                credential.credentialHash,
                            credentialSalt =
                                credential.credentialSalt
                        )
                    } catch (_: Exception) {

                        /*
                         * Do NOT clear recovery here.
                         *
                         * Remote activation may already be committed.
                         * Exact retry must remain possible.
                         */
                        return ActivationResult.Failed
                    }

                try {
                    trustedSessionStore.save(
                        businessId =
                            session.business.id,
                        branchId =
                            session.branch.id,
                        userId =
                            session.user.id
                    )

                    recoveryStore.clear(
                        tokenHash
                    )
                } catch (_: Exception) {
                    return ActivationResult.Failed
                }

                SessionManager.setSession(
                    session
                )

                languageManager.setLanguage(
                    session.user.languageCode
                )

                ActivationResult.Success(
                    session
                )
            }
        }
    }

    private suspend fun hasPreActivationLocalConflict(
        preview: InvitePreview
    ): Boolean {

        val invite = preview.invite

        val firstBusiness =
            businessDao.getFirstBusiness()

        if (
            firstBusiness != null &&
            firstBusiness.id != invite.businessId
        ) {
            /*
             * Local multi-business switching is not part of this phase.
             * Never consume a remote invite into an ambiguous local DB.
             */
            return true
        }

        if (
            userDao.getHumanIdentityByPhone(
                invite.canonicalPhone
            ) != null
        ) {
            return true
        }

        if (
            userDao.getUserByCanonicalPhone(
                invite.businessId,
                invite.canonicalPhone
            ) != null
        ) {
            return true
        }

        val username =
            localUsername(
                invite.canonicalPhone
            )

        return userDao.getUserByUsername(
            invite.businessId,
            username
        ) != null
    }

    private suspend fun materializeLocal(
        activation: RemoteStaffActivation,
        credentialHash: String,
        credentialSalt: String
    ): ActiveSession {

        val taxRoundingMode =
            TaxRoundingMode.valueOf(
                activation.taxRoundingMode
            )

        val languageCode =
            languageManager
                .currentLanguage
                .value
                .code

        return database.withTransaction {

            val firstBusiness =
                businessDao.getFirstBusiness()

            check(
                firstBusiness == null ||
                    firstBusiness.id ==
                    activation.businessId
            ) {
                "Local device belongs to another business"
            }

            val business =
                businessDao.getBusinessById(
                    activation.businessId
                )
                    ?: BusinessEntity(
                        id = activation.businessId,
                        name = activation.businessName,
                        createdAt =
                            activation.activatedAtMillis,
                        updatedAt =
                            activation.activatedAtMillis
                    ).also {
                        businessDao.insertBusiness(it)
                    }

            val existingBranch =
                businessDao.getBranchById(
                    activation.branchId
                )

            check(
                existingBranch == null ||
                    existingBranch.businessId ==
                    activation.businessId
            ) {
                "Local branch identity conflict"
            }

            val branch =
                existingBranch
                    ?: BranchEntity(
                        id = activation.branchId,
                        businessId =
                            activation.businessId,
                        name =
                            activation.branchName,
                        code =
                            activation.branchCode,
                        countryCode =
                            activation.countryCode,
                        currencyCode =
                            activation.currencyCode,
                        currencyDecimalPlaces =
                            activation
                                .currencyDecimalPlaces,
                        currencySymbol =
                            activation.currencySymbol,
                        timezoneId =
                            activation.timezoneId,
                        taxRoundingMode =
                            taxRoundingMode,
                        defaultTaxRateBps =
                            activation.defaultTaxRateBps,
                        address =
                            activation.branchAddress,
                        phone =
                            activation.branchPhone,
                        taxRegistrationNumber =
                            activation
                                .taxRegistrationNumber,
                        createdAt =
                            activation.activatedAtMillis,
                        updatedAt =
                            activation.activatedAtMillis
                    ).also {
                        businessDao.insertBranch(it)
                    }

            val localUserId =
                activation.localUserId

            val username =
                localUsername(
                    activation.canonicalPhone
                )

            val identityById =
                userDao.getHumanIdentityById(
                    localUserId
                )

            val identityByPhone =
                userDao.getHumanIdentityByPhone(
                    activation.canonicalPhone
                )

            check(
                identityByPhone == null ||
                    identityByPhone.id ==
                    localUserId
            ) {
                "Local phone identity belongs to another user"
            }

            if (identityById == null) {

                check(identityByPhone == null)

                userDao.insertHumanIdentity(
                    HumanIdentityEntity(
                        id = localUserId,
                        canonicalPhone =
                            activation.canonicalPhone,
                        displayName =
                            activation.displayName,
                        isActive = true,
                        createdAt =
                            activation.activatedAtMillis,
                        updatedAt =
                            activation.activatedAtMillis
                    )
                )
            } else {

                check(
                    identityById.canonicalPhone ==
                        activation.canonicalPhone &&
                        identityById.isActive
                ) {
                    "Local identity does not match remote activation"
                }
            }

            val existingByPhone =
                userDao.getUserByCanonicalPhone(
                    activation.businessId,
                    activation.canonicalPhone
                )

            check(
                existingByPhone == null ||
                    existingByPhone.id ==
                    localUserId
            ) {
                "Local Cashier phone already belongs to another account"
            }

            val existingByUsername =
                userDao.getUserByUsername(
                    activation.businessId,
                    username
                )

            check(
                existingByUsername == null ||
                    existingByUsername.id ==
                    localUserId
            ) {
                "Local Cashier username already belongs to another account"
            }

            var user =
                userDao.getUserById(
                    localUserId
                )

            if (user == null) {

                user =
                    UserAccountEntity(
                        id = localUserId,
                        identityId = localUserId,
                        businessId =
                            activation.businessId,
                        displayName =
                            activation.displayName,
                        username = username,
                        role = UserRole.CASHIER,
                        authType = AuthType.PIN,
                        credentialHash =
                            credentialHash,
                        credentialSalt =
                            credentialSalt,
                        languageCode =
                            languageCode,
                        isActive = true,

                        /*
                         * The employee selected this PIN personally
                         * during activation. It is not a temporary
                         * Admin-created PIN.
                         */
                        mustChangePin = false,

                        createdAt =
                            activation.activatedAtMillis,
                        updatedAt =
                            activation.activatedAtMillis
                    )

                userDao.insertUser(user)
            } else {

                check(
                    user.businessId ==
                        activation.businessId &&
                        user.identityId ==
                        localUserId &&
                        user.role ==
                        UserRole.CASHIER &&
                        user.authType ==
                        AuthType.PIN &&
                        user.isActive &&
                        user.credentialHash ==
                        credentialHash &&
                        user.credentialSalt ==
                        credentialSalt
                ) {
                    "Existing local Cashier does not match authoritative activation"
                }
            }

            var membership =
                userDao.getMembership(
                    localUserId,
                    activation.branchId
                )

            if (membership == null) {

                membership =
                    UserBranchMembershipEntity(
                        id =
                            activation.membershipId,
                        userId =
                            localUserId,
                        branchId =
                            activation.branchId,
                        role =
                            UserRole.CASHIER,

                        canCreateSale =
                            permission(
                                activation,
                                "CREATE_SALE",
                                true
                            ),

                        canAcceptCash =
                            permission(
                                activation,
                                "ACCEPT_CASH",
                                true
                            ),

                        canAcceptOnline =
                            permission(
                                activation,
                                "ACCEPT_ONLINE",
                                true
                            ),

                        canCreateCreditSale =
                            permission(
                                activation,
                                "CREATE_CREDIT_SALE",
                                false
                            ),

                        canUseCoupon =
                            permission(
                                activation,
                                "USE_COUPON",
                                false
                            ),

                        canIssueInvoice =
                            permission(
                                activation,
                                "ISSUE_INVOICE",
                                true
                            ),

                        canIssueReceipt =
                            permission(
                                activation,
                                "ISSUE_RECEIPT",
                                true
                            ),

                        canRefund =
                            permission(
                                activation,
                                "REFUND",
                                false
                            ),

                        canVoid =
                            permission(
                                activation,
                                "VOID",
                                false
                            ),

                        canCorrectAmount =
                            permission(
                                activation,
                                "CORRECT_AMOUNT",
                                false
                            ),

                        canChangePaymentMethod =
                            permission(
                                activation,
                                "CHANGE_PAYMENT_METHOD",
                                false
                            ),

                        canViewBroadHistory =
                            permission(
                                activation,
                                "VIEW_BROAD_HISTORY",
                                false
                            ),

                        canAccessCustomerDirectory =
                            permission(
                                activation,
                                "CUSTOMER_DIRECTORY_ACCESS",
                                false
                            ),

                        canManageProducts =
                            permission(
                                activation,
                                "MANAGE_PRODUCTS",
                                false
                            ),

                        canViewReports =
                            permission(
                                activation,
                                "VIEW_REPORTS",
                                false
                            ),

                        createdAt =
                            activation.activatedAtMillis
                    )

                userDao.insertMembership(
                    membership
                )
            } else {

                check(
                    membership.id ==
                        activation.membershipId &&
                        membership.userId ==
                        localUserId &&
                        membership.branchId ==
                        activation.branchId &&
                        membership.role ==
                        UserRole.CASHIER
                ) {
                    "Existing local membership does not match authoritative activation"
                }
            }

            ActiveSession(
                business = business,
                branch = branch,
                user = user,
                membership = membership,
                requiresPinChange = false
            )
        }
    }

    private fun permission(
        activation: RemoteStaffActivation,
        key: String,
        defaultValue: Boolean
    ): Boolean =
        activation.permissions[key]
            ?: defaultValue

    private fun localUsername(
        canonicalPhone: String
    ): String =
        canonicalPhone
            .filter(Char::isDigit)
}