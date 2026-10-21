package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
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
