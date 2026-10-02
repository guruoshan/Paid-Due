package com.dailymoneyregister.feature.customers.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerWhatsAppMessageComposerTest {

    private val party =
        CustomerWhatsAppMessageComposer.Party(
            businessName = "Test Shop",
            customerName = "Test Customer",
            customerPhone = "+96890000000"
        )

    @Test
    fun creditBalance_containsAuthoritativeValuesAndFooter() {
        val message =
            CustomerWhatsAppMessageComposer.creditBalance(
                party = party,
                formattedAmount = "OMR 12.500",
                messageDateTime = "01 Oct 2026 11:30 AM"
            )

        assertTrue(message.contains("Credit Balance"))
        assertTrue(message.contains("OMR 12.500"))
        assertCommon(message)
    }

    @Test
    fun creditBalance_allowsZero() {
        val message =
            CustomerWhatsAppMessageComposer.creditBalance(
                party = party,
                formattedAmount = "OMR 0.000",
                messageDateTime = "01 Oct 2026 11:30 AM"
            )

        assertTrue(message.contains("OMR 0.000"))
        assertCommon(message)
    }

    @Test
    fun paymentReminder_requiresPositiveOutstanding() {
        assertThrows(IllegalArgumentException::class.java) {
            CustomerWhatsAppMessageComposer.paymentReminder(
                party = party,
                outstandingMinor = 0L,
                formattedAmount = "OMR 0.000",
                messageDateTime = "01 Oct 2026 11:30 AM"
            )
        }
    }

    @Test
    fun paymentReminder_containsOutstandingAmount() {
        val message =
            CustomerWhatsAppMessageComposer.paymentReminder(
                party = party,
                outstandingMinor = 12500L,
                formattedAmount = "OMR 12.500",
                messageDateTime = "01 Oct 2026 11:30 AM"
            )

        assertTrue(message.contains("Payment Reminder"))
        assertTrue(message.contains("OMR 12.500"))
        assertFalse(message.contains("overdue", ignoreCase = true))
        assertFalse(message.contains("past due", ignoreCase = true))
        assertCommon(message)
    }

    @Test
    fun couponBalance_containsAuthoritativeBalance() {
        val message =
            CustomerWhatsAppMessageComposer.couponBalance(
                party = party,
                formattedBalance = "OMR 8.250",
                messageDateTime = "01 Oct 2026 11:30 AM"
            )

        assertTrue(message.contains("Coupon Balance: OMR 8.250"))
        assertCommon(message)
    }

    @Test
    fun couponPurchaseTopUp_containsEventAndBalance() {
        val message =
            CustomerWhatsAppMessageComposer.couponPurchaseOrTopUp(
                party = party,
                formattedEventAmount = "OMR 5.000",
                formattedCurrentBalance = "OMR 8.250",
                transactionDateTime = "30 Sep 2026 08:15 PM",
                messageDateTime = "01 Oct 2026 11:30 AM"
            )

        assertTrue(message.contains("Coupon Purchase / Top-up"))
        assertTrue(message.contains("Coupon value added: OMR 5.000"))
        assertTrue(message.contains("Current Coupon Balance: OMR 8.250"))
        assertTrue(
            message.contains(
                "Transaction Date & Time: 30 Sep 2026 08:15 PM"
            )
        )

        assertCommon(message)
    }

    private fun assertCommon(message: String) {
        assertTrue(message.contains("Test Shop"))
        assertTrue(message.contains("Test Customer"))
        assertTrue(message.contains("+96890000000"))
        assertTrue(message.contains("01 Oct 2026 11:30 AM"))

        val lines =
            message
                .trimEnd()
                .lines()

        assertEquals(
            "Powered by Paid&Due",
            lines.last()
        )

        assertTrue(
            message
                .trimEnd()
                .endsWith("Powered by Paid&Due")
        )
    }
}
