package com.dailymoneyregister.feature.setup.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.BranchId
import com.dailymoneyregister.core.model.ids.BusinessId
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.UserId
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PasswordHasher
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import javax.inject.Inject

data class SetupParams(
    val businessName: String,
    val branchName: String,
    val branchCode: String,
    val countryCode: String,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val currencySymbol: String,
    val timezoneId: String,
    val taxRoundingMode: TaxRoundingMode,
    val defaultTaxRateBps: Int,
    val adminDisplayName: String,
    val adminUsername: String,
    val adminPhone: String? = null,
    val adminPasswordPlain: String,
    val languageCode: String
)

data class SetupResult(
    val businessId: BusinessId,
    val branchId: BranchId,
    val adminUserId: UserId
)

class SetupRepository @Inject constructor(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val trustedSessionStore: TrustedSessionStore
) {
    private val businessBranchDao = database.businessBranchDao()
    private val userDao = database.userDao()

    suspend fun establishAdminSession(result: SetupResult) {
        val business = businessBranchDao.getBusinessById(result.businessId.value)
            ?: error("Setup business was not persisted")
        val branch = businessBranchDao.getBranchById(result.branchId.value)
            ?: error("Setup branch was not persisted")
        val user = userDao.getUserById(result.adminUserId.value)
            ?: error("Setup admin was not persisted")
        val membership = userDao.getMembership(user.id, branch.id)
            ?: error("Setup admin membership was not persisted")
        SessionManager.setSession(ActiveSession(business, branch, user, membership))
        trustedSessionStore.save(
            businessId = business.id,
            branchId = branch.id,
            userId = user.id
        )
    }

    suspend fun isSetupComplete(): Boolean {
        return businessBranchDao.getFirstBusiness() != null && userDao.getFirstAdmin(
            businessBranchDao.getFirstBusiness()?.id ?: ""
        ) != null
    }

    suspend fun executeSetup(
        params: SetupParams,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<SetupResult> {
        val requestedFingerprint = SetupPayloadCodec.fingerprint(params)
        val existingOperation = database.operationDao().getOperationById(operationId.value)

        val payload = if (existingOperation != null) {
            if (existingOperation.requestFingerprint != requestedFingerprint) {
                return CommandResult.Conflict(
                    "Conflict: Setup Operation ID '${operationId.value}' was already used with different setup details."
                )
            }

            val savedPayload = try {
                SetupPayloadCodec.decode(existingOperation.intentPayload)
            } catch (error: Exception) {
                return CommandResult.Error(error)
            }

            if (
                savedPayload.operationId != operationId.value ||
                savedPayload.businessId != existingOperation.businessId ||
                savedPayload.branchId != existingOperation.branchId ||
                savedPayload.adminUserId != existingOperation.actorUserId
            ) {
                return CommandResult.Conflict(
                    "Conflict: Saved Setup recovery payload does not match its original Operation."
                )
            }

            if (
                !PasswordHasher.verifyPassword(
                    params.adminPasswordPlain,
                    savedPayload.adminCredentialSalt,
                    savedPayload.adminCredentialHash
                )
            ) {
                return CommandResult.Conflict(
                    "Conflict: Setup password does not match the original request for this Operation ID."
                )
            }

            savedPayload
        } else {
            val businessId = BusinessId.generate()
            val branchId = BranchId.generate()
            val adminId = UserId.generate()
            val membershipId = IdGenerator.newId()
            val now = System.currentTimeMillis()

            val passwordPolicyError =
                PasswordHasher.validateNewPassword(
                    password = params.adminPasswordPlain,
                    username = params.adminUsername
                )

            if (passwordPolicyError != null) {
                return CommandResult.Error(
                    IllegalArgumentException(
                        passwordPolicyError
                    )
                )
            }

            val salt =
                PasswordHasher.generateSalt()

            val hash =
                PasswordHasher.hashPassword(
                    params.adminPasswordPlain,
                    salt
                )

            val canonicalPhone = params.adminPhone?.let {
                PhoneNumberNormalizer.normalize(it, params.countryCode)
            }

            SetupRecoveryPayload(
                operationId = operationId.value,
                businessId = businessId.value,
                branchId = branchId.value,
                adminUserId = adminId.value,
                membershipId = membershipId,
                occurredAt = now,
                deviceId = deviceIdentityProvider.deviceId,

                businessName = params.businessName,
                branchName = params.branchName,
                branchCode = params.branchCode,
                countryCode = params.countryCode,
                currencyCode = params.currencyCode,
                currencyDecimalPlaces = params.currencyDecimalPlaces,
                currencySymbol = params.currencySymbol,
                timezoneId = params.timezoneId,
                taxRoundingMode = params.taxRoundingMode,
                defaultTaxRateBps = params.defaultTaxRateBps,

                adminDisplayName = params.adminDisplayName,
                adminUsername = params.adminUsername,
                adminCanonicalPhone = canonicalPhone,
                adminCredentialHash = hash,
                adminCredentialSalt = salt,
                languageCode = params.languageCode
            )
        }

        val businessId = BusinessId(payload.businessId)
        val branchId = BranchId(payload.branchId)
        val adminId = UserId(payload.adminUserId)
        val membershipId = payload.membershipId
        val now = payload.occurredAt

        val command = object : OperationCommand {
            override val operationId = operationId
            override val businessId = payload.businessId
            override val branchId = payload.branchId
            override val operationType = OperationType.CREATE_BUSINESS
            override val subjectType = SubjectType.BUSINESS
            override val subjectId = payload.businessId
            override val transactionId: String? = null
            override val actorUserId = payload.adminUserId
            override val deviceId = payload.deviceId
            override val occurredAt = payload.occurredAt
            override val commandSchemaVersion = SetupPayloadCodec.CURRENT_SCHEMA_VERSION
            override val requestFingerprint = requestedFingerprint
            override val intentPayload = SetupPayloadCodec.encode(payload)
        }

        return commandExecutor.execute(
            command = command,
            replayResolver = { op ->
                val existingBusiness = businessBranchDao.getBusinessById(op.businessId)
                    ?: throw IllegalStateException("Replay failed: Business '${op.businessId}' not found")

                val replayBranchId = op.branchId
                    ?: throw IllegalStateException("Replay failed: Setup operation has no branch ID")

                val existingBranch = businessBranchDao.getBranchById(replayBranchId)
                    ?: throw IllegalStateException("Replay failed: Branch '$replayBranchId' not found")

                val existingAdmin = userDao.getUserById(op.actorUserId)
                    ?: throw IllegalStateException("Replay failed: Admin '${op.actorUserId}' not found")

                if (
                    existingBranch.businessId != existingBusiness.id ||
                    existingAdmin.businessId != existingBusiness.id
                ) {
                    throw IllegalStateException("Replay failed: Setup entities do not belong to the same business")
                }

                SetupResult(
                    businessId = BusinessId(existingBusiness.id),
                    branchId = BranchId(existingBranch.id),
                    adminUserId = UserId(existingAdmin.id)
                )
            }
        ) {
            val business = BusinessEntity(
                id = businessId.value,
                name = payload.businessName,
                createdAt = now,
                updatedAt = now
            )
            businessBranchDao.insertBusiness(business)

            val branch = BranchEntity(
                id = branchId.value,
                businessId = businessId.value,
                name = payload.branchName,
                code = payload.branchCode,
                countryCode = payload.countryCode,
                currencyCode = payload.currencyCode,
                currencyDecimalPlaces = payload.currencyDecimalPlaces,
                currencySymbol = payload.currencySymbol,
                timezoneId = payload.timezoneId,
                taxRoundingMode = payload.taxRoundingMode,
                defaultTaxRateBps = payload.defaultTaxRateBps,
                createdAt = now,
                updatedAt = now
            )
            businessBranchDao.insertBranch(branch)

            val admin = UserAccountEntity(
                id = adminId.value,
                identityId = adminId.value,
                businessId = businessId.value,
                displayName = payload.adminDisplayName,
                username = payload.adminUsername,
                role = UserRole.ADMIN,
                authType = AuthType.PASSWORD,
                credentialHash = payload.adminCredentialHash,
                credentialSalt = payload.adminCredentialSalt,
                languageCode = payload.languageCode,
                isActive = true,
                createdAt = now,
                updatedAt = now
            )
            userDao.insertHumanIdentity(
                com.dailymoneyregister.core.database.entity.HumanIdentityEntity(
                    id = adminId.value,
                    canonicalPhone = payload.adminCanonicalPhone,
                    displayName = payload.adminDisplayName,
                    createdAt = now,
                    updatedAt = now
                )
            )
            userDao.insertUser(admin)

            val membership = UserBranchMembershipEntity(
                id = membershipId,
                userId = adminId.value,
                branchId = branchId.value,
                role = UserRole.ADMIN,
                canCreateSale = true,
                canAcceptCash = true,
                canAcceptOnline = true,
                canCreateCreditSale = true,
                canUseCoupon = true,
                canIssueInvoice = true,
                canIssueReceipt = true,
                canRefund = true,
                canVoid = true,
                canCorrectAmount = true,
                canChangePaymentMethod = true,
                canViewBroadHistory = true,
                canManageProducts = true,
                canViewReports = true,
                createdAt = now
            )
            userDao.insertMembership(membership)

            val result = SetupResult(businessId, branchId, adminId)
            Pair(result, Pair("BUSINESS", businessId.value))
        }
    }
}





