package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class CreditReturnUiRoutingTest {

    @Test
    fun cashSale_keepsNormalRefundRoute() {
        assertEquals(
            CreditReturnUiRoute.NON_CREDIT,
            creditReturnUiRoute(
                PaymentCategory.CASH,
                PaymentChannel.CASH_TENDER,
                0
            )
        )
    }

    @Test
    fun onlineSale_keepsNormalRefundRoute() {
        assertEquals(
            CreditReturnUiRoute.NON_CREDIT,
            creditReturnUiRoute(
                PaymentCategory.ONLINE,
                PaymentChannel.MANUAL_QR,
                0
            )
        )
    }

    @Test
    fun couponSale_keepsNormalRefundRoute() {
        assertEquals(
            CreditReturnUiRoute.NON_CREDIT,
            creditReturnUiRoute(
                PaymentCategory.COUPON,
                PaymentChannel.COUPON_WALLET,
                0
            )
        )
    }

    @Test
    fun pureUnpaidCredit_routesToDebtReduction() {
        assertEquals(
            CreditReturnUiRoute.UNPAID_CREDIT,
            creditReturnUiRoute(
                PaymentCategory.CREDIT,
                PaymentChannel.CREDIT_ACCOUNT,
                0
            )
        )
    }

    @Test
    fun creditWithRepaymentAllocation_routesToAuthoritativeMixedReturn() {
        assertEquals(
            CreditReturnUiRoute.REPAID_CREDIT,
            creditReturnUiRoute(
                PaymentCategory.CREDIT,
                PaymentChannel.CREDIT_ACCOUNT,
                1
            )
        )
    }

    @Test
    fun malformedCreditFunding_failsClosed() {
        assertEquals(
            CreditReturnUiRoute.INVALID_CREDIT,
            creditReturnUiRoute(
                PaymentCategory.CREDIT,
                PaymentChannel.CASH_TENDER,
                0
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeAllocationCount_isRejected() {
        creditReturnUiRoute(
            PaymentCategory.CREDIT,
            PaymentChannel.CREDIT_ACCOUNT,
            -1
        )
    }
}
