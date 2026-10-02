package com.dailymoneyregister

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeCommand
import com.dailymoneyregister.feature.register.data.PaymentMethodChangeFinancialPrecondition
import com.dailymoneyregister.feature.register.data.PaymentMethodChangePayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentMethodChangeCommandTest {

    @Test
    fun commandCarriesDurableTransactionIdentity() {
        val command =
            newCommand(
                newCategory = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.MANUAL_QR
            )

        assertEquals(
            OperationType.RECORD_PAYMENT_METHOD_CHANGE,
            command.operationType
        )
        assertEquals(
            SubjectType.TRANSACTION,
            command.subjectType
        )
        assertEquals(
            command.subjectId,
            command.transactionId
        )
        assertEquals(
            PaymentMethodChangePayloadCodec.CURRENT_SCHEMA_VERSION,
            command.commandSchemaVersion
        )
    }

    @Test
    fun cashDestinationRequiresCashTender() {
        newCommand(
            newCategory = PaymentCategory.CASH,
            newChannel = PaymentChannel.CASH_TENDER
        )

        expectIllegalArgument {
            newCommand(
                newCategory = PaymentCategory.CASH,
                newChannel = PaymentChannel.MANUAL_QR
            )
        }
    }

    @Test
    fun onlineDestinationAllowsManualQrAndSoftPosOnly() {
        newCommand(
            newCategory = PaymentCategory.ONLINE,
            newChannel = PaymentChannel.MANUAL_QR
        )

        newCommand(
            newCategory = PaymentCategory.ONLINE,
            newChannel = PaymentChannel.SOFTPOS
        )

        expectIllegalArgument {
            newCommand(
                newCategory = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.CASH_TENDER
            )
        }
    }

    @Test
    fun creditAndCouponDestinationsFailClosed() {
        expectIllegalArgument {
            newCommand(
                newCategory = PaymentCategory.CREDIT,
                newChannel = PaymentChannel.CASH_TENDER
            )
        }

        expectIllegalArgument {
            newCommand(
                newCategory = PaymentCategory.COUPON,
                newChannel = PaymentChannel.CASH_TENDER
            )
        }
    }

    @Test
    fun financialPreconditionRequiresExactSupportedCurrentIdentityAndPositiveAmount() {
        expectIllegalArgument {
            precondition(
                fingerprint = "not-a-fingerprint"
            )
        }

        expectIllegalArgument {
            precondition(
                amountMinor = 0L
            )
        }

        expectIllegalArgument {
            precondition(
                category = PaymentCategory.CASH,
                channel = PaymentChannel.MANUAL_QR
            )
        }
    }

    @Test
    fun admittedCommandRejectsNoOpIdentity() {
        val current =
            precondition(
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER
            )

        expectIllegalArgument {
            newCommand(
                newCategory = PaymentCategory.CASH,
                newChannel = PaymentChannel.CASH_TENDER,
                financialPrecondition = current
            )
        }
    }

    @Test
    fun payloadRoundTripsExactDurableIntent() {
        val current =
            precondition(
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountMinor = 7_500L
            )

        val command =
            newCommand(
                newCategory = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.SOFTPOS,
                reason = "Cash selected by mistake",
                financialPrecondition = current
            )

        val decoded =
            PaymentMethodChangePayloadCodec.decode(
                command.intentPayload
            )

        assertEquals(1, decoded.schemaVersion)
        assertEquals(
            PaymentCategory.ONLINE,
            decoded.newCategory
        )
        assertEquals(
            PaymentChannel.SOFTPOS,
            decoded.newChannel
        )
        assertEquals(
            "Cash selected by mistake",
            decoded.reason
        )
        assertEquals(
            current,
            decoded.financialPrecondition
        )
    }

    @Test
    fun requestFingerprintStaysStableWhenFinancialPreconditionIsAdmitted() {
        val requested =
            newCommand(
                newCategory = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.MANUAL_QR
            )

        val admitted =
            requested.copy(
                financialPrecondition =
                    precondition(
                        category = PaymentCategory.CASH,
                        channel = PaymentChannel.CASH_TENDER,
                        amountMinor = 9_000L
                    )
            )

        assertEquals(
            requested.requestFingerprint,
            admitted.requestFingerprint
        )

        assertNotEquals(
            requested.intentPayload,
            admitted.intentPayload
        )
    }

    @Test
    fun payloadRejectsIncompleteOrUnknownFinancialIntent() {
        expectIllegalArgument {
            PaymentMethodChangePayloadCodec.decode(
                "v1|newCategory:ONLINE|newChannel:MANUAL_QR|" +
                    "reason:|state:${"a".repeat(64)}|" +
                    "currentCategory:CASH|currentChannel:|amount:5000"
            )
        }

        expectIllegalArgument {
            PaymentMethodChangePayloadCodec.decode(
                "v1|newCategory:ONLINE|newChannel:MANUAL_QR|" +
                    "reason:|state:|currentCategory:|currentChannel:|" +
                    "amount:|unexpected:value"
            )
        }
    }

    @Test
    fun payloadWithoutAdmissionPreconditionRemainsAValidUserRequest() {
        val command =
            newCommand(
                newCategory = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.MANUAL_QR,
                financialPrecondition = null
            )

        val decoded =
            PaymentMethodChangePayloadCodec.decode(
                command.intentPayload
            )

        assertNull(decoded.financialPrecondition)
        assertTrue(command.requestFingerprint.isNotBlank())
    }

    private fun newCommand(
        newCategory: PaymentCategory,
        newChannel: PaymentChannel,
        reason: String? = null,
        financialPrecondition: PaymentMethodChangeFinancialPrecondition? = null
    ): PaymentMethodChangeCommand =
        PaymentMethodChangeCommand(
            operationId =
                OperationId("payment-change-command-test-operation"),
            businessId = "business-1",
            branchId = "branch-1",
            subjectId = "transaction-1",
            actorUserId = "user-1",
            deviceId = "device-1",
            occurredAt = 1_700_000_000_000L,
            newCategory = newCategory,
            newChannel = newChannel,
            reason = reason,
            financialPrecondition = financialPrecondition
        )

    private fun precondition(
        fingerprint: String = "a".repeat(64),
        category: PaymentCategory = PaymentCategory.CASH,
        channel: PaymentChannel = PaymentChannel.CASH_TENDER,
        amountMinor: Long = 5_000L
    ): PaymentMethodChangeFinancialPrecondition =
        PaymentMethodChangeFinancialPrecondition(
            stateFingerprint = fingerprint,
            expectedCurrentCategory = category,
            expectedCurrentChannel = channel,
            expectedReclassificationAmountMinor = amountMinor
        )

    private fun expectIllegalArgument(
        block: () -> Unit
    ) {
        var thrown = false

        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }

        assertTrue(
            "Expected IllegalArgumentException",
            thrown
        )
    }
}
