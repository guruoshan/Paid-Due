package com.dailymoneyregister.core.cloud

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.HumanIdentityEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PasswordHasher
import com.dailymoneyregister.core.security.PinHasher
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class RemoteStaffRestorationRpcRow(
    @SerialName("staff_identity_id") val staffIdentityId: String? = null,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("local_user_id") val localUserId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("business_name") val businessName: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("branch_name") val branchName: String,
    @SerialName("branch_code") val branchCode: String = "MAIN",
    @SerialName("country_code") val countryCode: String = "OM",
    @SerialName("currency_code") val currencyCode: String = "OMR",
    @SerialName("currency_decimal_places") val currencyDecimalPlaces: Int = 3,
    @SerialName("currency_symbol") val currencySymbol: String = "OMR",
    @SerialName("timezone_id") val timezoneId: String = "Asia/Muscat",
    @SerialName("tax_rounding_mode") val taxRoundingMode: String = "HALF_UP",
    @SerialName("default_tax_rate_bps") val defaultTaxRateBps: Int = 500,
    @SerialName("branch_address") val branchAddress: String? = null,
    @SerialName("branch_phone") val branchPhone: String? = null,
    @SerialName("tax_registration_number") val taxRegistrationNumber: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("canonical_phone") val canonicalPhone: String? = null,
    @SerialName("credential_hash") val credentialHash: String? = null,
    @SerialName("credential_salt") val credentialSalt: String? = null,
    val permissions: Map<String, Boolean>? = null
)

sealed interface RemoteBusinessRestorationResult {
    data class Success(
        val businessId: String,
        val branchId: String,
        val userId: String
    ) : RemoteBusinessRestorationResult

    data class Failed(val message: String) : RemoteBusinessRestorationResult
}

interface BusinessRestorer {
    suspend fun restoreCashierIfEmpty(
        usernameOrPhone: String,
        pin: String,
        countryCode: String = "OM"
    ): RemoteBusinessRestorationResult

    suspend fun restoreAdminIfEmpty(
        usernameOrEmail: String,
        passwordPlain: String
    ): RemoteBusinessRestorationResult

    /** Fresh online check; false means the cached session must not be restored. */
    suspend fun validateAdminSession(businessId: String, branchId: String, userId: String): Boolean = false
    suspend fun validateCashierSession(membershipId: String): Boolean = false
}

