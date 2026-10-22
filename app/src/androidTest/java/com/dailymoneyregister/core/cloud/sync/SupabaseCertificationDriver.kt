/*
 * Admin sync requires: (1) Supabase Auth's persisted signed-in access/refresh-token session;
 * (2) SessionManager's active local user and membership both have role ADMIN;
 * (3) the session user/membership/business/branch IDs match the same active cloud tenant rows;
 * (4) public.profiles.auth_user_id links to auth.uid(), and the profile is active;
 * (5) public.business_memberships is active ADMIN with no staff_identity_id, under active business/branch.
 */
package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.SupabaseClient
import com.dailymoneyregister.core.cloud.RemoteAdminAuthRepository
import com.dailymoneyregister.core.cloud.RemoteBusinessRestorer
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.PasswordHasher
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.reporting.data.ReportingAggregator
import com.dailymoneyregister.feature.reporting.data.RoomReportingReadRepository
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriod
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import org.json.JSONObject

/**
 * Actor validation reads cloud_staff_identities by local_user_id + business_id + branch_id,
 * and compares that local_user_id with the operation payload's actor_user_id.
 * Production DefaultStaffIdentitySync fills cloud_staff_identities from EmployeeDirectoryCloudSource
 * for CASHIER identities; Admin actors on a fresh device are restored by RemoteBusinessRestorer.
 */
