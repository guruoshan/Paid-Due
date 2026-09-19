package com.dailymoneyregister.core.command

import com.dailymoneyregister.core.model.enums.OperationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationStateMachineTest {

    @Test
    fun testAllowedStateTransitions() {
        // null -> PENDING
        assertTrue(OperationStateMachine.isTransitionAllowed(null, OperationStatus.PENDING))

        // PENDING -> SUCCEEDED
        assertTrue(OperationStateMachine.isTransitionAllowed(OperationStatus.PENDING, OperationStatus.SUCCEEDED))

        // PENDING -> FAILED
        assertTrue(OperationStateMachine.isTransitionAllowed(OperationStatus.PENDING, OperationStatus.FAILED))

        // PENDING -> REQUIRES_RECONCILIATION
        assertTrue(OperationStateMachine.isTransitionAllowed(OperationStatus.PENDING, OperationStatus.REQUIRES_RECONCILIATION))

        // REQUIRES_RECONCILIATION -> SUCCEEDED
        assertTrue(OperationStateMachine.isTransitionAllowed(OperationStatus.REQUIRES_RECONCILIATION, OperationStatus.SUCCEEDED))
    }

    @Test
    fun testForbiddenStateTransitions() {
        // SUCCEEDED -> anything is strictly forbidden
        assertFalse(OperationStateMachine.isTransitionAllowed(OperationStatus.SUCCEEDED, OperationStatus.PENDING))
        assertFalse(OperationStateMachine.isTransitionAllowed(OperationStatus.SUCCEEDED, OperationStatus.FAILED))
        assertFalse(OperationStateMachine.isTransitionAllowed(OperationStatus.SUCCEEDED, OperationStatus.SUCCEEDED))

        // FAILED -> SUCCEEDED is forbidden (must use a new Operation ID)
        assertFalse(OperationStateMachine.isTransitionAllowed(OperationStatus.FAILED, OperationStatus.SUCCEEDED))

        assertThrows(IllegalOperationStateTransitionException::class.java) {
            OperationStateMachine.assertTransitionAllowed(OperationStatus.SUCCEEDED, OperationStatus.FAILED)
        }
    }
}
