package com.dailymoneyregister.feature.admin.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.i18n.LanguageRegistry
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.UserId
import com.dailymoneyregister.core.security.PinHasher
import javax.inject.Inject

/** Who is acting (admin) and which cashier is being changed. */
data class StaffTarget(
    val businessId: String,
    val branchId: String,
    val adminUserId: String,
    val cashierUserId: String
)

data class UpdateCashierParams(
    val target: StaffTarget,
    val displayName: String,
    val phone: String,
    val preferredLanguage: String
)

/** Current values used to pre-fill the edit dialogs. */
data class CashierEditSnapshot(
    val userId: String,
    val displayName: String,
    val phoneInput: String,
    val languageCode: String,
    val isActive: Boolean,
    val permissions: CashierPermissionsConfig
)

/**
 * Staff lifecycle for existing cashiers:
 *  1. edit name / mobile / language
 *  2. edit permissions
 *  3. disable
 *  4. re-enable
 *  5. admin resets a forgotten PIN
 *
 * Every change is one durable Operation (type, actor, device, time, intent payload)
 * executed through CommandExecutor, so each change is idempotent and leaves an
 * Operation row behind. No schema change is needed.
 */
class CashierLifecycleRepository @Inject constructor(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val deviceIdentityProvider: DeviceIdentityProvider
) {
    private val userDao = database.userDao()

    private class Rejection(val result: CommandResult<UserId>) : Exception()

    private fun reject(result: CommandResult<UserId>): Nothing = throw Rejection(result)

    private class Resolved(
        val branch: BranchEntity,
        val cashier: UserAccountEntity,
        val membership: UserBranchMembershipEntity
    )

    // ------------------------------------------------------------------
    // Read helper for dialogs
    // ------------------------------------------------------------------

    suspend fun getEditSnapshot(
        businessId: String,
        branchId: String,
        cashierUserId: String
    ): CashierEditSnapshot? {
        val user = userDao.getUserByIdInBusiness(cashierUserId, businessId) ?: return null
        if (user.role != UserRole.CASHIER) return null
        val membership = userDao.getMembership(user.id, branchId) ?: return null
        val identity = userDao.getHumanIdentityById(user.identityId ?: user.id)

        // Pre-fill with the number as the admin originally typed it. Cashiers created
        // without a phone start with an empty field.
        val phoneInput = if (identity?.canonicalPhone != null) user.username else ""

        return CashierEditSnapshot(
            userId = user.id,
            displayName = user.displayName,
            phoneInput = phoneInput,
            languageCode = user.languageCode,
            isActive = user.isActive,
            permissions = membership.toConfig()
        )
    }

    // ------------------------------------------------------------------
    // 1. Edit name / mobile number / language
    // ------------------------------------------------------------------

    suspend fun updateCashier(
        params: UpdateCashierParams,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<UserId> {
        val name = params.displayName.trim()
        val phoneInput = params.phone.trim()
        val language = params.preferredLanguage

        return executeStaffCommand(
            type = OperationType.UPDATE_CASHIER,
            ref = params.target,
            operationId = operationId,
            fingerprint = fingerprint(
                OperationType.UPDATE_CASHIER, params.target, name, phoneInput, language
            ),
            prepare = { r ->
                if (name.isBlank()) {
                    reject(CommandResult.Error(IllegalArgumentException("Cashier name is required")))
                }
                if (phoneInput.isBlank()) {
                    reject(CommandResult.Error(IllegalArgumentException("Mobile number is required")))
                }
                if (LanguageRegistry.allLanguages.none { it.code == language }) {
                    reject(CommandResult.Error(IllegalArgumentException("Unsupported language")))
                }

                val canonical = runCatching {
                    PhoneNumberNormalizer.normalize(phoneInput, r.branch.countryCode)
                }.getOrNull() ?: reject(
                    CommandResult.Error(IllegalArgumentException("Enter a valid mobile number"))
                )

                val identity = userDao.getHumanIdentityById(r.cashier.identityId ?: r.cashier.id)
                    ?: reject(
                        CommandResult.Error(IllegalStateException("Cashier identity record is missing"))
                    )

                if (
                    identity.canonicalPhone == canonical &&
                    r.cashier.displayName == name &&
                    r.cashier.languageCode == language &&
                    r.cashier.username == phoneInput
                ) {
                    reject(CommandResult.Conflict("No changes to save"))
                }

                val phoneOwner = userDao.getHumanIdentityByPhone(canonical)
                if (phoneOwner != null && phoneOwner.id != identity.id) {
                    reject(CommandResult.Conflict("This mobile number already belongs to another account"))
                }

                val loginOwner = userDao.getUserByUsername(r.cashier.businessId, phoneInput)
                if (loginOwner != null && loginOwner.id != r.cashier.id) {
                    reject(CommandResult.Conflict("This login name is already used by another account"))
                }

                basePayload(OperationType.UPDATE_CASHIER, params.target, operationId).copy(
                    displayName = name,
                    username = phoneInput,
                    canonicalPhone = canonical,
                    preferredLanguage = language
                )
            },
            mutate = { p, cashier, _ ->
                val identity = userDao.getHumanIdentityById(cashier.identityId ?: cashier.id)
                    ?: throw IllegalStateException("Cashier identity record is missing")

                val phoneOwner = p.canonicalPhone?.let { userDao.getHumanIdentityByPhone(it) }
                check(phoneOwner == null || phoneOwner.id == identity.id) {
                    "Mobile number is no longer available"
                }

                val newName = requireNotNull(p.displayName)

                userDao.updateHumanIdentity(
                    identity.copy(
                        canonicalPhone = p.canonicalPhone,
                        displayName = newName,
                        updatedAt = p.occurredAt
                    )
                )
                userDao.updateUser(
                    cashier.copy(
                        displayName = newName,
                        username = requireNotNull(p.username),
                        languageCode = requireNotNull(p.preferredLanguage),
                        updatedAt = p.occurredAt
                    )
                )
            }
        )
    }

    // ------------------------------------------------------------------
    // 2. Edit permissions
    // ------------------------------------------------------------------

    suspend fun updateCashierPermissions(
        target: StaffTarget,
        permissions: CashierPermissionsConfig,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<UserId> {
        return executeStaffCommand(
            type = OperationType.UPDATE_CASHIER_PERMISSIONS,
            ref = target,
            operationId = operationId,
            fingerprint = fingerprint(
                OperationType.UPDATE_CASHIER_PERMISSIONS,
                target,
                *permissions.asStrings()
            ),
            prepare = { r ->
                if (r.membership.toConfig() == permissions) {
                    reject(CommandResult.Conflict("No permission changes to save"))
                }
                basePayload(OperationType.UPDATE_CASHIER_PERMISSIONS, target, operationId)
                    .copy(permissions = permissions)
            },
            mutate = { p, _, membership ->
                val perms = requireNotNull(p.permissions)
                userDao.updateMembership(
                    membership.copy(
                        canCreateSale = perms.canCreateSale,
                        canAcceptCash = perms.canAcceptCash,
                        canAcceptOnline = perms.canAcceptOnline,
                        canCreateCreditSale = perms.canCreateCreditSale,
                        canUseCoupon = perms.canUseCoupon,
                        canIssueInvoice = perms.canIssueInvoice,
                        canIssueReceipt = perms.canIssueReceipt,
                        canRefund = perms.canRefund,
                        canVoid = perms.canVoid,
                        canCorrectAmount = perms.canCorrectAmount,
                        canChangePaymentMethod = perms.canChangePaymentMethod,
                        canViewBroadHistory = perms.canViewBroadHistory,
                        canManageProducts = perms.canManageProducts,
                        canViewReports = perms.canViewReports
                    )
                )
            }
        )
    }

    // ------------------------------------------------------------------
    // 3. Disable   /   4. Re-enable
    // ------------------------------------------------------------------

    suspend fun disableCashier(
        target: StaffTarget,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<UserId> = setActive(
        type = OperationType.DISABLE_CASHIER,
        target = target,
        operationId = operationId,
        activate = false
    )

    suspend fun enableCashier(
        target: StaffTarget,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<UserId> = setActive(
        type = OperationType.ENABLE_CASHIER,
        target = target,
        operationId = operationId,
        activate = true
    )

    private suspend fun setActive(
        type: OperationType,
        target: StaffTarget,
        operationId: OperationId,
        activate: Boolean
    ): CommandResult<UserId> {
        return executeStaffCommand(
            type = type,
            ref = target,
            operationId = operationId,
            fingerprint = fingerprint(type, target),
            prepare = { r ->
                if (r.cashier.isActive == activate) {
                    reject(
                        CommandResult.Conflict(
                            if (activate) "Cashier is already enabled" else "Cashier is already disabled"
                        )
                    )
                }

                /*
                 * A cashier must finish/close an authoritative OPEN shift
                 * before the account can be disabled.
                 *
                 * This check is intentionally part of the domain repository,
                 * not just the Compose UI.
                 */
                if (
                    !activate &&
                    hasOpenCashShift(
                        branchId = target.branchId,
                        cashierUserId = target.cashierUserId
                    )
                ) {
                    reject(
                        CommandResult.Conflict(
                            "Close this cashier's active shift before disabling the cashier"
                        )
                    )
                }

                basePayload(type, target, operationId)
            },
            mutate = { p, cashier, _ ->

                /*
                 * Re-check inside CommandExecutor's transaction.
                 *
                 * The prepare check produces a clean Conflict before durable
                 * admission. This second check prevents a concurrent shift
                 * opening between preparation and mutation.
                 */
                check(
                    activate ||
                        !hasOpenCashShift(
                            branchId = target.branchId,
                            cashierUserId = cashier.id
                        )
                ) {
                    "Cashier has an active shift and cannot be disabled"
                }

                userDao.updateUser(
                    cashier.copy(
                        isActive = activate,
                        updatedAt = p.occurredAt
                    )
                )
            }
        )
    }

    // ------------------------------------------------------------------
    // 5. Admin resets a forgotten PIN
    // ------------------------------------------------------------------

    suspend fun resetCashierPin(
        target: StaffTarget,
        newPin: String,
        operationId: OperationId = OperationId.generate()
    ): CommandResult<UserId> {
        return executeStaffCommand(
            type = OperationType.ADMIN_RESET_PIN,
            ref = target,
            operationId = operationId,
            // The PIN is deliberately not part of the fingerprint. On replay it is
            // verified against the saved salted hash instead (same as createCashier).
            fingerprint = fingerprint(OperationType.ADMIN_RESET_PIN, target),
            prepare = { r ->
                if (!PinHasher.isValidPin(newPin)) {
                    reject(
                        CommandResult.Error(
                            IllegalArgumentException("Cashier PIN must be exactly 6 numeric digits")
                        )
                    )
                }
                if (PinHasher.verifyPin(newPin, r.cashier.credentialSalt ?: "", r.cashier.credentialHash)) {
                    reject(CommandResult.Conflict("The new PIN must be different from the current PIN"))
                }

                val salt = PinHasher.generateSalt()
                val hash = PinHasher.hashPin(newPin, salt)

                basePayload(OperationType.ADMIN_RESET_PIN, target, operationId).copy(
                    credentialHash = hash,
                    credentialSalt = salt
                )
            },
            verifyReplay = { saved ->
                PinHasher.verifyPin(
                    newPin,
                    saved.credentialSalt.orEmpty(),
                    saved.credentialHash.orEmpty()
                )
            },
            mutate = { p, cashier, _ ->
                userDao.updateUser(
                    cashier.copy(
                        credentialHash = requireNotNull(p.credentialHash),
                        credentialSalt = requireNotNull(p.credentialSalt),
                        mustChangePin = true,
                        updatedAt = p.occurredAt
                    )
                )
            }
        )
    }

    // ------------------------------------------------------------------
    // Shared engine
    // ------------------------------------------------------------------

    private suspend fun executeStaffCommand(
        type: OperationType,
        ref: StaffTarget,
        operationId: OperationId,
        fingerprint: String,
        prepare: suspend (Resolved) -> StaffLifecyclePayload,
        verifyReplay: (StaffLifecyclePayload) -> Boolean = { true },
        mutate: suspend (StaffLifecyclePayload, UserAccountEntity, UserBranchMembershipEntity) -> Unit
    ): CommandResult<UserId> {

        val existing = database.operationDao().getOperationById(operationId.value)

        // The same Operation ID must always mean the same requested action.
        if (existing != null && existing.requestFingerprint != fingerprint) {
            return CommandResult.Conflict(
                "Conflict: Operation ID '${operationId.value}' was already used with a different staff request."
            )
        }

        val payload: StaffLifecyclePayload = try {
            val resolved = resolve(ref)

            if (existing != null) {
                if (
                    existing.operationType != type ||
                    existing.subjectType != SubjectType.USER ||
                    existing.subjectId != ref.cashierUserId
                ) {
                    reject(
                        CommandResult.Conflict(
                            "Conflict: Operation ID '${operationId.value}' belongs to a different staff action."
                        )
                    )
                }

                val saved = try {
                    StaffLifecyclePayloadCodec.decode(existing.intentPayload)
                } catch (error: Exception) {
                    reject(CommandResult.Error(error))
                }

                if (
                    saved.kind != type.name ||
                    saved.operationId != operationId.value ||
                    saved.businessId != existing.businessId ||
                    saved.branchId != existing.branchId ||
                    saved.adminUserId != existing.actorUserId ||
                    saved.cashierUserId != existing.subjectId
                ) {
                    reject(
                        CommandResult.Conflict(
                            "Conflict: Saved staff recovery payload does not match its original Operation."
                        )
                    )
                }

                if (!verifyReplay(saved)) {
                    reject(
                        CommandResult.Conflict(
                            "Conflict: Request does not match the original request for this Operation ID."
                        )
                    )
                }

                saved
            } else {
                prepare(resolved)
            }
        } catch (rejection: Rejection) {
            return rejection.result
        }

        val commandOperationId = operationId
        val commandFingerprint = fingerprint
        val commandType = type

        val command = object : OperationCommand {
            override val operationId = commandOperationId
            override val businessId = payload.businessId
            override val branchId = payload.branchId
            override val operationType = commandType
            override val subjectType = SubjectType.USER
            override val subjectId = payload.cashierUserId
            override val transactionId: String? = null
            override val actorUserId = payload.adminUserId
            override val deviceId = payload.deviceId
            override val occurredAt = payload.occurredAt
            override val commandSchemaVersion = StaffLifecyclePayloadCodec.CURRENT_SCHEMA_VERSION
            override val requestFingerprint = commandFingerprint
            override val intentPayload = StaffLifecyclePayloadCodec.encode(payload)
        }

        return commandExecutor.execute(
            command = command,
            replayResolver = { op ->
                val cashierRef = op.resultReferenceId ?: op.subjectId
                val cashier = userDao.getUserByIdInBusiness(cashierRef, op.businessId)
                    ?: throw IllegalStateException("Replay failed: Cashier '$cashierRef' not found")
                if (cashier.role != UserRole.CASHIER) {
                    throw IllegalStateException("Replay failed: user '$cashierRef' is not a cashier")
                }
                UserId(cashier.id)
            }
        ) {
            // Re-read inside the transaction so the change is applied to current state.
            val cashier = userDao.getUserByIdInBusiness(payload.cashierUserId, payload.businessId)
                ?: throw IllegalStateException("Cashier '${payload.cashierUserId}' not found")
            val membership = userDao.getMembership(cashier.id, payload.branchId)
                ?: throw IllegalStateException("Cashier '${cashier.id}' has no membership for this branch")

            check(cashier.role == UserRole.CASHIER && membership.role == UserRole.CASHIER) {
                "Target user is not a cashier"
            }

            mutate(payload, cashier, membership)

            Pair(UserId(cashier.id), Pair("USER", cashier.id))
        }
    }

    /** Admin + target validation, mirroring createCashier but stricter on the actor role. */
    private suspend fun resolve(ref: StaffTarget): Resolved {
        val actor = userDao.getUserById(ref.adminUserId)
            ?: reject(CommandResult.PermissionDenied("Actor user '${ref.adminUserId}' not found"))

        if (!actor.isActive) {
            reject(CommandResult.PermissionDenied("Actor user '${actor.username}' is inactive"))
        }
        if (actor.businessId != ref.businessId) {
            reject(CommandResult.PermissionDenied("Actor user '${actor.username}' belongs to another business"))
        }
        if (actor.role != UserRole.ADMIN) {
            reject(CommandResult.PermissionDenied("Actor user '${actor.username}' is not an Admin"))
        }

        val branch = database.businessBranchDao().getBranchById(ref.branchId)
        if (branch == null || branch.businessId != ref.businessId) {
            reject(
                CommandResult.PermissionDenied(
                    "Target branch '${ref.branchId}' is invalid for business '${ref.businessId}'"
                )
            )
        }

        val cashier = userDao.getUserByIdInBusiness(ref.cashierUserId, ref.businessId)
            ?: reject(CommandResult.PermissionDenied("Cashier not found in this business"))

        if (cashier.role != UserRole.CASHIER || cashier.authType != AuthType.PIN) {
            reject(CommandResult.PermissionDenied("Target user is not a PIN cashier"))
        }

        val membership = userDao.getMembership(cashier.id, ref.branchId)
        if (membership == null || membership.role != UserRole.CASHIER) {
            reject(CommandResult.PermissionDenied("Cashier has no membership in this branch"))
        }

        return Resolved(branch, cashier, membership)
    }

    /**
     * Read-only authoritative shift-state check.
     *
     * Uses the same persisted shift/event evidence as Register:
     * OPENED present + CLOSED absent = OPEN.
     */
    private suspend fun hasOpenCashShift(
        branchId: String,
        cashierUserId: String
    ): Boolean {

        val latest =
            database.cashShiftDao()
                .getLatestShiftForCashier(
                    branchId = branchId,
                    userId = cashierUserId
                )
                ?: return false

        val events =
            database.cashShiftDao()
                .getEventsForShift(
                    latest.id
                )

        return events.any {
            it.type == CashShiftEventType.OPENED
        } &&
            events.none {
                it.type == CashShiftEventType.CLOSED
            }
    }

    private fun basePayload(
        type: OperationType,
        ref: StaffTarget,
        operationId: OperationId
    ): StaffLifecyclePayload = StaffLifecyclePayload(
        kind = type.name,
        operationId = operationId.value,
        businessId = ref.businessId,
        branchId = ref.branchId,
        adminUserId = ref.adminUserId,
        cashierUserId = ref.cashierUserId,
        occurredAt = System.currentTimeMillis(),
        deviceId = deviceIdentityProvider.deviceId
    )

    private fun fingerprint(
        type: OperationType,
        ref: StaffTarget,
        vararg extra: String
    ): String {
        val values = listOf(
            StaffLifecyclePayloadCodec.CURRENT_SCHEMA_VERSION.toString(),
            type.name,
            ref.businessId,
            ref.branchId,
            ref.adminUserId,
            ref.cashierUserId
        ) + extra.toList()

        val canonical = values.joinToString("|") { "${it.length}:$it" }
        return CommandFingerprint.sha256(canonical)
    }

    private fun CashierPermissionsConfig.asStrings(): Array<String> = arrayOf(
        canCreateSale.toString(),
        canAcceptCash.toString(),
        canAcceptOnline.toString(),
        canCreateCreditSale.toString(),
        canUseCoupon.toString(),
        canIssueInvoice.toString(),
        canIssueReceipt.toString(),
        canRefund.toString(),
        canVoid.toString(),
        canCorrectAmount.toString(),
        canChangePaymentMethod.toString(),
        canViewBroadHistory.toString(),
        canManageProducts.toString(),
        canViewReports.toString()
    )

    private fun UserBranchMembershipEntity.toConfig() = CashierPermissionsConfig(
        canCreateSale = canCreateSale,
        canAcceptCash = canAcceptCash,
        canAcceptOnline = canAcceptOnline,
        canCreateCreditSale = canCreateCreditSale,
        canUseCoupon = canUseCoupon,
        canIssueInvoice = canIssueInvoice,
        canIssueReceipt = canIssueReceipt,
        canRefund = canRefund,
        canVoid = canVoid,
        canCorrectAmount = canCorrectAmount,
        canChangePaymentMethod = canChangePaymentMethod,
        canViewBroadHistory = canViewBroadHistory,
        canManageProducts = canManageProducts,
        canViewReports = canViewReports
    )
}