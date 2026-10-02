package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditReturnPlanTest {

    @Test
    fun unpaidCredit_returnThree_reducesCreditOnly() {
        val plan =
            plan(
                original = 10_000L,
                requested = 3_000L
            )

        assertEquals(3_000L, plan.creditReductionMinor)
        assertEquals(0L, plan.paidValueRefundMinor)
        assertTrue(plan.paidValuePortions.isEmpty())
        assertEquals(10_000L, plan.unpaidCreditBeforeReturnMinor)
    }

    @Test
    fun fourCashPaid_returnThree_reducesCreditOnly() {
        val plan =
            plan(
                original = 10_000L,
                requested = 3_000L,
                repayments = listOf(
                    cash("r1", 4_000L, 1_000L)
                )
            )

        assertEquals(6_000L, plan.unpaidCreditBeforeReturnMinor)
        assertEquals(3_000L, plan.creditReductionMinor)
        assertEquals(0L, plan.paidValueRefundMinor)
    }

    @Test
    fun fourCashPaid_returnSix_reducesCreditOnly() {
        val plan =
            plan(
                original = 10_000L,
                requested = 6_000L,
                repayments = listOf(
                    cash("r1", 4_000L, 1_000L)
                )
            )

        assertEquals(6_000L, plan.creditReductionMinor)
        assertEquals(0L, plan.paidValueRefundMinor)
    }

    @Test
    fun fourCashPaid_returnEight_refundsTwoCash() {
        val plan =
            plan(
                original = 10_000L,
                requested = 8_000L,
                repayments = listOf(
                    cash("r1", 4_000L, 1_000L)
                )
            )

        assertEquals(6_000L, plan.creditReductionMinor)
        assertEquals(2_000L, plan.paidValueRefundMinor)
        assertEquals(1, plan.paidValuePortions.size)

        assertEquals(
            PaymentCategory.CASH,
            plan.paidValuePortions.single().paymentCategory
        )

        assertEquals(
            PaymentChannel.CASH_TENDER,
            plan.paidValuePortions.single().paymentChannel
        )

        assertEquals(
            2_000L,
            plan.paidValuePortions.single().amountMinor
        )
    }

    @Test
    fun fourCashPaid_fullReturn_refundsOnlyFourPaid() {
        val plan =
            plan(
                original = 10_000L,
                requested = 10_000L,
                repayments = listOf(
                    cash("r1", 4_000L, 1_000L)
                )
            )

        assertEquals(6_000L, plan.creditReductionMinor)
        assertEquals(4_000L, plan.paidValueRefundMinor)
        assertEquals(4_000L, plan.paidValuePortions.single().amountMinor)
    }

    @Test
    fun cashAndOnline_fullReturn_preservesBothFundingSources() {
        val plan =
            plan(
                original = 10_000L,
                requested = 10_000L,
                repayments = listOf(
                    cash("cash", 2_000L, 1_000L),
                    online("online", 2_000L, 2_000L)
                )
            )

        assertEquals(6_000L, plan.creditReductionMinor)
        assertEquals(4_000L, plan.paidValueRefundMinor)
        assertEquals(2, plan.paidValuePortions.size)

        assertEquals(
            PaymentCategory.CASH,
            plan.paidValuePortions[0].paymentCategory
        )

        assertEquals(
            2_000L,
            plan.paidValuePortions[0].amountMinor
        )

        assertEquals(
            PaymentCategory.ONLINE,
            plan.paidValuePortions[1].paymentCategory
        )

        assertEquals(
            2_000L,
            plan.paidValuePortions[1].amountMinor
        )
    }

    @Test
    fun repaymentOrdering_isDeterministicByTimeThenId() {
        val plan =
            plan(
                original = 10_000L,
                requested = 8_000L,
                repayments = listOf(
                    online("z", 2_000L, 2_000L),
                    cash("b", 1_000L, 1_000L),
                    cash("a", 1_000L, 1_000L)
                )
            )

        assertEquals(
            listOf("a", "b"),
            plan.paidValuePortions.map { it.repaymentId }
        )
    }

    @Test
    fun repaymentAllocatedElsewhere_onlyTransactionAllocationCounts() {
        val repayment =
            CreditReturnRepaymentEvidence(
                repaymentId = "shared",
                customerId = "customer",
                currencyCode = "OMR",
                repaymentAmountMinor = 5_000L,
                totalAllocatedAcrossCreditMinor = 5_000L,
                allocatedToTransactionMinor = 2_000L,
                paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER,
                repaymentOccurredAt = 1_000L
            )

        val plan =
            plan(
                original = 10_000L,
                requested = 10_000L,
                repayments = listOf(repayment)
            )

        assertEquals(2_000L, plan.allocatedRepaymentAmountMinor)
        assertEquals(8_000L, plan.creditReductionMinor)
        assertEquals(2_000L, plan.paidValueRefundMinor)
    }

    @Test
    fun unrelatedCustomerEvidence_isRejected() {
        val bad =
            cash("r1", 4_000L, 1_000L)
                .copy(customerId = "different-customer")

        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 8_000L,
                repayments = listOf(bad)
            )
        }
    }

    @Test
    fun overReturn_isRejected() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 10_001L
            )
        }
    }

    @Test
    fun zeroReturn_isRejected() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 0L
            )
        }
    }

    @Test
    fun negativeReturn_isRejected() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = -1L
            )
        }
    }

    @Test
    fun duplicateRepaymentEvidence_isRejected() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 8_000L,
                repayments = listOf(
                    cash("same", 2_000L, 1_000L),
                    cash("same", 2_000L, 2_000L)
                )
            )
        }
    }

    @Test
    fun allocationGreaterThanRepayment_isRejected() {
        val bad =
            CreditReturnRepaymentEvidence(
                repaymentId = "bad",
                customerId = "customer",
                currencyCode = "OMR",
                repaymentAmountMinor = 2_000L,
                totalAllocatedAcrossCreditMinor = 2_001L,
                allocatedToTransactionMinor = 2_001L,
                paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER,
                repaymentOccurredAt = 1_000L
            )

        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                repayments = listOf(bad)
            )
        }
    }

    @Test
    fun customerMismatch_isRejected() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                repayments = listOf(
                    cash("r1", 2_000L, 1_000L)
                        .copy(customerId = "wrong")
                )
            )
        }
    }

    @Test
    fun currencyMismatch_isRejected() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                repayments = listOf(
                    cash("r1", 2_000L, 1_000L)
                        .copy(currencyCode = "USD")
                )
            )
        }
    }

    @Test
    fun invalidCashChannel_isRejected() {
        val bad =
            cash("r1", 2_000L, 1_000L)
                .copy(
                    paymentChannel =
                        PaymentChannel.MANUAL_QR
                )

        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                repayments = listOf(bad)
            )
        }
    }

    @Test
    fun creditAsRepaymentFunding_isRejected() {
        val bad =
            cash("r1", 2_000L, 1_000L)
                .copy(
                    paymentCategory =
                        PaymentCategory.CREDIT,
                    paymentChannel =
                        PaymentChannel.CREDIT_ACCOUNT
                )

        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                repayments = listOf(bad)
            )
        }
    }

    @Test
    fun priorB1Return_isAccountedBeforeLaterRepayment() {
        /*
         * Original Credit 10.
         * Earlier B1 Credit return = 3.
         * Remaining obligation = 7.
         * Customer later repays 4.
         * Unpaid attributable Credit = 3.
         * Another return of 5:
         *   3 Credit reduction
         *   2 paid-value refund.
         */
        val plan =
            plan(
                original = 10_000L,
                requested = 5_000L,
                alreadyReturned = 3_000L,
                priorCreditReduction = 3_000L,
                priorPaidRefund = 0L,
                repayments = listOf(
                    cash("r1", 4_000L, 2_000L)
                )
            )

        assertEquals(3_000L, plan.unpaidCreditBeforeReturnMinor)
        assertEquals(3_000L, plan.creditReductionMinor)
        assertEquals(2_000L, plan.paidValueRefundMinor)
    }

    @Test
    fun historicalPaidRefund_consumesSameFundingDeterministically() {
        /*
         * Original 10, repaid 4:
         * 2 Cash then 2 Online.
         *
         * Earlier return:
         *   6 Credit reduction + 1 Cash refund = 7.
         *
         * Remaining returnable = 3.
         * Another full remaining return:
         *   Credit reduction 0
         *   paid value 3
         *   remaining Cash 1 + Online 2.
         */
        val plan =
            plan(
                original = 10_000L,
                requested = 3_000L,
                alreadyReturned = 7_000L,
                priorCreditReduction = 6_000L,
                priorPaidRefund = 1_000L,
                repayments = listOf(
                    cash("cash", 2_000L, 1_000L),
                    online("online", 2_000L, 2_000L)
                )
            )

        assertEquals(0L, plan.unpaidCreditBeforeReturnMinor)
        assertEquals(0L, plan.creditReductionMinor)
        assertEquals(3_000L, plan.paidValueRefundMinor)

        assertEquals(
            listOf(1_000L, 2_000L),
            plan.paidValuePortions.map { it.amountMinor }
        )

        assertEquals(
            listOf(
                PaymentCategory.CASH,
                PaymentCategory.ONLINE
            ),
            plan.paidValuePortions.map { it.paymentCategory }
        )
    }

    @Test
    fun historicalReturnEvidenceMustReconcile() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                alreadyReturned = 3_000L,
                priorCreditReduction = 2_000L,
                priorPaidRefund = 0L
            )
        }
    }

    @Test
    fun repaymentCannotExceedObligationAfterPriorCreditReturn() {
        assertThrows(CreditReturnPlanException::class.java) {
            plan(
                original = 10_000L,
                requested = 1_000L,
                alreadyReturned = 7_000L,
                priorCreditReduction = 7_000L,
                repayments = listOf(
                    cash("r1", 4_000L, 2_000L)
                )
            )
        }
    }

    @Test
    fun couponRepayment_preservesCouponFundingIdentity() {
        val plan =
            plan(
                original = 10_000L,
                requested = 8_000L,
                repayments = listOf(
                    coupon("coupon", 4_000L, 1_000L)
                )
            )

        assertEquals(6_000L, plan.creditReductionMinor)
        assertEquals(2_000L, plan.paidValueRefundMinor)

        assertEquals(
            PaymentCategory.COUPON,
            plan.paidValuePortions.single().paymentCategory
        )

        assertEquals(
            PaymentChannel.COUPON_WALLET,
            plan.paidValuePortions.single().paymentChannel
        )
    }

    @Test
    fun onlineSoftPos_isPreserved() {
        val repayment =
            online("softpos", 4_000L, 1_000L)
                .copy(
                    paymentChannel =
                        PaymentChannel.SOFTPOS
                )

        val plan =
            plan(
                original = 10_000L,
                requested = 8_000L,
                repayments = listOf(repayment)
            )

        assertEquals(
            PaymentChannel.SOFTPOS,
            plan.paidValuePortions.single().paymentChannel
        )
    }

    @Test
    fun identicalEvidence_producesIdenticalPlan() {
        val repayments =
            listOf(
                cash("cash", 2_000L, 1_000L),
                online("online", 2_000L, 2_000L)
            )

        val first =
            plan(
                original = 10_000L,
                requested = 9_000L,
                repayments = repayments
            )

        val second =
            plan(
                original = 10_000L,
                requested = 9_000L,
                repayments = repayments.reversed()
            )

        assertEquals(first, second)
    }

    private fun plan(
        original: Long,
        requested: Long,
        alreadyReturned: Long = 0L,
        priorCreditReduction: Long = 0L,
        priorPaidRefund: Long = 0L,
        repayments: List<CreditReturnRepaymentEvidence> =
            emptyList()
    ): CreditReturnPlan =
        CreditReturnPlanner.plan(
            originalTransactionId = "transaction",
            customerId = "customer",
            currencyCode = "OMR",
            originalCreditAmountMinor = original,
            requestedReturnAmountMinor = requested,
            alreadyReturnedAmountMinor = alreadyReturned,
            priorCreditReductionMinor = priorCreditReduction,
            priorPaidValueRefundMinor = priorPaidRefund,
            repayments = repayments
        )

    private fun cash(
        id: String,
        amount: Long,
        occurredAt: Long
    ) =
        CreditReturnRepaymentEvidence(
            repaymentId = id,
            customerId = "customer",
            currencyCode = "OMR",
            repaymentAmountMinor = amount,
            totalAllocatedAcrossCreditMinor = amount,
            allocatedToTransactionMinor = amount,
            paymentCategory = PaymentCategory.CASH,
            paymentChannel = PaymentChannel.CASH_TENDER,
            repaymentOccurredAt = occurredAt
        )

    private fun online(
        id: String,
        amount: Long,
        occurredAt: Long
    ) =
        CreditReturnRepaymentEvidence(
            repaymentId = id,
            customerId = "customer",
            currencyCode = "OMR",
            repaymentAmountMinor = amount,
            totalAllocatedAcrossCreditMinor = amount,
            allocatedToTransactionMinor = amount,
            paymentCategory = PaymentCategory.ONLINE,
            paymentChannel = PaymentChannel.MANUAL_QR,
            repaymentOccurredAt = occurredAt
        )

    private fun coupon(
        id: String,
        amount: Long,
        occurredAt: Long
    ) =
        CreditReturnRepaymentEvidence(
            repaymentId = id,
            customerId = "customer",
            currencyCode = "OMR",
            repaymentAmountMinor = amount,
            totalAllocatedAcrossCreditMinor = amount,
            allocatedToTransactionMinor = amount,
            paymentCategory = PaymentCategory.COUPON,
            paymentChannel = PaymentChannel.COUPON_WALLET,
            repaymentOccurredAt = occurredAt
        )
}