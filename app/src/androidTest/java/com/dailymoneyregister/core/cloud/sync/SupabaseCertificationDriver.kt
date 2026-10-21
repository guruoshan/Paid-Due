package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
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
    override suspend fun verifyOutbox(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = unavailable()
    override suspend fun uploadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = unavailable()
    override suspend fun verifyEnvelopeAndDigest(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject = unavailable()
    override suspend fun downloadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject = unavailable()
    override suspend fun snapshotSender(context: FinancialCertificationExecutionEngine.OperationContext): String = unavailable()
    override suspend fun materializeOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject = unavailable()
    override suspend fun snapshotReceiver(context: FinancialCertificationExecutionEngine.OperationContext): String = unavailable()
    override suspend fun replayOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject = unavailable()
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
