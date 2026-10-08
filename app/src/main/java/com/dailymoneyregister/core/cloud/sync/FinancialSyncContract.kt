package com.dailymoneyregister.core.cloud.sync

private val DIGEST_HEX = Regex("^[0-9a-f]{64}$")
private val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
/* Existing tenant, membership and device IDs are opaque, non-whitespace
 * server/local identifiers. Do not impose UUID on legacy IDs. */
private val OPAQUE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

private fun validId(value: String, field: String, format: Regex) {
    require(value.length <= 128 && format.matches(value)) { "$field has invalid format" }
}

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
        // Financial OperationEntity specifies UUIDv7 IDs; no human-readable
        // aliases are permitted on the synchronization wire.
        validId(operationId, "operationId", UUID_V7)
        validId(businessId, "businessId", OPAQUE_ID)
        validId(branchId, "branchId", OPAQUE_ID)
        validId(actorMembershipId, "actorMembershipId", OPAQUE_ID)
        validId(deviceId, "deviceId", OPAQUE_ID)
        require(commandSchemaVersion > 0) { "commandSchemaVersion must be positive" }
        require(DIGEST_HEX.matches(canonicalSha256)) { "canonicalSha256 has invalid format" }
    }
}

enum class RetryReason { NETWORK, SERVER_UNAVAILABLE, DEPENDENCY_NOT_READY, RATE_LIMITED }
enum class RejectReason { INVALID_EVIDENCE, WRONG_TENANT, UNSUPPORTED_SCHEMA, FORBIDDEN_OPERATION }

/** Every response is correlated to a specific operation. */
sealed interface FinancialSyncReceipt {
    val operationId: String

    data class Acknowledged(
        override val operationId: String,
        val canonicalSha256: String,
        val duplicate: Boolean = false
    ) : FinancialSyncReceipt

    data class Retryable(
        override val operationId: String,
        val reason: RetryReason
    ) : FinancialSyncReceipt

    data class Rejected(
        override val operationId: String,
        val reason: RejectReason
    ) : FinancialSyncReceipt

    /** Same operation identity but conflicting digest: never retry automatically. */
    data class IntegrityConflict(override val operationId: String) : FinancialSyncReceipt

    /** Auth/session renewal, not a transient backoff state. */
    data class Unauthorized(override val operationId: String) : FinancialSyncReceipt
}

/** Only matching accepted evidence may mark a durable outbox entry delivered. */
object FinancialSyncAckGuard {
    fun isMatchingAck(expected: FinancialSyncIdentity, receipt: FinancialSyncReceipt): Boolean {
        if (receipt.operationId != expected.operationId) return false
        return receipt is FinancialSyncReceipt.Acknowledged &&
            receipt.canonicalSha256 == expected.canonicalSha256
    }
}
