package com.dailymoneyregister.feature.admin.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.UserId
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.security.PinHasher
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

data class CashierPermissionsConfig(
    val canCreateSale: Boolean = true,
    val canAcceptCash: Boolean = true,
    val canAcceptOnline: Boolean = true,
    val canCreateCreditSale: Boolean = false,
    val canUseCoupon: Boolean = false,
    val canIssueInvoice: Boolean = true,
    val canIssueReceipt: Boolean = true,
    val canRefund: Boolean = false,
    val canVoid: Boolean = false,
    val canCorrectAmount: Boolean = false,
    val canChangePaymentMethod: Boolean = false,
    val canViewBroadHistory: Boolean = false,
    val canAccessCustomerDirectory: Boolean = false,
    val canManageProducts: Boolean = false,
    val canViewReports: Boolean = false
)

data class CreateCashierParams(
    val businessId: String,
    val branchId: String,
    val adminUserId: String,
    val displayName: String,
    val username: String,
    val phone: String? = null,
    val pin: String,
    val preferredLanguage: String = "en",
    val permissions: CashierPermissionsConfig = CashierPermissionsConfig()
)

class CashierManagementRepository @Inject constructor(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val cloudSource: EmployeeDirectoryCloudSource
) {
    constructor(
        database: DmrDatabase,
        commandExecutor: CommandExecutor,
        deviceIdentityProvider: DeviceIdentityProvider
    ) : this(
        database,
        commandExecutor,
        deviceIdentityProvider,
        object : EmployeeDirectoryCloudSource {
            override suspend fun listCashiers(): List<CloudCashierDirectoryEntry> = emptyList()
            override suspend fun updateCashierPermissions(membershipId: String, permissions: Map<String, Boolean>) {
                error("Cloud employee directory is unavailable")
            }
            override suspend fun removeCashier(membershipId: String) {
                error("Cloud employee directory is unavailable")
            }
        }
    )
    private val userDao = database.userDao()

    private val cloudCashiers = MutableStateFlow<List<CloudCashierDirectoryEntry>>(emptyList())

    data class DirectoryEntry(
        val id: String,
        val displayName: String,
        val username: String,
        val languageCode: String,
        val isActive: Boolean,
        val localAccount: UserAccountEntity?,
        val membershipId: String? = null,
        val cloudPermissions: CashierPermissionsConfig? = null,
        val canonicalPhone: String? = null
    )

    fun observeCashiers(businessId: String): Flow<List<DirectoryEntry>> =
        combine(userDao.observeCashiers(businessId), cloudCashiers) { local, cloud ->
            val localById = local.associateBy { it.id }
            val cloudRows = cloud.filterNot { it.isRemoved }.map { remote ->
                val localAccount = localById[remote.userId]
                DirectoryEntry(
                    id = remote.userId,
                    displayName = localAccount?.displayName ?: remote.displayName,
                    username = localAccount?.username ?: remote.canonicalPhone,
                    languageCode = localAccount?.languageCode ?: "en",
                    isActive = remote.isActive,
                    localAccount = localAccount,
                    membershipId = remote.membershipId,
                    cloudPermissions = remote.permissions.toCashierPermissionsConfig(),
                    canonicalPhone = remote.canonicalPhone
                )
            }
            val cloudIds = cloud.asSequence().map { it.userId }.toSet()
            (cloudRows + local.filterNot { it.id in cloudIds }.map {
                DirectoryEntry(it.id, it.displayName, it.username, it.languageCode, it.isActive, it)
            }).sortedBy { it.displayName.lowercase() }
        }

    suspend fun refreshCloudDirectory(): Result<Unit> = runCatching {
        cloudCashiers.value = cloudSource.listCashiers().distinctBy { it.membershipId }
    }

    suspend fun updateCloudCashierPermissions(
        membershipId: String,
        permissions: CashierPermissionsConfig
    ) {
        cloudSource.updateCashierPermissions(membershipId, permissions.toRemotePermissionMap())
        refreshCloudDirectory().getOrThrow()
    }

    suspend fun removeCloudCashier(membershipId: String) {
        cloudSource.removeCashier(membershipId)
        refreshCloudDirectory().getOrThrow()
    }

    suspend fun createCashier(
        params: CreateCashierParams,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<UserId> {

        val existingOperation = database.operationDao().getOperationById(operationId.value)
        val fingerprintSchemaVersion = existingOperation?.commandSchemaVersion
            ?.takeIf { it in 1..CreateCashierPayloadCodec.CURRENT_SCHEMA_VERSION }
            ?: CreateCashierPayloadCodec.CURRENT_SCHEMA_VERSION
        val requestedFingerprint = CreateCashierPayloadCodec.fingerprint(params, fingerprintSchemaVersion)

        // Same Operation ID must always mean the same requested action.
        if (
            existingOperation != null &&
            existingOperation.requestFingerprint != requestedFingerprint
        ) {
            return CommandResult.Conflict(
                "Conflict: Cashier Operation ID '${operationId.value}' was already used with different cashier details."
            )
        }

        // Domain-level Admin authorization checks.
        val actor = userDao.getUserById(params.adminUserId)
            ?: return CommandResult.PermissionDenied(
                "Actor user '${params.adminUserId}' not found"
            )

        if (!actor.isActive) {
            return CommandResult.PermissionDenied(
                "Actor user '${actor.username}' is inactive"
            )
        }

        if (actor.businessId != params.businessId) {
            return CommandResult.PermissionDenied(
                "Actor user '${actor.username}' belongs to another business"
            )
        }

        val actorMembership = userDao.getMembership(actor.id, params.branchId)
        val isAdmin =
            actor.role == UserRole.ADMIN ||
                actorMembership?.role == UserRole.ADMIN

        if (!isAdmin) {
            return CommandResult.PermissionDenied(
                "Actor user '${actor.username}' is not an authorized Admin for this branch"
            )
        }

        val branch = database.businessBranchDao().getBranchById(params.branchId)

        if (branch == null || branch.businessId != params.businessId) {
            return CommandResult.PermissionDenied(
                "Target branch '${params.branchId}' is invalid for business '${params.businessId}'"
            )
        }

        require(PinHasher.isValidPin(params.pin)) {
            "Cashier PIN must be exactly 6 numeric digits"
        }

        val payload = if (existingOperation != null) {
            val savedPayload = try {
                CreateCashierPayloadCodec.decode(existingOperation.intentPayload)
            } catch (error: Exception) {
                return CommandResult.Error(error)
            }

            if (
                savedPayload.operationId != operationId.value ||
                savedPayload.businessId != existingOperation.businessId ||
                savedPayload.branchId != existingOperation.branchId ||
                savedPayload.adminUserId != existingOperation.actorUserId ||
                savedPayload.cashierUserId != existingOperation.subjectId ||
                existingOperation.operationType != OperationType.CREATE_CASHIER ||
                existingOperation.subjectType != SubjectType.USER
            ) {
                return CommandResult.Conflict(
                    "Conflict: Saved Cashier recovery payload does not match its original Operation."
                )
            }

            if (
                !PinHasher.verifyPin(
                    params.pin,
                    savedPayload.credentialSalt,
                    savedPayload.credentialHash
                )
            ) {
                return CommandResult.Conflict(
                    "Conflict: Cashier PIN does not match the original request for this Operation ID."
                )
            }

            savedPayload
        } else {
            val cashierId = UserId.generate()
            val membershipId = IdGenerator.newId()
            val now = System.currentTimeMillis()

            val salt = PinHasher.generateSalt()
            val hash = PinHasher.hashPin(params.pin, salt)
            val canonicalPhone = params.phone?.let {
                PhoneNumberNormalizer.normalize(it, branch.countryCode)
            }

            CreateCashierRecoveryPayload(
                operationId = operationId.value,
                businessId = params.businessId,
                branchId = params.branchId,
                adminUserId = params.adminUserId,
                cashierUserId = cashierId.value,
                membershipId = membershipId,
                occurredAt = now,
                deviceId = deviceIdentityProvider.deviceId,

                displayName = params.displayName,
                username = params.username,
                canonicalPhone = canonicalPhone,
                credentialHash = hash,
                credentialSalt = salt,
                preferredLanguage = params.preferredLanguage,
                permissions = params.permissions
            )
        }

        val cashierId = UserId(payload.cashierUserId)
        val membershipId = payload.membershipId
        val now = payload.occurredAt

        val command = object : OperationCommand {
            override val operationId = operationId
            override val businessId = payload.businessId
            override val branchId = payload.branchId
            override val operationType = OperationType.CREATE_CASHIER
            override val subjectType = SubjectType.USER
            override val subjectId = payload.cashierUserId
            override val transactionId: String? = null
            override val actorUserId = payload.adminUserId
            override val deviceId = payload.deviceId
            override val occurredAt = payload.occurredAt
            override val commandSchemaVersion = fingerprintSchemaVersion
            override val requestFingerprint = requestedFingerprint
            override val intentPayload =
                CreateCashierPayloadCodec.encode(payload, fingerprintSchemaVersion)
        }

        return commandExecutor.execute(
            command = command,
            replayResolver = { op ->
                val cashierRef = op.resultReferenceId ?: op.subjectId

                val existingCashier = userDao.getUserById(cashierRef)
                    ?: throw IllegalStateException(
                        "Replay failed: Cashier '$cashierRef' not found"
                    )

                val replayBranchId = op.branchId
                    ?: throw IllegalStateException(
                        "Replay failed: Cashier operation has no branch ID"
                    )

                val existingMembership = userDao.getMembership(
                    existingCashier.id,
                    replayBranchId
                ) ?: throw IllegalStateException(
                    "Replay failed: Cashier '${existingCashier.id}' has no membership for branch '$replayBranchId'"
                )

                if (
                    existingCashier.businessId != op.businessId ||
                    existingCashier.role != UserRole.CASHIER ||
                    existingMembership.userId != existingCashier.id ||
                    existingMembership.role != UserRole.CASHIER
                ) {
                    throw IllegalStateException(
                        "Replay failed: Cashier identity does not match the original operation"
                    )
                }

                UserId(existingCashier.id)
            }
        ) {
            val cashier = UserAccountEntity(
                id = cashierId.value,
                identityId = cashierId.value,
                businessId = payload.businessId,
                displayName = payload.displayName,
                username = payload.username,
                role = UserRole.CASHIER,
                authType = AuthType.PIN,
                credentialHash = payload.credentialHash,
                credentialSalt = payload.credentialSalt,
                languageCode = payload.preferredLanguage,
                isActive = true,
                mustChangePin = true,
                createdAt = now,
                updatedAt = now
            )
            userDao.insertHumanIdentity(
                com.dailymoneyregister.core.database.entity.HumanIdentityEntity(
                    id = cashierId.value,
                    canonicalPhone = payload.canonicalPhone,
                    displayName = payload.displayName,
                    createdAt = now,
                    updatedAt = now
                )
            )
            userDao.insertUser(cashier)

            val permissions = payload.permissions

            val membership = UserBranchMembershipEntity(
                id = membershipId,
                userId = cashierId.value,
                branchId = payload.branchId,
                role = UserRole.CASHIER,
                canCreateSale = permissions.canCreateSale,
                canAcceptCash = permissions.canAcceptCash,
                canAcceptOnline = permissions.canAcceptOnline,
                canCreateCreditSale = permissions.canCreateCreditSale,
                canUseCoupon = permissions.canUseCoupon,
                canIssueInvoice = permissions.canIssueInvoice,
                canIssueReceipt = permissions.canIssueReceipt,
                canRefund = permissions.canRefund,
                canVoid = permissions.canVoid,
                canCorrectAmount = permissions.canCorrectAmount,
                canChangePaymentMethod = permissions.canChangePaymentMethod,
                canViewBroadHistory = permissions.canViewBroadHistory,
                canAccessCustomerDirectory = permissions.canAccessCustomerDirectory,
                canManageProducts = permissions.canManageProducts,
                canViewReports = permissions.canViewReports,
                createdAt = now
            )
            userDao.insertMembership(membership)

            Pair(cashierId, Pair("USER", cashierId.value))
        }
    }
}




private fun Map<String, Boolean>.toCashierPermissionsConfig() = CashierPermissionsConfig(
    canCreateSale = this["CREATE_SALE"] ?: true,
    canAcceptCash = this["ACCEPT_CASH"] ?: true,
    canAcceptOnline = this["ACCEPT_ONLINE"] ?: true,
    canCreateCreditSale = this["CREATE_CREDIT_SALE"] ?: false,
    canUseCoupon = this["USE_COUPON"] ?: false,
    canIssueInvoice = this["ISSUE_INVOICE"] ?: true,
    canIssueReceipt = this["ISSUE_RECEIPT"] ?: true,
    canRefund = this["REFUND"] ?: false,
    canVoid = this["VOID"] ?: false,
    canCorrectAmount = this["CORRECT_AMOUNT"] ?: false,
    canChangePaymentMethod = this["CHANGE_PAYMENT_METHOD"] ?: false,
    canViewBroadHistory = this["VIEW_BROAD_HISTORY"] ?: false,
    canAccessCustomerDirectory = this["CUSTOMER_DIRECTORY_ACCESS"] ?: false,
    canManageProducts = this["MANAGE_PRODUCTS"] ?: false,
    canViewReports = this["VIEW_REPORTS"] ?: false
)

private fun CashierPermissionsConfig.toRemotePermissionMap(): Map<String, Boolean> = mapOf(
    "CREATE_SALE" to canCreateSale,
    "ACCEPT_CASH" to canAcceptCash,
    "ACCEPT_ONLINE" to canAcceptOnline,
    "CREATE_CREDIT_SALE" to canCreateCreditSale,
    "USE_COUPON" to canUseCoupon,
    "ISSUE_INVOICE" to canIssueInvoice,
    "ISSUE_RECEIPT" to canIssueReceipt,
    "REFUND" to canRefund,
    "VOID" to canVoid,
    "CORRECT_AMOUNT" to canCorrectAmount,
    "CHANGE_PAYMENT_METHOD" to canChangePaymentMethod,
    "VIEW_BROAD_HISTORY" to canViewBroadHistory,
    "CUSTOMER_DIRECTORY_ACCESS" to canAccessCustomerDirectory,
    "MANAGE_PRODUCTS" to canManageProducts,
    "VIEW_REPORTS" to canViewReports
)
