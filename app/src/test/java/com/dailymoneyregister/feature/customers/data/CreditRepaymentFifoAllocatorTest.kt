package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.ids.OperationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CreditRepaymentFifoAllocatorTest {

    @Test
    fun repaymentAllocatesOldestObligationFirst() {

        val result =
            CreditRepaymentFifoAllocator.allocate(
                repaymentAmountMinor = 7000L,
                obligations =
                    listOf(
                        obligation(
                            "newer",
                            occurredAt = 200L,
                            remainingMinor = 5000L
                        ),
                        obligation(
                            "older",
                            occurredAt = 100L,
                            remainingMinor = 5000L
                        )
                    )
            )

        assertEquals(
            listOf(
                CreditRepaymentFifoAllocator.Allocation(
                    "older",
                    5000L
                ),
                CreditRepaymentFifoAllocator.Allocation(
                    "newer",
                    2000L
                )
            ),
            result
        )
    }

    @Test
    fun partialRepaymentTouchesOnlyOldestEligibleSale() {

        val result =
            CreditRepaymentFifoAllocator.allocate(
                2000L,
                listOf(
                    obligation("sale-1", 100L, 5000L),
                    obligation("sale-2", 200L, 5000L)
                )
            )

        assertEquals(
            listOf(
                CreditRepaymentFifoAllocator.Allocation(
                    "sale-1",
                    2000L
                )
            ),
            result
        )
    }

    @Test
    fun exactRepaymentCanCloseMultipleObligations() {

        val result =
            CreditRepaymentFifoAllocator.allocate(
                8000L,
                listOf(
                    obligation("sale-1", 100L, 3000L),
                    obligation("sale-2", 200L, 5000L)
                )
            )

        assertEquals(2, result.size)
        assertEquals(3000L, result[0].amountMinor)
        assertEquals(5000L, result[1].amountMinor)
    }

    @Test
    fun overpaymentFailsClosed() {

        assertThrows(IllegalArgumentException::class.java) {
            CreditRepaymentFifoAllocator.allocate(
                6000L,
                listOf(
                    obligation("sale-1", 100L, 5000L)
                )
            )
        }
    }

    @Test
    fun zeroRepaymentFailsClosed() {

        assertThrows(IllegalArgumentException::class.java) {
            CreditRepaymentFifoAllocator.allocate(
                0L,
                listOf(
                    obligation("sale-1", 100L, 5000L)
                )
            )
        }
    }

    @Test
    fun equalTimestampsUseDeterministicTransactionOrdering() {

        val result =
            CreditRepaymentFifoAllocator.allocate(
                1500L,
                listOf(
                    obligation("sale-b", 100L, 1000L),
                    obligation("sale-a", 100L, 1000L)
                )
            )

        assertEquals("sale-a", result[0].transactionId)
        assertEquals(1000L, result[0].amountMinor)

        assertEquals("sale-b", result[1].transactionId)
        assertEquals(500L, result[1].amountMinor)
    }

    @Test
    fun cashCommandUsesAuthoritativeRepaymentOperation() {

        val command =
            CreditRepaymentCommand(
                operationId = OperationId("op-repayment-1"),
                businessId = "business",
                branchId = "branch",
                actorUserId = "cashier",
                repaymentId = "repayment-1",
                customerId = "customer-1",
                amountMinor = 5000L,
                currencyCode = "OMR",
                paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER,
                deviceId = "device",
                occurredAt = 123L
            )

        assertEquals(
            "repayment-1",
            command.subjectId
        )

        assertEquals(
            5000L,
            command.amountMinor
        )

        assertEquals(
            PaymentCategory.CASH,
            command.paymentCategory
        )
    }

    @Test
    fun creditCannotRepayCredit() {

        assertThrows(IllegalStateException::class.java) {
            CreditRepaymentCommand(
                operationId = OperationId("op"),
                businessId = "business",
                branchId = "branch",
                actorUserId = "cashier",
                repaymentId = "repayment",
                customerId = "customer",
                amountMinor = 1000L,
                currencyCode = "OMR",
                paymentCategory = PaymentCategory.CREDIT,
                paymentChannel = PaymentChannel.CREDIT_ACCOUNT,
                deviceId = "device",
                occurredAt = 123L
            )
        }
    }

    @Test
    fun couponRepaymentRequiresCouponWalletChannel() {

        assertThrows(IllegalArgumentException::class.java) {
            CreditRepaymentCommand(
                operationId = OperationId("op"),
                businessId = "business",
                branchId = "branch",
                actorUserId = "cashier",
                repaymentId = "repayment",
                customerId = "customer",
                amountMinor = 1000L,
                currencyCode = "OMR",
                paymentCategory = PaymentCategory.COUPON,
                paymentChannel = PaymentChannel.CASH_TENDER,
                deviceId = "device",
                occurredAt = 123L
            )
        }
    }

    private fun obligation(
        transactionId: String,
        occurredAt: Long,
        remainingMinor: Long
    ) =
        CreditRepaymentFifoAllocator.OutstandingObligation(
            transactionId = transactionId,
            occurredAt = occurredAt,
            remainingMinor = remainingMinor
        )
}
