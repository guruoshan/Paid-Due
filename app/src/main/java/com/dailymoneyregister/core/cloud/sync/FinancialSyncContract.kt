package com.dailymoneyregister.core.cloud.sync

/**
 * BOARDLOCK: a transport-independent contract only.
 *
 * No financial rows are written, moved or synchronized by this file.
 * A later stage must bind verified committed Room evidence to these identities,
 * then authorize and atomically accept it on the server.
 */
data class FinancialSyncIdentity(
    val operationId: String,
    val businessId: String,
    val branchId: String,
    val actorMembershipId: String,
    val deviceId: String,
    val commandSchemaVersion: Int,
    val canonicalSha256: String
) {
    init {
        require(operationId.isNotBlank())
        require(businessId.isNotBlank())
        require(branchId.isNotBlank())
        require(actorMembershipId.isNotBlank())
        require(deviceId.isNotBlank())
        require(commandSchemaVersion > 0)
        require(Regex("^[0-9a-f]{64}$").matches(canonicalSha256))
    }
}

/**
 * These states describe delivery only, not whether a sale succeeded locally.
 * A server ack MUST match operationId and canonicalSha256.
 */
sealed interface FinancialSyncReceipt {
    data class Accepted(
        val operationId: String,
        val canonicalSha256: String
    ) : FinancialSyncReceipt

    data class AlreadyAccepted(
        val operationId: String,
        val canonicalSha256: String
    ) : FinancialSyncReceipt

    data class Retryable(val reasonCode: String) : FinancialSyncReceipt
    data class Rejected(val reasonCode: String) : FinancialSyncReceipt
}

/**
 * A pure gate to prevent a server ack for a different command clearing a local
 * outbox entry. The persistent outbox must also enforce this atomically.
 */
object FinancialSyncAckGuard {
    fun isMatchingAck(
        expected: FinancialSyncIdentity,
        receipt: FinancialSyncReceipt
    ): Boolean = when (receipt) {
        is FinancialSyncReceipt.Accepted ->
            receipt.operationId == expected.operationId &&
                receipt.canonicalSha256 == expected.canonicalSha256
        is FinancialSyncReceipt.AlreadyAccepted ->
            receipt.operationId == expected.operationId &&
                receipt.canonicalSha256 == expected.canonicalSha256
        is FinancialSyncReceipt.Retryable, is FinancialSyncReceipt.Rejected -> false
    }
}
