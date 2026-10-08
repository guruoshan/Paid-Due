package com.dailymoneyregister.core.cloud.sync

/**
 * BOARDLOCK — FOUNDATION ONLY.
 *
 * Models delivery state, not financial accounting state.
 * Never updates a sale, settlement, or original operation.
 *
 * This is a pure policy. Durable Room persistence and network transport
 * must be implemented and tested separately.
 */
enum class FinancialSyncDeliveryState {
    QUEUED,
    IN_FLIGHT,
    WAITING_RETRY,
    AUTH_REQUIRED,
    INTEGRITY_CONFLICT,
    REJECTED,
    ACKNOWLEDGED
}

object FinancialSyncOutboxPolicy {

    fun beginUpload(
        state: FinancialSyncDeliveryState
    ): FinancialSyncDeliveryState {
        require(
            state == FinancialSyncDeliveryState.QUEUED ||
            state == FinancialSyncDeliveryState.WAITING_RETRY
        ) {
            "Upload cannot begin from this delivery state"
        }

        return FinancialSyncDeliveryState.IN_FLIGHT
    }

    fun applyReceipt(
        identity: FinancialSyncIdentity,
        state: FinancialSyncDeliveryState,
        receipt: FinancialSyncReceipt
    ): FinancialSyncDeliveryState {

        require(state == FinancialSyncDeliveryState.IN_FLIGHT) {
            "Receipt requires an in-flight upload"
        }

        require(receipt.operationId == identity.operationId) {
            "Receipt operationId does not match"
        }

        return when (receipt) {

            is FinancialSyncReceipt.Acknowledged -> {
                if (FinancialSyncAckGuard.isMatchingAck(identity, receipt)) {
                    FinancialSyncDeliveryState.ACKNOWLEDGED
                } else {
                    FinancialSyncDeliveryState.INTEGRITY_CONFLICT
                }
            }

            is FinancialSyncReceipt.Retryable ->
                FinancialSyncDeliveryState.WAITING_RETRY

            is FinancialSyncReceipt.Unauthorized ->
                FinancialSyncDeliveryState.AUTH_REQUIRED

            is FinancialSyncReceipt.IntegrityConflict ->
                FinancialSyncDeliveryState.INTEGRITY_CONFLICT

            is FinancialSyncReceipt.Rejected ->
                FinancialSyncDeliveryState.REJECTED
        }
    }
}