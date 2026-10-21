package com.dailymoneyregister.core.cloud.sync

import android.content.Context
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
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import org.json.JSONObject

/**
 * Certification driver skeleton. Every stage intentionally fails closed until its real
 * application-path implementation is added in follow-up commits.
 */
class SupabaseCertificationDriver : FinancialCertificationExecutionEngine.Driver {
    // Repository RPC inventory: ingest_financial_envelope, restore_admin_by_auth_id,
    // fetch_financial_envelopes_v3, and legacy fetch_financial_envelopes. The repository
    // calls no tables or storage buckets directly; RPC migrations reference businesses,
    // branches, profiles, business_memberships, and private.financial_operation_envelopes.
    private fun localSupabase(): Boolean {
        val args = InstrumentationRegistry.getArguments()
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

    private fun unavailable(): Nothing =
        throw FinancialCertificationExecutionEngine.BlockedDependency("not implemented yet")

    override suspend fun createOnSender(operationType: String): FinancialCertificationExecutionEngine.OperationContext {
        if (operationType != "RECORD_EXPENSE") {
            throw FinancialCertificationExecutionEngine.BlockedDependency("This real driver currently supports RECORD_EXPENSE only")
        }
        localSupabase()
        // Reuse the established single-operation expense fixture, with fresh in-memory Room
        // databases so this stage cannot read or write production or a user's business data.
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val sender = DmrDatabase.createInMemory(appContext)
        val receiver = DmrDatabase.createInMemory(appContext)
        val business = BusinessEntity("cert-business", "Certification Business", 0L, 0L)
        val branch = BranchEntity(
            id = "cert-branch", businessId = business.id, name = "Main", code = "MAIN",
            countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
            currencySymbol = "OMR", timezoneId = "Asia/Muscat", createdAt = 0L, updatedAt = 0L
        )
        val user = UserAccountEntity(
            id = "cert-cashier", businessId = business.id, displayName = "Certification Cashier",
            username = "cert_cashier", role = UserRole.CASHIER, authType = AuthType.PIN,
            credentialHash = "test-hash", credentialSalt = "test-salt", createdAt = 0L, updatedAt = 0L
        )
        val membership = UserBranchMembershipEntity(
            id = "cert-membership", userId = user.id, branchId = branch.id, role = UserRole.CASHIER,
            canCreateSale = true, canAcceptCash = true, canAcceptOnline = true,
            canCreateCreditSale = true, canUseCoupon = true, canRefund = true, createdAt = 0L
        )
        try {
            listOf(sender, receiver).forEach { database ->
                database.businessBranchDao().insertBusiness(business)
                database.businessBranchDao().insertBranch(branch)
                database.userDao().insertUser(user)
                database.userDao().insertMembership(membership)
            }
            SessionManager.setSession(ActiveSession(business, branch, user, membership))
            val command = ExpenseCommand(
                operationId = OperationId("cert-expense-operation"),
                businessId = business.id, branchId = branch.id, actorUserId = user.id,
                expenseId = "cert-expense", amountMinor = 1250L, currencyCode = branch.currencyCode,
                category = ExpenseCategory.SUPPLIES, paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER, payeeNameSnapshot = "Certification Supplier",
                reference = "CERT-EXPENSE", note = "Synthetic certification fixture",
                deviceId = "cert-device-a", occurredAt = 1_800_000_000_000L
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
            return FinancialCertificationExecutionEngine.OperationContext(
                operationType = operationType,
                operationId = command.operationId.value,
                opaque = ExpenseSenderState(sender, receiver, business, branch, user, membership, command)
            )
        } catch (error: Throwable) {
            SessionManager.clearSession()
            sender.close()
            receiver.close()
            throw error
        }
    }
    override suspend fun verifyOutbox(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject {
        val state = context.opaque as? ExpenseSenderState
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
        val state = context.opaque as? ExpenseSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for outbound sync")
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("supabase_url")?.trim().orEmpty()
        val anon = args.getString("supabase_anon_key")?.trim().orEmpty()
        // The local service-role key is intentionally not installed in the app client:
        // normal sync must run with the same least-privilege client as the application.
        if (url.isBlank() || anon.isBlank()) {
            throw FinancialCertificationExecutionEngine.BlockedDependency("Local Supabase URL/anon key missing for normal sync")
        }
        val supabase = createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = anon
        ) {
            install(Postgrest)
            install(Realtime)
            install(Auth) {
                scheme = "paiddue"
                host = "invite"
            }
        }
        val repository = RemoteFinancialSyncRepository(supabase)
        val syncEngine = FinancialSyncEngine(
            database = state.sender,
            remoteSyncRepository = repository,
            inboundEnvelopeApplier = MaterializingInboundEnvelopeApplier()
        )
        // The reused real command fixture is a cashier. The current production outbound
        // engine is Admin-only; invoke it unchanged and block unless it acknowledges this ID.
        val acknowledged = syncEngine.syncOutbound(
            actorMembershipId = state.membership.id,
            role = state.user.role
        )
        if (acknowledged != 1) {
            throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Normal app sync did not acknowledge the expense; current sync authorization/session requirements must be satisfied"
            )
        }
        throw FinancialCertificationExecutionEngine.BlockedDependency(
            "Normal app sync acknowledged the operation but the upload response envelope is not exposed by FinancialSyncEngine"
        )
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
        val state = context.opaque as? ExpenseSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for inbound sync")
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("supabase_url")?.trim().orEmpty()
        val anon = args.getString("supabase_anon_key")?.trim().orEmpty()
        if (url.isBlank() || anon.isBlank()) {
            throw FinancialCertificationExecutionEngine.BlockedDependency("Local Supabase URL/anon key missing for normal download")
        }
        val supabase = createSupabaseClient(supabaseUrl = url, supabaseKey = anon) {
            install(Postgrest)
            install(Realtime)
            install(Auth) {
                scheme = "paiddue"
                host = "invite"
            }
        }
        val repository = RemoteFinancialSyncRepository(supabase)
        val syncEngine = FinancialSyncEngine(
            database = state.receiver,
            remoteSyncRepository = repository,
            inboundEnvelopeApplier = MaterializingInboundEnvelopeApplier()
        )
        // Use the production inbound orchestration; no direct table writes or fake receipt.
        val applied = syncEngine.syncInbound(
            businessId = state.business.id,
            adminAccountId = state.user.id
        )
        val downloaded = repository.fetchRemoteEnvelopesV2(
            businessId = state.business.id,
            afterReceivedAt = null,
            afterOperationId = null,
            limit = 100
        ).firstOrNull { it.operationId == context.operationId }
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Normal inbound sync did not expose the expense envelope for verification"
            )
        if (applied < 1 && state.receiver.operationDao().getOperationById(context.operationId) == null) {
            throw FinancialCertificationExecutionEngine.BlockedDependency(
                "Normal inbound sync did not materialize the expense on the receiver"
            )
        }
        return JSONObject()
            .put("downloaded", true)
            .put("operation_id", downloaded.operationId)
            .put("business_id", downloaded.businessId)
            .put("branch_id", downloaded.branchId)
            .put("operation_type", downloaded.operationType)
            .put("occurred_at", downloaded.occurredAtIso)
            .put("received_at", downloaded.receivedAtIso)
            .put("canonical_text", downloaded.canonicalText)
            .put("canonical_sha256", downloaded.canonicalSha256)
    }
    override suspend fun snapshotSender(context: FinancialCertificationExecutionEngine.OperationContext): String {
        val state = context.opaque as? ExpenseSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Sender fixture is unavailable for snapshot")
        return FinancialSnapshotCollector.canonical(
            FinancialSnapshotCollector.capture(state.sender, context.operationId)
        )
    }
    override suspend fun materializeOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject {
        val state = context.opaque as? ExpenseSenderState
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
            canonicalSha256 = downloadedEnvelope.optString("canonical_sha256").ifBlank { null }
        )
        check(envelope.operationId == context.operationId && envelope.operationType == context.operationType) {
            "Downloaded envelope identity does not match certification context"
        }
        val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = state.receiver,
            envelope = envelope,
            sessionBusinessId = state.business.id,
            sessionBranchId = null
        )
        check(result == InboundProcessResult.Applied) {
            "Real receiver materializer did not apply envelope: $result"
        }
        return JSONObject()
            .put("result", "Applied")
            .put("operation_id", envelope.operationId)
            .put("operation_type", envelope.operationType)
    }
    override suspend fun snapshotReceiver(context: FinancialCertificationExecutionEngine.OperationContext): String {
        val state = context.opaque as? ExpenseSenderState
            ?: throw FinancialCertificationExecutionEngine.BlockedDependency("Receiver fixture is unavailable for snapshot")
        return FinancialSnapshotCollector.canonical(
            FinancialSnapshotCollector.capture(state.receiver, context.operationId)
        )
    }
    override suspend fun replayOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject {
        val state = context.opaque as? ExpenseSenderState
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
    override suspend fun snapshotReceiverAfterReplay(context: FinancialCertificationExecutionEngine.OperationContext): String = unavailable()
    override suspend fun expectedFixture(context: FinancialCertificationExecutionEngine.OperationContext): List<FinancialReportOracle.Entry> = unavailable()
    override suspend fun actualReportsFromApp(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = unavailable()
}

private data class ExpenseSenderState(
    val sender: DmrDatabase,
    val receiver: DmrDatabase,
    val business: BusinessEntity,
    val branch: BranchEntity,
    val user: UserAccountEntity,
    val membership: UserBranchMembershipEntity,
    val command: ExpenseCommand
)

/** Selects only explicitly supported operations; all others remain honestly blocked. */
class FinancialCertificationDriverSelector(
    private val missingReason: String
) : FinancialCertificationExecutionEngine.Driver {
    private val expense = SupabaseCertificationDriver()
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