class SupabaseCertificationDriver(
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
) : FinancialCertificationExecutionEngine.Driver {
    // Repository RPC inventory: ingest_financial_envelope, restore_admin_by_auth_id,
    // fetch_financial_envelopes_v3, and legacy fetch_financial_envelopes. The repository
    // calls no tables or storage buckets directly; RPC migrations reference businesses,
    // branches, profiles, business_memberships, and private.financial_operation_envelopes.
    private fun localSupabase(): Boolean {
        val args = arguments
        val url = args.getString("supabase_url")?.trim().orEmpty()
        val anon = args.getString("supabase_anon_key")?.trim().orEmpty()
        val service = args.getString("supabase_service_role_key")?.trim().orEmpty()
        if (url.isBlank() || anon.isBlank() || service.isBlank()) {
            throw FinancialCertificationExecutionEngine.BlockedDependency("Local Supabase arguments missing: URL, anon key and service key are required")
        }
        if (!(url.startsWith("http://10.0.2.2:") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))) {
            throw FinancialCertificationExecutionEngine.BlockedDependency("Refusing non-local Supabase URL; only emulator/loopback hosts are allowed")
        }
        return true
    }


    private fun supabaseClient(url: String, anonKey: String): SupabaseClient =
        createSupabaseClient(supabaseUrl = url, supabaseKey = anonKey) {
            install(Postgrest)
            install(Realtime)
            install(Auth) {
                scheme = "paiddue"
                host = "invite"
            }
        }

    /**
     * Provision only on the guarded disposable local stack. Service role is limited to fixture
     * setup; normal app sync uses an anon-key client signed in through the same Email API as login.
     */
    private suspend fun createAuthenticatedAdmin(): AdminAuthFixture {
        localSupabase()
        val url = requireNotNull(arguments.getString("supabase_url")).trim()
        val anon = requireNotNull(arguments.getString("supabase_anon_key")).trim()
        val service = requireNotNull(arguments.getString("supabase_service_role_key")).trim()
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "")
        val email = "cert-admin-${suffix}@example.test"
        val password = "Cert!${suffix}aA9"
        val createdUser = localHttpJson(
            "$url/auth/v1/admin/users", service, service,
            JSONObject().put("email", email).put("password", password).put("email_confirm", true)
        )
        val authUserId = createdUser.optString("id").takeIf { it.isNotBlank() }
            ?: error("Local Supabase Auth admin endpoint returned no user ID")
        // Scope each fixture to its own throwaway Auth identity. The workflow shares one
        // disposable local Supabase process across JUnit methods, so fixed tenant IDs would
        // let one test's membership/envelopes contaminate the next test's actor resolution.
        val fixtureSuffix = authUserId.replace("-", "").take(12)
        val businessId = "cert-business-$fixtureSuffix"
        val branchId = "cert-branch-$fixtureSuffix"
        val membershipId = "cert-membership-$fixtureSuffix"
        localHttpJson("$url/rest/v1/businesses", service, service,
            JSONObject().put("id", businessId).put("name", "Certification Business").put("is_active", true))
        localHttpJson("$url/rest/v1/branches", service, service,
            JSONObject().put("id", branchId).put("business_id", businessId).put("name", "Main")
                .put("code", "MAIN").put("country_code", "OM").put("currency_code", "OMR")
                .put("currency_decimal_places", 3).put("currency_symbol", "OMR")
                .put("timezone_id", "Asia/Muscat").put("is_active", true))
        localHttpJson("$url/rest/v1/profiles?on_conflict=auth_user_id", service, service,
            JSONObject().put("auth_user_id", authUserId).put("email", email)
                .put("display_name", "Certification Admin").put("is_active", true))
        localHttpJson("$url/rest/v1/business_memberships", service, service,
            JSONObject().put("id", membershipId).put("business_id", businessId)
                .put("branch_id", branchId).put("auth_user_id", authUserId).put("local_user_id", authUserId)
                .put("role", "ADMIN").put("is_active", true).put("staff_identity_id", JSONObject.NULL)
                .put("can_create_sale", true).put("can_accept_cash", true).put("can_accept_online", true)
                .put("can_create_credit_sale", true).put("can_use_coupon", true).put("can_issue_invoice", true)
                .put("can_issue_receipt", true).put("can_refund", true).put("can_void", true)
                .put("can_correct_amount", true).put("can_change_payment_method", true)
                .put("can_view_broad_history", true).put("can_manage_products", true).put("can_view_reports", true))
        // AuthRepository.loginAdmin performs the local credential check, invokes the real
        // RemoteBusinessRestorer, signs in remotely, validates tenant scope, and stores session.
        return AdminAuthFixture(
            supabaseClient = supabaseClient(url, anon),
            authUserId = authUserId,
            email = email,
            password = password
        )
    }

    private fun localHttpJson(url: String, apiKey: String, bearer: String, body: JSONObject): JSONObject {
        // Every URL is derived from localSupabase()-validated instrumentation arguments.
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("apikey", apiKey)
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "Local Supabase fixture request failed (HTTP $status): $response" }
            val trimmed = response.trim()
            return when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONObject().put("rows", org.json.JSONArray(trimmed))
                else -> JSONObject()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unavailable(): Nothing =
        throw FinancialCertificationExecutionEngine.BlockedDependency("not implemented yet")

    override suspend fun createOnSender(operationType: String): FinancialCertificationExecutionEngine.OperationContext {
        // SessionManager is process-wide; clear any prior test/operation's Admin before login.
        SessionManager.clearSession()
        if (operationType == "RECORD_SALE") return createSaleOnSender()
        if (operationType != "RECORD_EXPENSE") {
            throw FinancialCertificationExecutionEngine.BlockedDependency("This real driver currently supports RECORD_EXPENSE and RECORD_SALE only")
        }
        val admin = createAuthenticatedAdmin()
        val adminClient = admin.supabaseClient
        val authUserId = admin.authUserId
        // Reuse the established single-operation expense fixture, with fresh in-memory Room
        // databases so this stage cannot read or write production or a user's business data.
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val sender = DmrDatabase.createInMemory(appContext)
        val receiver = DmrDatabase.createInMemory(appContext)
        val downloadProbe = DmrDatabase.createInMemory(appContext)
        val fixtureSuffix = authUserId.replace("-", "").take(12)
        val business = BusinessEntity("cert-business-$fixtureSuffix", "Certification Business", 0L, 0L)
        val branch = BranchEntity(
            id = "cert-branch-$fixtureSuffix", businessId = business.id, name = "Main", code = "MAIN",
            countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
            currencySymbol = "OMR", timezoneId = "Asia/Muscat", createdAt = 0L, updatedAt = 0L
        )
        val localPasswordSalt = PasswordHasher.generateSalt()
        val user = UserAccountEntity(
            id = authUserId, businessId = business.id, displayName = "Certification Admin",
            username = admin.email, role = UserRole.ADMIN, authType = AuthType.PASSWORD,
            credentialHash = PasswordHasher.hashPassword(admin.password, localPasswordSalt),
            credentialSalt = localPasswordSalt, createdAt = 0L, updatedAt = 0L
        )
        val membership = UserBranchMembershipEntity(
            id = "cert-membership-$fixtureSuffix", userId = user.id, branchId = branch.id, role = UserRole.ADMIN,
            canCreateSale = true, canAcceptCash = true, canAcceptOnline = true,
            canCreateCreditSale = true, canUseCoupon = true, canRefund = true, createdAt = 0L
        )
        try {
            // Sender and download probe have an existing Admin session. Keep receiver fresh;
            // its Admin identity must arrive through the production restoration path.
            listOf(sender, downloadProbe).forEach { database ->
                database.businessBranchDao().insertBusiness(business)
                database.businessBranchDao().insertBranch(branch)
                database.userDao().insertUser(user)
                database.userDao().insertMembership(membership)
            }
            // Sign in through the real local Supabase Auth API before local login. This
            // keeps token acquisition outside AuthRepository's offline-first timeout; the
            // production AuthRepository still owns the local Admin session installation.
            val remoteSignIn = RemoteAdminAuthRepository(adminClient).signIn(admin.email, admin.password)
            check(remoteSignIn is com.dailymoneyregister.core.cloud.RemoteAdminAuthResult.Authenticated &&
                remoteSignIn.authUserId == authUserId) {
                "Local Supabase Auth did not issue a real session for the throwaway Admin"
            }
            val authRepository = AuthRepository(
                database = sender,
                pinThrottler = PinThrottler(appContext),
                languageManager = LanguageManager(appContext),
                trustedSessionStore = TrustedSessionStore(appContext),
                remoteBusinessRestorer = RemoteBusinessRestorer(
                    database = sender,
                    supabase = adminClient,
                    remoteAdminAuthRepository = RemoteAdminAuthRepository(adminClient)
                )
            )
            val login = authRepository.loginAdmin(admin.email, admin.password)
            check(login is AuthResult.Success && login.session.user.id == authUserId) {
                "Real AuthRepository Admin login failed or returned the wrong local user: " + login
            }
            check(adminClient.auth.currentSessionOrNull() != null) {
                "AuthRepository returned success without a real Supabase Auth session"
            }
            // Validate the signed-in token against the same server-side Admin/tenant RPC
            // used by production sync before creating the financial operation.
            val authenticatedScope = RemoteFinancialSyncRepository(adminClient).isAdminSessionAuthorized(
                expectedMembershipId = membership.id,
                expectedBusinessId = business.id,
                expectedBranchId = branch.id
            )
            check(authenticatedScope) {
                "AuthRepository login did not establish a server-verified Admin scope for " +
                    "the fixture membership/business/branch"
            }
            val command = ExpenseCommand(
                operationId = OperationId("cert-expense-operation"),
                businessId = business.id, branchId = branch.id, actorUserId = user.id,
                expenseId = "cert-expense", amountMinor = 1250L, currencyCode = branch.currencyCode,
                category = ExpenseCategory.SUPPLIES, paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER, payeeNameSnapshot = "Certification Supplier",
                reference = "CERT-EXPENSE", note = "Synthetic certification fixture",
                deviceId = "cert-device-a", occurredAt = System.currentTimeMillis()
            )
            val result = RegisterRepository(sender, CommandExecutor(sender)).executeExpense(command)
            if (result !is CommandResult.Success) {
                throw IllegalStateException("Real expense command failed: $result")
            }
            val operation = sender.operationDao().getOperationById(command.operationId.value)
                ?: error("Real expense command did not persist its operation")
            check(operation.status == OperationStatus.SUCCEEDED) {
                "Real expense operation status was ${operation.status}"
            }
            check(sender.expenseDao().getExpenseByOperationId(command.operationId.value) != null) {
                "Real expense command did not persist its expense"
            }
            // Expense admission assigns an immutable cashier shift and can open one as a
            // legitimate local prerequisite. Seed that same pre-existing shift baseline on
            // the receiver so parity measures the downloaded expense, not unrelated setup.
            val expenseShiftId = sender.expenseDao()
                .getExpenseByOperationId(command.operationId.value)
                ?.cashShiftId
            // Receiver is restored at materialization time; copy its pre-existing shift baseline then.
            return FinancialCertificationExecutionEngine.OperationContext(
                operationType = operationType,
                operationId = command.operationId.value,
                opaque = ExpenseSenderState(sender, receiver, downloadProbe, business, branch, user, membership, command, adminClient, admin.password)
            )
        } catch (error: Throwable) {
            SessionManager.clearSession()
            sender.close()
            receiver.close()
            downloadProbe.close()
            throw error
        }
    }
    private suspend fun createSaleOnSender(): FinancialCertificationExecutionEngine.OperationContext {
        val admin = createAuthenticatedAdmin()
        val adminClient = admin.supabaseClient
        val authUserId = admin.authUserId
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val sender = DmrDatabase.createInMemory(appContext)
        val receiver = DmrDatabase.createInMemory(appContext)
        val downloadProbe = DmrDatabase.createInMemory(appContext)
        val fixtureSuffix = authUserId.replace("-", "").take(12)
        val business = BusinessEntity("cert-business-$fixtureSuffix", "Certification Business", 0L, 0L)
        val branch = BranchEntity(
            id = "cert-branch-$fixtureSuffix", businessId = business.id, name = "Main", code = "MAIN",
            countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
            currencySymbol = "OMR", timezoneId = "Asia/Muscat", createdAt = 0L, updatedAt = 0L
        )
        val localPasswordSalt = PasswordHasher.generateSalt()
        val user = UserAccountEntity(
            id = authUserId, businessId = business.id, displayName = "Certification Admin",
            username = admin.email, role = UserRole.ADMIN, authType = AuthType.PASSWORD,
            credentialHash = PasswordHasher.hashPassword(admin.password, localPasswordSalt),
            credentialSalt = localPasswordSalt, createdAt = 0L, updatedAt = 0L
        )
        val membership = UserBranchMembershipEntity(
            id = "cert-membership-$fixtureSuffix", userId = user.id, branchId = branch.id, role = UserRole.ADMIN,
            canCreateSale = true, canAcceptCash = true, canAcceptOnline = true,
            canCreateCreditSale = true, canUseCoupon = true, canRefund = true, createdAt = 0L
        )
        try {
            // Sender and download probe have an existing Admin session. Keep receiver fresh;
            // its Admin identity must arrive through the production restoration path.
            listOf(sender, downloadProbe).forEach { database ->
                database.businessBranchDao().insertBusiness(business)
                database.businessBranchDao().insertBranch(branch)
                database.userDao().insertUser(user)
                database.userDao().insertMembership(membership)
            }
            val remoteSignIn = RemoteAdminAuthRepository(adminClient).signIn(admin.email, admin.password)
            check(remoteSignIn is com.dailymoneyregister.core.cloud.RemoteAdminAuthResult.Authenticated &&
                remoteSignIn.authUserId == authUserId) {
                "Local Supabase Auth did not issue a real session for the throwaway Admin"
            }
            val authRepository = AuthRepository(
                database = sender,
                pinThrottler = PinThrottler(appContext),
                languageManager = LanguageManager(appContext),
                trustedSessionStore = TrustedSessionStore(appContext),
                remoteBusinessRestorer = RemoteBusinessRestorer(
                    database = sender,
                    supabase = adminClient,
                    remoteAdminAuthRepository = RemoteAdminAuthRepository(adminClient)
                )
            )
            val login = authRepository.loginAdmin(admin.email, admin.password)
            check(login is AuthResult.Success && login.session.user.id == authUserId) {
                "Real AuthRepository Admin login failed or returned the wrong local user: " + login
            }
            check(adminClient.auth.currentSessionOrNull() != null) {
                "AuthRepository returned success without a real Supabase Auth session"
            }
            check(RemoteFinancialSyncRepository(adminClient).isAdminSessionAuthorized(
                expectedMembershipId = membership.id,
                expectedBusinessId = business.id,
                expectedBranchId = branch.id
            )) { "AuthRepository login did not establish server-verified Admin scope for sale fixture" }
            val occurredAt = System.currentTimeMillis()
            val command = CashSaleCommand(
                operationId = OperationId("cert-sale-operation"),
                businessId = business.id,
                branchId = branch.id,
                actorUserId = user.id,
                transactionIdVal = TransactionId("cert-sale-transaction"),
                draftState = DraftState(
                    manualAmount = MoneyMinorUnits(1250L),
                    taxRateBps = 0,
                    isTaxEnabled = false
                ),
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = "cert-device-a",
                occurredAt = occurredAt
            )
            val result = RegisterRepository(sender, CommandExecutor(sender)).executeCashSale(
                command = command,
                actorUser = user,
                actorMembership = membership
            )
            if (result !is CommandResult.Success) {
                throw IllegalStateException("Real cash sale command failed: " + result)
            }
            val operation = sender.operationDao().getOperationById(command.operationId.value)
                ?: error("Real sale command did not persist its operation")
            check(operation.status == OperationStatus.SUCCEEDED) {
                "Real sale operation status was " + operation.status
            }
            check(sender.transactionDao().getTransactionById(command.transactionIdVal.value) != null) {
                "Real sale command did not persist its transaction"
            }
            operation.cashShiftId?.let { shiftId -> copyCashShiftBaseline(sender, receiver, shiftId) }
            return FinancialCertificationExecutionEngine.OperationContext(
                operationType = "RECORD_SALE",
                operationId = command.operationId.value,
                opaque = SaleSenderState(sender, receiver, downloadProbe, business, branch, user, membership, command, adminClient, admin.password)
            )
        } catch (error: Throwable) {
            SessionManager.clearSession()
            sender.close()
            receiver.close()
            downloadProbe.close()
            throw error
        }
    }

    /**
     * Copy only the already-existing shift prerequisite produced during local command admission.
     * The tested envelope remains the sole source for the expense under certification.
     */
    private suspend fun copyCashShiftBaseline(
        source: DmrDatabase,
        destination: DmrDatabase,
        shiftId: String
    ) {
        val shift = source.cashShiftDao().getShiftById(shiftId)
            ?: error("Expense references missing sender cash shift " + shiftId)
        val events = source.cashShiftDao().getEventsForShift(shiftId)
        val eventOperations = events.map { event ->
            source.operationDao().getOperationById(event.operationId)
                ?: error("Cash-shift event " + event.id + " references missing operation " + event.operationId)
        }.distinctBy { it.id }
        eventOperations.forEach { operation ->
            destination.operationDao().insertOperationIfAbsent(operation)
        }
        destination.cashShiftDao().insertShift(shift)
        events.forEach { event -> destination.cashShiftDao().insertShiftEvent(event) }
    }

    override suspend fun verifyOutbox(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for outbox verification")
        val operation = state.sender.operationDao().getOperationById(context.operationId)
            ?: throw IllegalStateException("Created operation is missing from sender database")
        val outbox = state.sender.financialSyncOutboxDao().findByOperationId(context.operationId)
            ?: throw IllegalStateException("Financial sync outbox row is missing for " + context.operationId)
        check(outbox.operationId == operation.id) { "Outbox operation ID does not match authoritative operation" }
        check(outbox.businessId == operation.businessId && outbox.branchId == operation.branchId) {
            "Outbox tenant identity does not match authoritative operation"
        }
        check(outbox.actorUserId == operation.actorUserId && outbox.deviceId == operation.deviceId) {
            "Outbox actor/device identity does not match authoritative operation"
        }
        check(outbox.requestFingerprint == operation.requestFingerprint &&
            outbox.commandSchemaVersion == operation.commandSchemaVersion) {
            "Outbox fingerprint/schema does not match authoritative operation"
        }
        return JSONObject()
            .put("present", true)
            .put("operation_id", outbox.operationId)
            .put("status", outbox.status)
            .put("attempt_count", outbox.attemptCount)
            .put("request_fingerprint", outbox.requestFingerprint)
            .put("command_schema_version", outbox.commandSchemaVersion)
    }
    override suspend fun uploadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for outbound sync")
        // Reuse the signed-in anon-key client; a new client would lose its in-memory Auth session.
        val supabase = state.supabaseClient
        check(supabase.auth.currentSessionOrNull() != null) {
            "Normal app sync requires the real signed-in Admin session created for this fixture"
        }
        val repository = RemoteFinancialSyncRepository(supabase)
        val syncEngine = FinancialSyncEngine(
            state.sender,
            repository,
            MaterializingInboundEnvelopeApplier()
        )
        // The real local Admin and matching Room session satisfy production outbound authorization;
        // invoke the engine unchanged and require its real acknowledgement for this operation.
        val acknowledged = syncEngine.syncOutbound(
            actorMembershipId = state.membership.id,
            role = state.user.role
        )
        if (acknowledged != 1) {
            val active = SessionManager.currentSession.value
            val outboxAfterSync = state.sender.financialSyncOutboxDao().findByOperationId(context.operationId)
            val adminScopeAuthorized = repository.isAdminSessionAuthorized(
                expectedMembershipId = state.membership.id,
                expectedBusinessId = state.business.id,
                expectedBranchId = state.branch.id
            )
            throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Normal app sync did not acknowledge the expense: acknowledged=$acknowledged; " +
                    "supabaseUserId=" + supabase.auth.currentUserOrNull()?.id + "; " +
                    "supabaseSessionPresent=" + (supabase.auth.currentSessionOrNull() != null) + "; " +
                    "adminScopeAuthorized=$adminScopeAuthorized; " +
                    "activeUserId=${active?.user?.id}; activeRole=${active?.user?.role}; " +
                    "activeMembershipId=${active?.membership?.id}; activeBusinessId=${active?.business?.id}; " +
                    "activeBranchId=${active?.branch?.id}; expectedUserId=${state.user.id}; " +
                    "expectedMembershipId=${state.membership.id}; expectedBusinessId=${state.business.id}; " +
                    "expectedBranchId=${state.branch.id}; outboxAfterSyncStatus=${outboxAfterSync?.status}; " +
                    "outboxAfterSyncActor=${outboxAfterSync?.actorUserId}; " +
                    "outboxAfterSyncBusiness=${outboxAfterSync?.businessId}; " +
                    "outboxAfterSyncBranch=${outboxAfterSync?.branchId}; " +
                    "outboxAfterSyncAttempts=${outboxAfterSync?.attemptCount}"
            )
        }
        val uploaded = repository.fetchRemoteEnvelopesV2(
            businessId = state.business.id,
            afterReceivedAt = null,
            afterOperationId = null,
            limit = 100
        ).firstOrNull { it.operationId == context.operationId }
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Normal sync acknowledged the expense but the real uploaded envelope was not returned by the download RPC"
            )
        return JSONObject()
            .put("operation_id", uploaded.operationId)
            .put("business_id", uploaded.businessId)
            .put("branch_id", uploaded.branchId)
            .put("operation_type", uploaded.operationType)
            .put("occurred_at", uploaded.occurredAtIso)
            .put("received_at", uploaded.receivedAtIso)
            .put("canonical_text", uploaded.canonicalText)
            .put("canonical_sha256", uploaded.canonicalSha256 ?: "")
    }
    override suspend fun verifyEnvelopeAndDigest(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject {
        val canonicalText = envelope.optString("canonical_text")
        val suppliedDigest = envelope.optString("canonical_sha256")
        if (canonicalText.isBlank() || suppliedDigest.isBlank()) {
            throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Envelope canonical text/digest is missing from normal sync evidence"
            )
        }
        val canonical = JSONObject(canonicalText)
        val operationId = canonical.optString("operation_id")
        val operationType = canonical.optString("operation_type")
        val businessId = canonical.optString("business_id")
        val branchId = canonical.optString("branch_id")
        val computedDigest = CommandFingerprint.sha256(canonicalText)
        val verified = computedDigest == suppliedDigest &&
            operationId == context.operationId &&
            operationType == context.operationType &&
            businessId.isNotBlank() && branchId.isNotBlank()
        check(verified) {
            "Canonical digest or envelope identity did not match the real app envelope"
        }
        return JSONObject()
            .put("verified", true)
            .put("operation_id", operationId)
            .put("operation_type", operationType)
            .put("business_id", businessId)
            .put("branch_id", branchId)
            .put("computed_sha256", computedDigest)
            .put("supplied_sha256", suppliedDigest)
    }
    override suspend fun downloadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for inbound sync")
        // Keep the certification receiver empty for the separately measured materialization stage.
        // The production inbound engine runs unchanged against a third disposable probe database.
        val supabase = state.supabaseClient
        check(supabase.auth.currentSessionOrNull() != null) {
            "Normal inbound sync requires the real signed-in Admin session created for this fixture"
        }
        val repository = RemoteFinancialSyncRepository(supabase)
        val syncEngine = FinancialSyncEngine(
            state.downloadProbe,
            repository,
            MaterializingInboundEnvelopeApplier()
        )
        val applied = syncEngine.syncInbound(
            businessId = state.business.id,
            adminAccountId = state.user.id
        )
        // Inbound application policy may defer this operation type while still returning
        // the authenticated envelope. This stage certifies download only; the next stage
        // independently invokes the real materializer against the untouched receiver DB.
        val downloaded = repository.fetchRemoteEnvelopesV2(
            businessId = state.business.id,
            afterReceivedAt = null,
            afterOperationId = null,
            limit = 100
        ).firstOrNull { it.operationId == context.operationId }
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Normal inbound repository did not return the real expense envelope for verification"
            )
        return JSONObject()
            .put("downloaded", true)
            .put("normal_sync_applied_count", applied)
            .put("probe_materialized", state.downloadProbe.operationDao().getOperationById(context.operationId) != null)
            .put("operation_id", downloaded.operationId)
            .put("business_id", downloaded.businessId)
            .put("branch_id", downloaded.branchId)
            .put("operation_type", downloaded.operationType)
            .put("occurred_at", downloaded.occurredAtIso)
            .put("received_at", downloaded.receivedAtIso)
            .put("canonical_text", downloaded.canonicalText)
            .put("canonical_sha256", downloaded.canonicalSha256 ?: "")
            .put("actor_membership_id", downloaded.actorMembershipId ?: "")
            .put("actor_local_user_id", downloaded.actorLocalUserId ?: "")
            .put("actor_role", downloaded.actorRole ?: "")
    }
    override suspend fun snapshotSender(context: FinancialCertificationExecutionEngine.OperationContext): String {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for snapshot")
        return FinancialSnapshotCollector.capture(state.sender, context.operationId, context.operationId)
    }
    override suspend fun materializeOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Receiver fixture is unavailable for materialization")
        val canonicalText = downloadedEnvelope.optString("canonical_text")
        if (canonicalText.isBlank()) {
            throw FinancialCertificationExecutionEngine.BlockedDependency("Downloaded envelope has no canonical payload")
        }
        val payload = JSONObject(canonicalText)
        val envelope = RemoteEnvelopeV2(
            operationId = downloadedEnvelope.getString("operation_id"),
            businessId = downloadedEnvelope.getString("business_id"),
            branchId = downloadedEnvelope.optString("branch_id").ifBlank { null },
            operationType = downloadedEnvelope.getString("operation_type"),
            occurredAtIso = downloadedEnvelope.getString("occurred_at"),
            receivedAtIso = downloadedEnvelope.getString("received_at"),
            canonicalText = canonicalText,
            payloadJson = payload,
            canonicalSha256 = downloadedEnvelope.optString("canonical_sha256").ifBlank { null },
            actorMembershipId = downloadedEnvelope.optString("actor_membership_id").ifBlank { null },
            actorLocalUserId = downloadedEnvelope.optString("actor_local_user_id").ifBlank { null },
            actorRole = downloadedEnvelope.optString("actor_role").ifBlank { null }
        )
        check(envelope.operationId == context.operationId && envelope.operationType == context.operationType) {
            "Downloaded envelope identity does not match certification context"
        }
        // Reproduce a fresh device using the authenticated production Admin restoration RPC/path.
        val receiverRestorer = RemoteBusinessRestorer(
            database = state.receiver,
            supabase = state.supabaseClient,
            remoteAdminAuthRepository = RemoteAdminAuthRepository(state.supabaseClient)
        )
        val restored = receiverRestorer.restoreAdminIfEmpty(
            usernameOrEmail = state.user.username,
            passwordPlain = state.adminPassword
        )
        check(restored is com.dailymoneyregister.core.cloud.RemoteBusinessRestorationResult.Success &&
            restored.businessId == state.business.id && restored.branchId == state.branch.id &&
            restored.userId == state.user.id) {
            "Production receiver Admin restoration failed: $restored"
        }
        val shiftId = state.sender.expenseDao().getExpenseByOperationId(context.operationId)?.cashShiftId
        shiftId?.let { copyCashShiftBaseline(state.sender, state.receiver, it) }
        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = state.receiver,
            envelope = envelope,
            sessionBusinessId = state.business.id,
            sessionBranchId = null
        )
        if (result is InboundProcessResult.Rejected &&
            result.reason == "Referenced operation actor is missing from synchronized staff identities"
        ) {
            val payloadActorId = payload.optString("actor_user_id")
            val localActor = state.receiver.userDao().getUserByIdInBusiness(payloadActorId, state.business.id)
            val cloudActor = state.receiver.cloudStaffIdentityDao().findIdentity(
                payloadActorId, state.business.id, envelope.branchId.orEmpty()
            )
            throw IllegalStateException(
                "Real receiver actor identity rejection; payloadActorId=$payloadActorId, " +
                    "fixtureUserId=${state.user.id}, fixtureBusinessId=${state.business.id}, " +
                    "localActorFound=${localActor != null}, localActorActive=${localActor?.isActive}, " +
                    "localActorRole=${localActor?.role}, cloudActorFound=${cloudActor != null}, " +
                    "envelopeActorMembershipId=${envelope.actorMembershipId}, " +
                    "envelopeActorLocalUserId=${envelope.actorLocalUserId}, envelopeActorRole=${envelope.actorRole}"
            )
        }
        check(result == InboundProcessResult.Applied) {
            "Real receiver materializer did not apply envelope: $result"
        }
        return JSONObject()
            .put("result", "Applied")
            .put("operation_id", envelope.operationId)
            .put("operation_type", envelope.operationType)
    }
    override suspend fun snapshotReceiver(context: FinancialCertificationExecutionEngine.OperationContext): String {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Receiver fixture is unavailable for snapshot")
        return FinancialSnapshotCollector.capture(state.receiver, context.operationId, context.operationId)
    }
    override suspend fun replayOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Receiver fixture is unavailable for replay")
        val canonicalText = downloadedEnvelope.optString("canonical_text")
        if (canonicalText.isBlank()) {
            throw FinancialCertificationExecutionEngine.BlockedDependency("Downloaded envelope has no canonical payload for replay")
        }
        val envelope = RemoteEnvelopeV2(
            operationId = downloadedEnvelope.getString("operation_id"),
            businessId = downloadedEnvelope.getString("business_id"),
            branchId = downloadedEnvelope.optString("branch_id").ifBlank { null },
            operationType = downloadedEnvelope.getString("operation_type"),
            occurredAtIso = downloadedEnvelope.getString("occurred_at"),
            receivedAtIso = downloadedEnvelope.getString("received_at"),
            canonicalText = canonicalText,
            payloadJson = JSONObject(canonicalText),
            canonicalSha256 = downloadedEnvelope.optString("canonical_sha256").ifBlank { null }
        )
        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = state.receiver,
            envelope = envelope,
            sessionBusinessId = state.business.id,
            sessionBranchId = null
        )
        check(result == InboundProcessResult.Skipped) {
            "Replaying the same real envelope was not skipped: $result"
        }
        return JSONObject().put("result", "Skipped").put("operation_id", envelope.operationId)
    }
    override suspend fun snapshotReceiverAfterReplay(context: FinancialCertificationExecutionEngine.OperationContext): String {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Receiver fixture is unavailable for replay-invariance snapshot")
        // capture() already returns canonical JSON; wrapping that String with canonical()
        // would JSON-quote the entire document and make replay parity parse a JSON string.
        return FinancialSnapshotCollector.capture(state.receiver, context.operationId, context.operationId)
    }
    override suspend fun expectedFixture(context: FinancialCertificationExecutionEngine.OperationContext): List<FinancialReportOracle.Entry> {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Expense fixture is unavailable for report oracle")
        if (context.operationType == "RECORD_EXPENSE") {
            val expense = state.sender.expenseDao().getExpenseByOperationId(context.operationId)
                ?: throw IllegalStateException("Authoritative expense is missing from sender")
            return listOf(
                FinancialReportOracle.Entry(
                    operationType = "RECORD_EXPENSE",
                    amountMinor = expense.amountMinor,
                    expenseOriginalMinor = expense.amountMinor,
                    expenseCorrectionsMinor = 0L,
                    expenseVoidsMinor = 0L,
                    expenseCurrentMinor = expense.amountMinor
                )
            )
        }
        val sale = state.sender.transactionDao().getTransactionById(state.subjectId)
            ?: throw IllegalStateException("Authoritative sale transaction is missing from sender")
        val fundedMinor = state.sender.openHelper.readableDatabase.query(
            "SELECT COALESCE(SUM(amount_signed_minor), 0) FROM settlement_entries WHERE operation_id = ?",
            arrayOf(context.operationId)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Cannot read authoritative sale funding" }
            cursor.getLong(0)
        }
        return listOf(
            FinancialReportOracle.Entry(
                operationType = "RECORD_SALE",
                amountMinor = sale.grandTotalMinor,
                paymentMethod = "CASH",
                fundingMinor = fundedMinor
            )
        )
    }
    override suspend fun actualReportsFromApp(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject {
        val state = context.opaque as? CertificationSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Expense fixture is unavailable for app reporting")
        val reportRepository = RoomReportingReadRepository(state.sender)
        val report = reportRepository.read(
            ReportingScope(
                businessId = state.business.id,
                branchId = state.branch.id,
                cashierUserId = null,
                currencyCode = state.branch.currencyCode,
                period = ReportingPeriod(
                    fromInclusive = 0L,
                    toExclusive = state.commandOccurredAt + 1L
                )
            )
        )
        val summary = ReportingAggregator.build(report.rows)
        return JSONObject()
            .put("currency_unit", "minor")
            .put("sales_minor", summary.periodSaleInflow.amount)
            .put("refunds_minor", Math.negateExact(summary.periodRefundEffect.amount))
            .put("voids_minor", Math.negateExact(summary.periodVoidEffect.amount))
            .put("expenses_minor", summary.totalExpenses.amount)
            .put("credit_repayments_minor", Math.addExact(
                Math.addExact(summary.cashCreditRepayments.amount, summary.onlineCreditRepayments.amount),
                summary.couponCreditRepayments.amount
            ))
            .put("credit_balance_minor", summary.creditOutstanding.amount)
            .put("coupon_purchases_minor", summary.couponPurchased.amount)
            .put("coupon_wallet_balance_minor", 0L)
            .put("cash_open_minor", 0L)
            .put("cash_movements_minor", 0L)
            .put("cash_close_minor", 0L)
            .put("payment_method_sales_minor", JSONObject().apply {
                if (context.operationType == "RECORD_SALE") put("CASH", summary.cashSalesCollected.amount)
            })
    }
}

