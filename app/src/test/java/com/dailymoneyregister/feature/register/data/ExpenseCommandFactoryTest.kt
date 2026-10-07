package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpenseCommandFactoryTest {

    @Test
    fun cashExpenseUsesAuthoritativeCashTenderChannel() {
        val command =
            create(
                paymentCategory = PaymentCategory.CASH
            )

        assertEquals(
            PaymentChannel.CASH_TENDER,
            command.paymentChannel
        )
    }

    @Test
    fun onlineExpenseUsesDefaultManualQrChannel() {
        val command =
            create(
                paymentCategory = PaymentCategory.ONLINE
            )

        assertEquals(
            PaymentChannel.MANUAL_QR,
            command.paymentChannel
        )
    }

    @Test
    fun unsupportedExpenseFundingFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            create(
                paymentCategory = PaymentCategory.CREDIT
            )
        }

        assertThrows(IllegalStateException::class.java) {
            create(
                paymentCategory = PaymentCategory.COUPON
            )
        }
    }

    @Test
    fun factoryNormalizesOptionalSnapshots() {
        val command =
            ExpenseCommandFactory.createNew(
                businessId = "business",
                branchId = "branch",
                actorUserId = "user",
                amountMinor = 1_250L,
                currencyCode = "OMR",
                category = ExpenseCategory.SUPPLIES,
                paymentCategory = PaymentCategory.CASH,
                payeeName = "  Supplier  ",
                payeePhone = "  +96890000000  ",
                reference = "  REF-1  ",
                note = "  note  ",
                deviceId = "device",
                occurredAt = 123L
            )

        assertEquals("Supplier", command.payeeNameSnapshot)
        assertEquals("+96890000000", command.payeePhoneSnapshot)
        assertEquals("REF-1", command.reference)
        assertEquals("note", command.note)
    }

    private fun create(
        paymentCategory: PaymentCategory
    ): ExpenseCommand =
        ExpenseCommandFactory.createNew(
            businessId = "business",
            branchId = "branch",
            actorUserId = "user",
            amountMinor = 1_000L,
            currencyCode = "OMR",
            category = ExpenseCategory.OTHER,
            paymentCategory = paymentCategory,
            payeeName = "",
            payeePhone = null,
            reference = "",
            note = "",
            deviceId = "device",
            occurredAt = 123L
        )
}
