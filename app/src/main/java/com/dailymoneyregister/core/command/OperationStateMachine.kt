package com.dailymoneyregister.core.command

import com.dailymoneyregister.core.model.enums.OperationStatus

object OperationStateMachine {

    /**
     * Validates if a state transition from [from] to [to] is legally allowed.
     */
    fun isTransitionAllowed(from: OperationStatus?, to: OperationStatus): Boolean {
        if (from == null) {
            return to == OperationStatus.PENDING || to == OperationStatus.SUCCEEDED
        }
        return when (from) {
            OperationStatus.PENDING -> when (to) {
                OperationStatus.SUCCEEDED,
                OperationStatus.FAILED,
                OperationStatus.REQUIRES_RECONCILIATION -> true
                OperationStatus.PENDING -> false
            }
            OperationStatus.REQUIRES_RECONCILIATION -> when (to) {
                OperationStatus.SUCCEEDED,
                OperationStatus.FAILED -> true
                else -> false
            }
            OperationStatus.SUCCEEDED -> false // Succeeded operations are immutable!
            OperationStatus.FAILED -> false // Failed operations cannot be overwritten!
        }
    }

    fun assertTransitionAllowed(from: OperationStatus?, to: OperationStatus) {
        if (!isTransitionAllowed(from, to)) {
            throw IllegalOperationStateTransitionException(
                "Illegal operation state transition from ${from?.name ?: "NEW"} to ${to.name}. " +
                        "Once SUCCEEDED or FAILED, operations are strictly immutable."
            )
        }
    }
}

class IllegalOperationStateTransitionException(message: String) : IllegalStateException(message)
