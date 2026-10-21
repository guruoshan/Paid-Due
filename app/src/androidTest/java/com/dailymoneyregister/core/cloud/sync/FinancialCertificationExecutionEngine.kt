package com.dailymoneyregister.core.cloud.sync

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Executes the same certification lifecycle for each required operation. The driver must
 * call real application paths; the engine never manufactures envelopes or PASS statuses.
 */
class FinancialCertificationExecutionEngine(
    private val driver: Driver,
    private val runId: String,
    private val commitSha: String
) {
    enum class Status { PASS, FAIL, BLOCKED, NOT_RUN }
    data class Stage(val status: Status, val evidence: String = "{}", val detail: String? = null)
    data class OperationResult(
        val operationType: String,
        val status: Status,
        val operationId: String?,
        val stages: Map<String, Stage>,
        val assertions: List<String>,
        val failure: String? = null
    )
    data class RunResult(val runId: String, val commitSha: String, val operations: List<OperationResult>) {
        val passed: Boolean get() = operations.size == FinancialCertificationCaseRegistry.cases.size &&
            operations.all { it.status == Status.PASS }
    }

    data class OperationContext(val operationType: String, val operationId: String, val opaque: Any? = null)

    interface Driver {
        /** Create a synthetic operation using the real app command/repository path on database A. */
        suspend fun createOnSender(operationType: String): OperationContext
        suspend fun verifyOutbox(context: OperationContext): JSONObject
        suspend fun uploadThroughNormalSync(context: OperationContext): JSONObject
        suspend fun verifyEnvelopeAndDigest(context: OperationContext, envelope: JSONObject): JSONObject
        suspend fun downloadThroughNormalSync(context: OperationContext, envelope: JSONObject): JSONObject
        suspend fun snapshotSender(context: OperationContext): String
        suspend fun materializeOnReceiver(context: OperationContext, downloadedEnvelope: JSONObject): JSONObject
        suspend fun snapshotReceiver(context: OperationContext): String
        suspend fun replayOnReceiver(context: OperationContext, downloadedEnvelope: JSONObject): JSONObject
        suspend fun snapshotReceiverAfterReplay(context: OperationContext): String
        suspend fun expectedFixture(context: OperationContext): List<FinancialReportOracle.Entry>
        suspend fun actualReportsFromApp(context: OperationContext): JSONObject
    }

    class BlockedDependency(message: String) : IllegalStateException(message)

    suspend fun executeAll(): RunResult {
        val results = FinancialCertificationCaseRegistry.cases.map { case -> executeOne(case.operationType) }
        return RunResult(runId, commitSha, results)
    }

    private suspend fun executeOne(operationType: String): OperationResult {
        val stages = linkedMapOf<String, Stage>()
        val assertions = mutableListOf<String>()
        var context: OperationContext? = null
        fun blocked(name: String, message: String) {
            stages[name] = Stage(Status.BLOCKED, detail = message)
        }
        fun pass(name: String, json: JSONObject = JSONObject()) {
            stages[name] = Stage(Status.PASS, json.toString())
        }
        return try {
            val activeContext = driver.createOnSender(operationType)
            context = activeContext
            require(activeContext.operationType == operationType) {
                "Driver created " + activeContext.operationType + " while requested " + operationType
            }
            pass("create", JSONObject().put("operation_id", activeContext.operationId).put("operation_type", operationType))

            val outbox = driver.verifyOutbox(activeContext)
            require(outbox.optBoolean("present", false)) { "Outbox row missing for operation " + activeContext.operationId }
            pass("outbox", outbox)

            val envelope = driver.uploadThroughNormalSync(activeContext)
            require(envelope.optString("operation_id") == activeContext.operationId) {
                "Uploaded envelope operation ID does not match created operation"
            }
            pass("upload", envelope)

            val digest = driver.verifyEnvelopeAndDigest(activeContext, envelope)
            require(digest.optBoolean("verified", false)) { "Independent envelope digest/identity verification failed" }
            pass("digest-check", digest)

            val downloaded = driver.downloadThroughNormalSync(activeContext, envelope)
            require(downloaded.optBoolean("downloaded", false)) { "Envelope was not downloaded through normal sync" }
            pass("download", downloaded)

            val senderSnapshot = driver.snapshotSender(activeContext)
            val before = JSONObject()
                .put("sha256", sha256(senderSnapshot))
                .put("canonical_snapshot", JSONObject(senderSnapshot))
            pass("before", before)

            val materialize = driver.materializeOnReceiver(activeContext, downloaded)
            require(materialize.optString("result") == "Applied") {
                "First materialization did not apply exactly once: " + materialize.optString("result")
            }
            pass("materialize", materialize)

            val receiverSnapshot = driver.snapshotReceiver(activeContext)
            pass("after", JSONObject().put("sha256", sha256(receiverSnapshot))
                .put("canonical_snapshot", JSONObject(receiverSnapshot)))

            val parity = FinancialParityAssertions.compare(senderSnapshot, receiverSnapshot)
            if (!parity.passed) {
                parity.mismatches.forEach {
                    assertions += it.table + "[" + it.primaryId + "]." + it.field + ": " + it.assertion +
                        " sender=" + it.senderValue + " receiver=" + it.receiverValue
                }
            }
            require(parity.passed) { "Sender/receiver parity failed: " + assertions.joinToString("; ") }
            pass("parity", JSONObject().put("passed", true).put("mismatch_count", 0))

            val replay = driver.replayOnReceiver(activeContext, downloaded)
            require(replay.optString("result") == "Skipped") {
                "Replay was not idempotently skipped: " + replay.optString("result")
            }
            pass("replay", replay)

            val afterReplay = driver.snapshotReceiverAfterReplay(activeContext)
            require(receiverSnapshot == afterReplay) {
                "Replay changed the canonical receiver snapshot; see before/after hashes"
            }
            pass("replay-invariance", JSONObject().put("passed", true)
                .put("before_sha256", sha256(receiverSnapshot)).put("after_sha256", sha256(afterReplay)))

            val oracle = FinancialReportOracle.calculate(driver.expectedFixture(activeContext))
            require(oracle.passed) { "Independent conservation assertions failed: " +
                oracle.assertions.filterNot { it.passed }.joinToString { it.name + ": " + it.detail } }
            val actualReports = driver.actualReportsFromApp(activeContext)
            require(FinancialSnapshotCollector.canonical(actualReports) == FinancialSnapshotCollector.canonical(JSONObject(oracle.expectedReportsJson))) {
                "Actual app reports differ from independently calculated fixture oracle"
            }
            pass("reports", JSONObject().put("expected", JSONObject(oracle.expectedReportsJson))
                .put("actual", actualReports))
            pass("assertions", JSONObject().put("passed", true)
                .put("assertions", org.json.JSONArray(oracle.assertions.map { it.name + ": PASS" }))
                .put("parity", "PASS").put("replay_invariance", "PASS"))
            OperationResult(operationType, Status.PASS, context.operationId, stages, assertions)
        } catch (e: BlockedDependency) {
            blocked("blocked", e.message ?: "Required certification dependency is unavailable")
            OperationResult(operationType, Status.BLOCKED, context?.operationId, stages, assertions, e.message)
        } catch (e: Throwable) {
            stages["failure"] = Stage(Status.FAIL, detail = e.message ?: e.javaClass.simpleName)
            OperationResult(operationType, Status.FAIL, context?.operationId, stages, assertions, e.message)
        }
    }

    fun writeEvidenceBundle(root: File, result: RunResult) {
        require(root.mkdirs() || root.isDirectory) { "Cannot create evidence directory: " + root }
        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("run_id", result.runId)
            .put("commit_sha", result.commitSha)
            .put("operation_count", result.operations.size)
            .put("overall_status", if (result.passed) "PASS" else "BLOCKED_OR_FAIL")
            .put("created_at_epoch_ms", System.currentTimeMillis())
            .put("operations", org.json.JSONArray(result.operations.map {
                JSONObject().put("operation_type", it.operationType).put("operation_id", it.operationId)
                    .put("status", it.status.name)
            }))
        File(root, "manifest.json").writeText(FinancialSnapshotCollector.canonical(manifest))
        result.operations.forEach { operation ->
            val dir = File(root, operation.operationType)
            require(dir.mkdirs() || dir.isDirectory) { "Cannot create evidence directory: " + dir }
            val files = mapOf(
                "operation.json" to JSONObject().put("operation_type", operation.operationType)
                    .put("operation_id", operation.operationId).put("status", operation.status.name),
                "outbox.json" to stageJson(operation, "outbox"),
                "envelope.json" to stageJson(operation, "upload"),
                "digest-check.json" to stageJson(operation, "digest-check"),
                "download.json" to stageJson(operation, "download"),
                "before.json" to stageJson(operation, "before"),
                "after.json" to stageJson(operation, "after"),
                "replay.json" to JSONObject().put("replay", stageJson(operation, "replay"))
                    .put("invariance", stageJson(operation, "replay-invariance")),
                "reports.json" to stageJson(operation, "reports"),
                "assertions.json" to JSONObject().put("status", operation.status.name)
                    .put("assertions", org.json.JSONArray(operation.assertions))
                    .put("failure", operation.failure)
            )
            files.forEach { (name, json) -> File(dir, name).writeText(FinancialSnapshotCollector.canonical(json)) }
        }
    }

    private fun stageJson(operation: OperationResult, name: String): JSONObject {
        val stage = operation.stages[name] ?: return JSONObject()
            .put("status", Status.NOT_RUN.name).put("detail", "Stage did not execute")
        return JSONObject().put("status", stage.status.name).put("evidence", JSONObject(stage.evidence))
            .put("detail", stage.detail)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
