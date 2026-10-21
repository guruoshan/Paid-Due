package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
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
import com.dailymoneyregister.core.database.DmrDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two independent Room databases plus fail-closed execution evidence.
 *
 * Full operation execution remains BLOCKED until CI is supplied with an isolated
 * non-production Supabase project and an adapter using the application's real
 * command/outbox/upload/download paths. This test deliberately never contacts production.
 */
@RunWith(AndroidJUnit4::class)
class TwoDatabaseFinancialCertificationTest {
    private val missingIntegration = "Missing dependency: isolated non-production Supabase project, protected test credentials, and real app-path CertificationExecutionEngine.Driver. Production project access is intentionally not accepted."

    @Test
    fun createsIndependentDatabasesAndEmitsBlockedEvidenceForAllThirteenOperations() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseA = DmrDatabase.createInMemory(context)
        val databaseB = DmrDatabase.createInMemory(context)
        try {
            assertFalse("Sender and receiver must be separate Room instances", databaseA === databaseB)
            val snapshotA = FinancialSnapshotCollector.capture(databaseA, "schema-probe-a")
            val snapshotB = FinancialSnapshotCollector.capture(databaseB, "schema-probe-b")
            val parity = FinancialParityAssertions.compare(snapshotA, snapshotB)
            assertTrue("Fresh independent database schemas should have equal authoritative rows", parity.passed)

            val engine = FinancialCertificationExecutionEngine(MissingDriver(missingIntegration), "blocked-schema-probe", "instrumentation")
            val result = engine.executeAll()
            assertEquals(13, result.operations.size)
            assertFalse("No operation may be marked PASS without live execution", result.passed)
            assertTrue(result.operations.all { it.status == FinancialCertificationExecutionEngine.Status.BLOCKED })
            assertTrue(result.operations.all { it.failure == missingIntegration })

            val output = File(requireNotNull(context.getExternalFilesDir(null)), "financial-certification-evidence")
            engine.writeEvidenceBundle(output, result)
            val manifest = JSONObject(File(output, "manifest.json").readText())
            assertEquals(13, manifest.getInt("operation_count"))
            assertEquals("BLOCKED_OR_FAIL", manifest.getString("overall_status"))
            result.operations.forEach { operation ->
                val operationDir = File(output, operation.operationType)
                listOf("operation.json", "outbox.json", "envelope.json", "digest-check.json",
                    "download.json", "before.json", "after.json", "replay.json",
                    "reports.json", "assertions.json").forEach { name ->
                    assertTrue("Evidence file missing: " + operation.operationType + "/" + name,
                        File(operationDir, name).isFile)
                }
            }
        } finally {
            databaseA.close()
            databaseB.close()
        }
    }



    @Test
    fun realExpenseCommandExecutesOnSenderAndDoesNotAppearOnIndependentReceiver() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sender = DmrDatabase.createInMemory(context)
        val receiver = DmrDatabase.createInMemory(context)
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
            sender.businessBranchDao().insertBusiness(business)
            sender.businessBranchDao().insertBranch(branch)
            sender.userDao().insertUser(user)
            sender.userDao().insertMembership(membership)
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
            val repository = RegisterRepository(sender, CommandExecutor(sender))
            val result = repository.executeExpense(command)
            assertTrue("Real repository command failed: $result", result is CommandResult.Success)
            val operation = sender.operationDao().getOperationById(command.operationId.value)
            assertTrue("The command must persist a successful authoritative operation", operation != null)
            assertEquals(OperationStatus.SUCCEEDED, operation!!.status)
            val expense = sender.expenseDao().getExpenseByOperationId(command.operationId.value)
            assertTrue("The real command path must persist the expense", expense != null)
            assertEquals(1250L, expense!!.amountMinor)
            assertEquals(
                "An independent receiver must not contain sender data before sync/materialization",
                null, receiver.operationDao().getOperationById(command.operationId.value)
            )
            assertEquals(
                "An independent receiver must not contain the sender expense before materialization",
                null, receiver.expenseDao().getExpenseByOperationId(command.operationId.value)
            )
        } finally {
            SessionManager.clearSession()
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun parityAssertionReportsExactFinancialFieldMismatch() {
        fun snapshot(amount: Long): String {
            val row = JSONObject().put("id", "op-1").put("amount_minor", amount)
            val table = JSONObject().put("primary_key", "id").put("row_count", 1)
                .put("rows", JSONArray().put(row))
            return FinancialSnapshotCollector.canonical(JSONObject().put("tables", JSONObject().put("operations", table)))
        }
        val result = FinancialParityAssertions.compare(snapshot(1250), snapshot(1240))
        assertFalse(result.passed)
        assertTrue(result.mismatches.any {
            it.table == "operations" && it.primaryId == "op-1" &&
                it.field == "amount_minor" && it.senderValue == "1250" && it.receiverValue == "1240"
        })
    }

    @Test
    fun reportOracleRejectsUnbalancedSaleFunding() {
        val balanced = FinancialReportOracle.calculate(listOf(
            FinancialReportOracle.Entry(operationType = "RECORD_SALE", amountMinor = 1250, fundingMinor = 1250)
        ))
        val unbalanced = FinancialReportOracle.calculate(listOf(
            FinancialReportOracle.Entry(operationType = "RECORD_SALE", amountMinor = 1250, fundingMinor = 1249)
        ))
        assertTrue(balanced.passed)
        assertFalse(unbalanced.passed)
        assertTrue(unbalanced.assertions.any { it.name.endsWith("sale-funding-conservation") && !it.passed })
    }

    private class MissingDriver(private val reason: String) : FinancialCertificationExecutionEngine.Driver {
        override suspend fun createOnSender(operationType: String): FinancialCertificationExecutionEngine.OperationContext {
            throw FinancialCertificationExecutionEngine.BlockedDependency(reason)
        }
        override suspend fun verifyOutbox(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = error("unreachable")
        override suspend fun uploadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = error("unreachable")
        override suspend fun verifyEnvelopeAndDigest(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject = error("unreachable")
        override suspend fun downloadThroughNormalSync(context: FinancialCertificationExecutionEngine.OperationContext, envelope: JSONObject): JSONObject = error("unreachable")
        override suspend fun snapshotSender(context: FinancialCertificationExecutionEngine.OperationContext): String = error("unreachable")
        override suspend fun materializeOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject = error("unreachable")
        override suspend fun snapshotReceiver(context: FinancialCertificationExecutionEngine.OperationContext): String = error("unreachable")
        override suspend fun replayOnReceiver(context: FinancialCertificationExecutionEngine.OperationContext, downloadedEnvelope: JSONObject): JSONObject = error("unreachable")
        override suspend fun snapshotReceiverAfterReplay(context: FinancialCertificationExecutionEngine.OperationContext): String = error("unreachable")
        override suspend fun expectedFixture(context: FinancialCertificationExecutionEngine.OperationContext): List<FinancialReportOracle.Entry> = error("unreachable")
        override suspend fun actualReportsFromApp(context: FinancialCertificationExecutionEngine.OperationContext): JSONObject = error("unreachable")
    }
}
