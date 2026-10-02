package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CreditReturnB2CUiRouteTest {

    @Test
    fun nonCredit_isNonCredit() {
        assertEquals(
            CreditReturnUiRoute.NON_CREDIT,
            creditReturnUiRoute(
                paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER,
                creditRepaymentAllocationCount = 0
            )
        )
    }

    @Test
    fun unpaidCredit_isAllowed() {
        assertEquals(
            CreditReturnUiRoute.UNPAID_CREDIT,
            creditReturnUiRoute(
                paymentCategory = PaymentCategory.CREDIT,
                paymentChannel = PaymentChannel.CREDIT_ACCOUNT,
                creditRepaymentAllocationCount = 0
            )
        )
    }

    @Test
    fun repaidCredit_isAllowed() {
        assertEquals(
            CreditReturnUiRoute.REPAID_CREDIT,
            creditReturnUiRoute(
                paymentCategory = PaymentCategory.CREDIT,
                paymentChannel = PaymentChannel.CREDIT_ACCOUNT,
                creditRepaymentAllocationCount = 1
            )
        )
    }

    @Test
    fun multipleRepaymentAllocations_areAllowed() {
        assertEquals(
            CreditReturnUiRoute.REPAID_CREDIT,
            creditReturnUiRoute(
                paymentCategory = PaymentCategory.CREDIT,
                paymentChannel = PaymentChannel.CREDIT_ACCOUNT,
                creditRepaymentAllocationCount = 4
            )
        )
    }

    @Test
    fun malformedCreditChannel_failsClosed() {
        assertEquals(
            CreditReturnUiRoute.INVALID_CREDIT,
            creditReturnUiRoute(
                paymentCategory = PaymentCategory.CREDIT,
                paymentChannel = PaymentChannel.CASH_TENDER,
                creditRepaymentAllocationCount = 1
            )
        )
    }

    @Test
    fun negativeAllocationCount_isRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            creditReturnUiRoute(
                paymentCategory = PaymentCategory.CREDIT,
                paymentChannel = PaymentChannel.CREDIT_ACCOUNT,
                creditRepaymentAllocationCount = -1
            )
        }
    }
}