@Singleton
class RemoteBusinessRestorer @Inject constructor(
    private val database: DmrDatabase,
    private val supabase: SupabaseClient,
    private val remoteAdminAuthRepository: RemoteAdminAuthRepository
) : BusinessRestorer {
    private val businessBranchDao = database.businessBranchDao()
    private val userDao = database.userDao()

    override suspend fun restoreCashierIfEmpty(
        usernameOrPhone: String,
        pin: String,
        countryCode: String
    ): RemoteBusinessRestorationResult {
        val existingBusiness = businessBranchDao.getFirstBusiness()

        // Existing business is NOT evidence that this cashier is restored.
        // Remote authority must match the existing tenant; never mix tenants.

        val phone = runCatching {
            PhoneNumberNormalizer.normalize(usernameOrPhone, countryCode)
        }.getOrNull() ?: return RemoteBusinessRestorationResult.Failed(
            "Enter a valid international mobile number"
        )

        return try {
            val result = com.dailymoneyregister.core.cloud.sync.CashierCloudSession
                .authenticateForRestoration(phone, pin)
                ?: return RemoteBusinessRestorationResult.Failed(
                    "Cloud cashier authentication failed [" + com.dailymoneyregister.core.cloud.sync.CashierCloudSession.lastAuthStage + "]."
                )

            val permissionsJson = result.getJSONObject("permissions")
            val permissionNames = listOf(
                "CREATE_SALE", "ACCEPT_CASH", "ACCEPT_ONLINE", "CREATE_CREDIT_SALE",
                "USE_COUPON", "ISSUE_INVOICE", "ISSUE_RECEIPT", "REFUND", "VOID",
                "CORRECT_AMOUNT", "CHANGE_PAYMENT_METHOD", "VIEW_BROAD_HISTORY",
                "MANAGE_PRODUCTS", "VIEW_REPORTS"
            )
            val permissions = permissionNames.associateWith { key ->
                require(permissionsJson.has(key) && !permissionsJson.isNull(key)) {
                    "Missing cloud cashier permission"
                }
                permissionsJson.getBoolean(key)
            }
            require(result.getString("canonical_phone") == phone) {
                "Cashier identity mismatch"
            }
            val membershipId = result.getString("membership_id")
            require(
                com.dailymoneyregister.core.cloud.sync.CashierCloudSession
                    .tokenFor(membershipId) != null
            ) { "Cashier cloud session is not valid" }

            // BOARDLOCK_CASHIER_TENANT_GUARD
            val remoteBusinessId = result.getString("business_id")
            val remoteBranchId = result.getString("branch_id")
            val remoteUserId = result.getString("local_user_id")
            require(remoteBusinessId.isNotBlank() && remoteBranchId.isNotBlank() && remoteUserId.isNotBlank()) {
                "Incomplete cashier authority"
            }
            if (existingBusiness != null) {
                require(existingBusiness.id == remoteBusinessId) { "Cashier business conflict" }
                val localBranch = businessBranchDao.getFirstBranch(existingBusiness.id)
                require(localBranch?.id == remoteBranchId) { "Cashier branch conflict" }
            }
            val existingPhoneOwner = userDao.getHumanIdentityByPhone(phone)
            require(existingPhoneOwner == null || existingPhoneOwner.id == remoteUserId) {
                "Phone already belongs to another local identity"
            }
            val existingUser = userDao.getUserById(remoteUserId)
            if (existingUser != null) {
                // Online login path: successful server PIN authentication is
                // mandatory; local IDs must match the server-authorized tenant.
                // Never replace existing local credentials or financial data.
                require(existingUser.businessId == remoteBusinessId &&
                    existingUser.role == UserRole.CASHIER &&
                    existingUser.authType == AuthType.PIN &&
                    existingUser.isActive) {
                    "Local cashier is inactive or does not match server authority"
                }
                require(existingUser.identityId == remoteUserId) {
                    "Local cashier identity does not match server authority"
                }
                val localMembership = userDao.getMembership(remoteUserId, remoteBranchId)
                    ?: throw IllegalStateException("Local cashier membership is missing")
                require(localMembership.id == membershipId) {
                    "Local membership does not match server authority"
                }
                require(localMembership.canCreateSale == permissions["CREATE_SALE"] &&
                    localMembership.canAcceptCash == permissions["ACCEPT_CASH"] &&
                    localMembership.canAcceptOnline == permissions["ACCEPT_ONLINE"] &&
                    localMembership.canCreateCreditSale == permissions["CREATE_CREDIT_SALE"] &&
                    localMembership.canUseCoupon == permissions["USE_COUPON"] &&
                    localMembership.canIssueInvoice == permissions["ISSUE_INVOICE"] &&
                    localMembership.canIssueReceipt == permissions["ISSUE_RECEIPT"] &&
                    localMembership.canRefund == permissions["REFUND"] &&
                    localMembership.canVoid == permissions["VOID"] &&
                    localMembership.canCorrectAmount == permissions["CORRECT_AMOUNT"] &&
                    localMembership.canChangePaymentMethod == permissions["CHANGE_PAYMENT_METHOD"] &&
                    localMembership.canViewBroadHistory == permissions["VIEW_BROAD_HISTORY"] &&
                    localMembership.canManageProducts == permissions["MANAGE_PRODUCTS"] &&
                    localMembership.canViewReports == permissions["VIEW_REPORTS"]) {
                    "Local permissions differ from server authority; restore is required"
                }
                return RemoteBusinessRestorationResult.Success(
                    businessId = remoteBusinessId,
                    branchId = remoteBranchId,
                    userId = remoteUserId
                )
            }
            val existingIdentity = userDao.getHumanIdentityById(remoteUserId)
            require(existingIdentity == null || existingIdentity.canonicalPhone == phone) {
                "Cashier identity conflict"
            }

            val salt = PinHasher.generateSalt()
            val localHash = PinHasher.hashPin(pin, salt)
            val restored = materializeLocal(
                businessId = result.getString("business_id"),
                businessName = result.getString("business_name"),
                branchId = result.getString("branch_id"),
                branchName = result.getString("branch_name"),
                branchCode = result.getString("branch_code"),
                countryCode = result.getString("country_code"),
                currencyCode = result.getString("currency_code"),
                currencyDecimalPlaces = result.getInt("currency_decimal_places"),
                currencySymbol = result.getString("currency_symbol"),
                timezoneId = result.getString("timezone_id"),
                taxRoundingMode = result.getString("tax_rounding_mode"),
                defaultTaxRateBps = result.getInt("default_tax_rate_bps"),
                branchAddress = result.optString("branch_address").ifBlank { null },
                branchPhone = result.optString("branch_phone").ifBlank { null },
                taxRegistrationNumber = result.optString("tax_registration_number").ifBlank { null },
                userId = result.getString("local_user_id"),
                displayName = result.getString("display_name"),
                username = phone,
                canonicalPhone = phone,
                role = UserRole.CASHIER,
                authType = AuthType.PIN,
                credentialHash = localHash,
                credentialSalt = salt,
                membershipId = membershipId,
                permissions = permissions,
                activatedAtMillis = System.currentTimeMillis()
            )
            if (restored is RemoteBusinessRestorationResult.Failed) {
                com.dailymoneyregister.core.cloud.sync.CashierCloudSession.clear()
            }
            restored
        } catch (e: Exception) {
            com.dailymoneyregister.core.cloud.sync.CashierCloudSession.clear()
            android.util.Log.e("PaidDueFinancialSync", "CASHIER_RESTORATION_FAILED", e)
            RemoteBusinessRestorationResult.Failed(
                "Unable to securely restore cashier business from the cloud"
            )
        }
    }
    override suspend fun validateAdminSession(
        businessId: String,
        branchId: String,
        userId: String
    ): Boolean {
        if (!remoteAdminAuthRepository.hasAuthenticatedSession()) return false
        return runCatching {
            val rows = supabase.postgrest.rpc("restore_admin_by_auth_id")
                .decodeList<RemoteStaffRestorationRpcRow>()
            val row = rows.singleOrNull() ?: return@runCatching false
            row.businessId == businessId &&
                row.branchId == branchId &&
                row.localUserId == userId
        }.getOrDefault(false)
    }

    override suspend fun validateCashierSession(membershipId: String): Boolean {
        return com.dailymoneyregister.core.cloud.sync.CashierCloudSession
            .validateSession(membershipId)
    }

    override suspend fun restoreAdminIfEmpty(
        usernameOrEmail: String,
        passwordPlain: String
    ): RemoteBusinessRestorationResult {
        // BOARDLOCK: phone login requires a real authenticated
        // server session. Never trust an anonymous restoration RPC.
        val isPhone = Regex(
            """^\+?\d[\d\s\-().]{6,19}$"""
        ).matches(usernameOrEmail.trim())

        if (isPhone) {
            return RemoteBusinessRestorationResult.Failed(
                "Secure admin phone authentication is not yet configured"
            )
        }
        // Email path or fallback
        val authResult = remoteAdminAuthRepository.signIn(usernameOrEmail, passwordPlain)
        if (authResult !is RemoteAdminAuthResult.Authenticated) {
            val reason = (authResult as? RemoteAdminAuthResult.Failed)?.message
                ?: "Admin cloud authentication failed"
            return RemoteBusinessRestorationResult.Failed(reason)
        }

        return try {
            // The RPC resolves auth.uid() server-side — no client-supplied
            // auth_user_id parameter, which would be an impersonation risk.
            val rpcRows = runCatching {
                supabase.postgrest.rpc("restore_admin_by_auth_id")
                    .decodeList<RemoteStaffRestorationRpcRow>()
            }.getOrNull()

            val row = rpcRows?.singleOrNull()
                ?: return RemoteBusinessRestorationResult.Failed("No cloud business found for Admin")

            // An authenticated Supabase identity must be authorized for this
            // exact local tenant. Existing local data is never overwritten.
            val existingBusiness = businessBranchDao.getFirstBusiness()
            if (existingBusiness != null) {
                val existingBranch = businessBranchDao.getFirstBranch(existingBusiness.id)
                val localUser = userDao.getUserById(row.localUserId)
                val localMembership = userDao.getMembership(row.localUserId, row.branchId)
                if (existingBusiness.id != row.businessId ||
                    existingBranch?.id != row.branchId ||
                    localUser?.id != row.localUserId ||
                    localUser.businessId != row.businessId ||
                    localUser.role != UserRole.ADMIN ||
                    localUser.authType != AuthType.PASSWORD ||
                    !localUser.isActive ||
                    localMembership?.id != row.membershipId) {
                    return RemoteBusinessRestorationResult.Failed(
                        "This Supabase account is not authorized for the business stored on this device"
                    )
                }
                return RemoteBusinessRestorationResult.Success(
                    row.businessId, row.branchId, row.localUserId
                )
            }

            val salt = row.credentialSalt ?: PasswordHasher.generateSalt()
            val hash = row.credentialHash ?: PasswordHasher.hashPassword(passwordPlain, salt)

            materializeLocal(
                businessId = row.businessId,
                businessName = row.businessName,
                branchId = row.branchId,
                branchName = row.branchName,
                branchCode = row.branchCode,
                countryCode = row.countryCode,
                currencyCode = row.currencyCode,
                currencyDecimalPlaces = row.currencyDecimalPlaces,
                currencySymbol = row.currencySymbol,
                timezoneId = row.timezoneId,
                taxRoundingMode = row.taxRoundingMode,
                defaultTaxRateBps = row.defaultTaxRateBps,
                branchAddress = row.branchAddress,
                branchPhone = row.branchPhone,
                taxRegistrationNumber = row.taxRegistrationNumber,
                userId = row.localUserId,
                displayName = row.displayName,
                // Store the verified email from Supabase auth — not the raw
                // user-typed string — as the canonical local username.
                username = authResult.email,
                canonicalPhone = row.canonicalPhone,
                role = UserRole.ADMIN,
                authType = AuthType.PASSWORD,
                credentialHash = hash,
                credentialSalt = salt,
                membershipId = row.membershipId,
                permissions = row.permissions ?: emptyMap(),
                activatedAtMillis = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            RemoteBusinessRestorationResult.Failed(e.message ?: "Cloud Admin business restoration failed")
        }
    }

    suspend fun materializeLocal(
        businessId: String,
        businessName: String,
        branchId: String,
        branchName: String,
        branchCode: String,
        countryCode: String,
        currencyCode: String,
        currencyDecimalPlaces: Int,
        currencySymbol: String,
        timezoneId: String,
        taxRoundingMode: String,
        defaultTaxRateBps: Int,
        branchAddress: String?,
        branchPhone: String?,
        taxRegistrationNumber: String?,
        userId: String,
        displayName: String,
        username: String,
        canonicalPhone: String?,
        role: UserRole,
        authType: AuthType,
        credentialHash: String,
        credentialSalt: String?,
        membershipId: String,
        permissions: Map<String, Boolean>,
        activatedAtMillis: Long
    ): RemoteBusinessRestorationResult {
        val requiredPermissionKeys = setOf(
            "CREATE_SALE",
            "ACCEPT_CASH",
            "ACCEPT_ONLINE",
            "CREATE_CREDIT_SALE",
            "USE_COUPON",
            "ISSUE_INVOICE",
            "ISSUE_RECEIPT",
            "REFUND",
            "VOID",
            "CORRECT_AMOUNT",
            "CHANGE_PAYMENT_METHOD",
            "VIEW_BROAD_HISTORY",
            "MANAGE_PRODUCTS",
            "VIEW_REPORTS"
        )

        if (!permissions.keys.containsAll(requiredPermissionKeys)) {
            return RemoteBusinessRestorationResult.Failed(
                "Incomplete cloud membership permissions"
            )
        }

        return database.withTransaction {
            // Abort atomically on duplicate users or tenant conflicts; no financial changes.
            val existingPhoneOwner = if (canonicalPhone == null) null else userDao.getHumanIdentityByPhone(canonicalPhone)
            require(existingPhoneOwner == null || existingPhoneOwner.id == userId) { "Identity already linked to a different user" }
            val localUser = userDao.getUserById(userId)
            require(localUser == null) { "User already exists; no credential replacement permitted" }
            val localIdentity = userDao.getHumanIdentityById(userId)
            require(localIdentity == null || localIdentity.canonicalPhone == canonicalPhone) { "Identity phone conflict" }
            val currentBusiness = businessBranchDao.getFirstBusiness()
            require(currentBusiness == null || currentBusiness.id == businessId) { "Business identity conflict" }
            val currentBranch = businessBranchDao.getFirstBranch(businessId)
            require(currentBranch == null || currentBranch.id == branchId) { "Branch identity conflict" }
            val existingBusiness = businessBranchDao.getBusinessById(businessId)
            val business = existingBusiness ?: BusinessEntity(
                id = businessId,
                name = businessName,
                createdAt = activatedAtMillis,
                updatedAt = activatedAtMillis
            ).also { businessBranchDao.insertBusiness(it) }

            val existingBranch = businessBranchDao.getBranchById(branchId)
            val branch = existingBranch ?: BranchEntity(
                id = branchId,
                businessId = businessId,
                name = branchName,
                code = branchCode,
                countryCode = countryCode,
                currencyCode = currencyCode,
                currencyDecimalPlaces = currencyDecimalPlaces,
                currencySymbol = currencySymbol,
                timezoneId = timezoneId,
                taxRoundingMode = runCatching { TaxRoundingMode.valueOf(taxRoundingMode) }.getOrDefault(TaxRoundingMode.HALF_UP),
                defaultTaxRateBps = defaultTaxRateBps,
                address = branchAddress,
                phone = branchPhone,
                taxRegistrationNumber = taxRegistrationNumber,
                createdAt = activatedAtMillis,
                updatedAt = activatedAtMillis
            ).also { businessBranchDao.insertBranch(it) }

            if (canonicalPhone != null) {
                val existingIdentity = userDao.getHumanIdentityById(userId)
                if (existingIdentity == null) {
                    userDao.insertHumanIdentity(
                        HumanIdentityEntity(
                            id = userId,
                            canonicalPhone = canonicalPhone,
                            displayName = displayName,
                            isActive = true,
                            createdAt = activatedAtMillis,
                            updatedAt = activatedAtMillis
                        )
                    )
                }
            }

            val existingUser = userDao.getUserById(userId)
            val user = existingUser ?: UserAccountEntity(
                id = userId,
                identityId = userId,
                businessId = businessId,
                displayName = displayName,
                username = username,
                role = role,
                authType = authType,
                credentialHash = credentialHash,
                credentialSalt = credentialSalt,
                languageCode = "en",
                isActive = true,
                createdAt = activatedAtMillis,
                updatedAt = activatedAtMillis
            ).also { userDao.insertUser(it) }

            val existingMembership = userDao.getMembership(userId, branchId)
            if (existingMembership == null) {
                userDao.insertMembership(
                    UserBranchMembershipEntity(
                        id = membershipId,
                        userId = userId,
                        branchId = branchId,
                        role = role,
                        canCreateSale = permissions["CREATE_SALE"] ?: false,
                        canAcceptCash = permissions["ACCEPT_CASH"] ?: false,
                        canAcceptOnline = permissions["ACCEPT_ONLINE"] ?: false,
                        canCreateCreditSale = permissions["CREATE_CREDIT_SALE"] ?: false,
                        canUseCoupon = permissions["USE_COUPON"] ?: false,
                        canIssueInvoice = permissions["ISSUE_INVOICE"] ?: false,
                        canIssueReceipt = permissions["ISSUE_RECEIPT"] ?: false,
                        canRefund = permissions["REFUND"] ?: false,
                        canVoid = permissions["VOID"] ?: false,
                        canCorrectAmount = permissions["CORRECT_AMOUNT"] ?: false,
                        canChangePaymentMethod = permissions["CHANGE_PAYMENT_METHOD"] ?: false,
                        canViewBroadHistory = permissions["VIEW_BROAD_HISTORY"] ?: false,
                        canManageProducts = permissions["MANAGE_PRODUCTS"] ?: false,
                        canViewReports = permissions["VIEW_REPORTS"] ?: false,
                        createdAt = activatedAtMillis
                    )
                )
            }

            RemoteBusinessRestorationResult.Success(businessId, branchId, userId)
        }
    }
}