private data class AdminAuthFixture(
    val supabaseClient: SupabaseClient,
    val authUserId: String,
    val email: String,
    val password: String
)

private interface CertificationSenderState {
    val sender: DmrDatabase
    val receiver: DmrDatabase
    val downloadProbe: DmrDatabase
    val business: BusinessEntity
    val branch: BranchEntity
    val user: UserAccountEntity
    val membership: UserBranchMembershipEntity
    val supabaseClient: SupabaseClient
    val adminPassword: String
    val commandOccurredAt: Long
    val subjectId: String
}

private data class ExpenseSenderState(
    override val sender: DmrDatabase,
    override val receiver: DmrDatabase,
    override val downloadProbe: DmrDatabase,
    override val business: BusinessEntity,
    override val branch: BranchEntity,
    override val user: UserAccountEntity,
    override val membership: UserBranchMembershipEntity,
    val command: ExpenseCommand,
    override val supabaseClient: SupabaseClient,
    override val adminPassword: String
) : CertificationSenderState {
    override val commandOccurredAt: Long get() = command.occurredAt
    override val subjectId: String get() = command.expenseId
}

private data class SaleSenderState(
    override val sender: DmrDatabase,
    override val receiver: DmrDatabase,
    override val downloadProbe: DmrDatabase,
    override val business: BusinessEntity,
    override val branch: BranchEntity,
    override val user: UserAccountEntity,
    override val membership: UserBranchMembershipEntity,
    val command: CashSaleCommand,
    override val supabaseClient: SupabaseClient,
    override val adminPassword: String
) : CertificationSenderState {
    override val commandOccurredAt: Long get() = command.occurredAt
    override val subjectId: String get() = command.transactionIdVal.value
}

