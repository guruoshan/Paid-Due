package com.dailymoneyregister.core.cloud.sync

import org.json.JSONObject

/**
 * Certification driver skeleton. Every stage intentionally fails closed until its real
 * application-path implementation is added in follow-up commits.
 */
class SupabaseCertificationDriver : FinancialCertificationExecutionEngine.Driver {
    private fun unavailable(): Nothing =
        throw FinancialCertificationExecutionEngine.BlockedDependency("not implemented yet")

    override suspend fun createOnSender(operationType: String): FinancialCertificationExecutionEngine.OperationContext = unavailable()
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
