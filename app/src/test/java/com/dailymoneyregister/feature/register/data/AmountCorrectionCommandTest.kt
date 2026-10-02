package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.ids.OperationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountCorrectionCommandTest {

    @Test
    fun requestFingerprintRemainsStableAfterFinancialAdmission() {

        val requested =
            AmountCorrectionCommand(
                operationId =
                    OperationId(
                        "amount-correction-operation-1"
                    ),
                businessId =
                    "business-1",
                branchId =
                    "branch-1",
                subjectId =
                    "transaction-1",
                actorUserId =
                    "actor-1",
                deviceId =
                    "device-1",
                occurredAt =
                    1000L,
                correctedAmountMinor =
                    12000L,
                reason =
                    null
            )

        val admitted =
            requested.copy(
                financialPrecondition =
                    AmountCorrectionFinancialPrecondition(
                        stateFingerprint =
                            "a".repeat(64),
                        expectedPreviousAmountMinor =
                            10000L,
                        expectedRefundedAmountMinor =
                            0L,
                        expectedCurrentCategory =
                            PaymentCategory.CASH,
                        expectedCurrentChannel =
                            PaymentChannel.CASH_TENDER,
                        expectedDeltaMinor =
                            2000L
                    )
            )

        assertEquals(
            requested.requestFingerprint,
            admitted.requestFingerprint
        )
    }

    @Test
    fun durablePayloadRoundTripsAdmittedFinancialExpectation() {

        val payload =
            AmountCorrectionPayloadCodec.encode(
                correctedAmountMinor =
                    12000L,
                reason =
                    null,
                financialPrecondition =
                    AmountCorrectionFinancialPrecondition(
                        stateFingerprint =
                            "b".repeat(64),
                        expectedPreviousAmountMinor =
                            10000L,
                        expectedRefundedAmountMinor =
                            1000L,
                        expectedCurrentCategory =
                            PaymentCategory.CASH,
                        expectedCurrentChannel =
                            PaymentChannel.CASH_TENDER,
                        expectedDeltaMinor =
                            2000L
                    )
            )

        val decoded =
            AmountCorrectionPayloadCodec.decode(
                payload
            )

        assertEquals(
            1,
            decoded.schemaVersion
        )

        assertEquals(
            12000L,
            decoded.correctedAmountMinor
        )

        assertNull(
            decoded.reason
        )

        val precondition =
            requireNotNull(
                decoded.financialPrecondition
            )

        assertEquals(
            "b".repeat(64),
            precondition.stateFingerprint
        )

        assertEquals(
            10000L,
            precondition.expectedPreviousAmountMinor
        )

        assertEquals(
            1000L,
            precondition.expectedRefundedAmountMinor
        )

        assertEquals(
            PaymentCategory.CASH,
            precondition.expectedCurrentCategory
        )

        assertEquals(
            PaymentChannel.CASH_TENDER,
            precondition.expectedCurrentChannel
        )

        assertEquals(
            2000L,
            precondition.expectedDeltaMinor
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun creditFundingIsFailClosed() {

        AmountCorrectionFinancialPrecondition(
            stateFingerprint =
                "c".repeat(64),
            expectedPreviousAmountMinor =
                10000L,
            expectedRefundedAmountMinor =
                0L,
            expectedCurrentCategory =
                PaymentCategory.CREDIT,
            expectedCurrentChannel =
                PaymentChannel.CREDIT_ACCOUNT,
            expectedDeltaMinor =
                1000L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun couponFundingIsFailClosed() {

        AmountCorrectionFinancialPrecondition(
            stateFingerprint =
                "d".repeat(64),
            expectedPreviousAmountMinor =
                10000L,
            expectedRefundedAmountMinor =
                0L,
            expectedCurrentCategory =
                PaymentCategory.COUPON,
            expectedCurrentChannel =
                PaymentChannel.COUPON_WALLET,
            expectedDeltaMinor =
                1000L
        )
    }
}