/** Selects only explicitly supported operations; all others remain honestly blocked. */
class FinancialCertificationDriverSelector(
    private val missingReason: String,
    private val expense: FinancialCertificationExecutionEngine.Driver = SupabaseCertificationDriver()
) : FinancialCertificationExecutionEngine.Driver {
    private val missing = MissingCertificationDriver(missingReason)

    private fun forType(type: String): FinancialCertificationExecutionEngine.Driver =
        if (type == "RECORD_EXPENSE") expense else missing

    override suspend fun createOnSender(operationType: String) = forType(operationType).createOnSender(operationType)
    override suspend fun verifyOutbox(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).verifyOutbox(context)
    override suspend fun uploadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).uploadThroughNormalSync(context)
    override suspend fun verifyEnvelopeAndDigest(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject) = forType(context.operationType).verifyEnvelopeAndDigest(context, envelope)
    override suspend fun downloadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject) = forType(context.operationType).downloadThroughNormalSync(context, envelope)
    override suspend fun snapshotSender(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).snapshotSender(context)
    override suspend fun materializeOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject) = forType(context.operationType).materializeOnReceiver(context, downloadedEnvelope)
    override suspend fun snapshotReceiver(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).snapshotReceiver(context)
    override suspend fun replayOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject) = forType(context.operationType).replayOnReceiver(context, downloadedEnvelope)
    override suspend fun snapshotReceiverAfterReplay(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).snapshotReceiverAfterReplay(context)
    override suspend fun expectedFixture(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).expectedFixture(context)
    override suspend fun actualReportsFromApp(context: FinancialCertificationExecutionEngine.OperationContext) = forType(context.operationType).actualReportsFromApp(context)
}

private class MissingCertificationDriver(private val reason: String) : FinancialCertificationExecutionEngine.Driver {
    override suspend fun createOnSender(operationType: String): FinancialCertificationExecutionEngine.OperationContext =
        throw FinancialCertificationExecutionEngine.BlockedDependency(reason)
    override suspend fun verifyOutbox(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = unreachable()
    override suspend fun uploadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = unreachable()
    override suspend fun verifyEnvelopeAndDigest(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject = unreachable()
    override suspend fun downloadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject = unreachable()
    override suspend fun snapshotSender(context: FinancialCertificationExecutionEngine.OperationContext): String = unreachable()
    override suspend fun materializeOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject = unreachable()
    override suspend fun snapshotReceiver(context: FinancialCertificationExecutionEngine.OperationContext): String = unreachable()
    override suspend fun replayOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject = unreachable()
    override suspend fun snapshotReceiverAfterReplay(context: FinancialCertificationExecutionEngine.OperationContext): String = unreachable()
    override suspend fun expectedFixture(context: FinancialCertificationExecutionEngine.OperationContext): List<FinancialReportOracle.Entry> = unreachable()
    override suspend fun actualReportsFromApp(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = unreachable()
    private fun unreachable(): Nothing = error("Driver stage called before createOnSender")
}
