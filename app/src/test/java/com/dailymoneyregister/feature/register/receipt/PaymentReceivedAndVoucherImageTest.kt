package com.dailymoneyregister.feature.register.receipt

import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BOARDLOCK_UNIVERSAL_RECEIPT_IMAGE_V4 — Credit payment received + expense voucher. Pure text checks. */
class PaymentReceivedAndVoucherImageTest {

    private val party =
        UniversalWhatsAppMessageComposer.Party(
            businessName = "Test Shop",
            customerName = "Test Customer",
            customerPhone = "+96890000000"
        )

    private fun payment(pending: String? = "OMR 91.000") =
        UniversalWhatsAppMessageComposer.PaymentReceived(
            party = party,
            formattedAmount = "OMR 5.000",
            paymentMethod = "Cash",
            formattedCreditPending = pending,
            eventDateTime = "06 Oct 2026, 09:10 AM"
        )

    private fun expense() =
        UniversalWhatsAppMessageComposer.Expense(
            party = party,
            formattedAmount = "OMR 12.500",
            category = "Supplies",
            paymentMethod = "Cash",
            paymentChannel = "Cash Tender",
            reference = "EXP-7",
            eventDateTime = "06 Oct 2026, 09:20 AM",
            note = "Printer paper"
        )

    private fun paymentImage(event: UniversalWhatsAppMessageComposer.PaymentReceived) =
        ReceiptRenderer.EventReceipt(
            businessName = event.party.businessName,
            title = UniversalWhatsAppMessageComposer.PAYMENT_RECEIVED_TITLE,
            reference = event.reference.orEmpty(),
            dateTime = event.eventDateTime,
            customerName = event.party.customerName,
            rows = UniversalWhatsAppMessageComposer.paymentReceivedRows(event).map {
                ReceiptRenderer.EventReceipt.Row(it.label, it.value)
            }
        )

    @Test
    fun paymentReceived_imageAndTextShowTheSameValues() {
        val event = payment()
        val image = ReceiptRenderer.buildEventLines(paymentImage(event)).map { it.text }
        val message = UniversalWhatsAppMessageComposer.compose(event)

        listOf(
            "Amount Received: OMR 5.000",
            "Payment: Cash",
            "Total Credit Pending: OMR 91.000"
        ).forEach {
            assertTrue("image is missing: $it", image.contains(it))
            assertTrue("text is missing: $it", message.contains(it))
        }
        assertEquals(listOf("Test Shop", "RECEIPT"), image.take(2))
        assertEquals(UniversalWhatsAppMessageComposer.FOOTER, image.last())
        assertTrue(message.contains("Payment Received"))
    }

    @Test
    fun paymentReceived_leavesOutPendingBalanceWhenNotExact() {
        val event = payment(pending = null)
        val image = ReceiptRenderer.buildEventLines(paymentImage(event)).map { it.text }
        val message = UniversalWhatsAppMessageComposer.compose(event)

        assertFalse(image.any { it.contains("Pending") })
        assertFalse(message.contains("Pending"))
    }

    @Test
    fun expenseText_keepsItsOriginalOrder() {
        val lines = UniversalWhatsAppMessageComposer.compose(expense()).lines()
        val start = lines.indexOf("Amount: OMR 12.500")

        assertTrue(start > 0)
        assertEquals("Category: Supplies", lines[start + 1])
        assertEquals("Payment: Cash", lines[start + 2])
        assertEquals("Payment Channel: Cash Tender", lines[start + 3])
        assertTrue(lines.contains("Reference: EXP-7"))
        assertTrue(lines.contains("Note: Printer paper"))
    }

    @Test
    fun expenseVoucher_usesVoucherHeadingAndPaidToLabel() {
        val event = expense()
        val image =
            ReceiptRenderer.EventReceipt(
                businessName = event.party.businessName,
                title = UniversalWhatsAppMessageComposer.EXPENSE_TITLE,
                reference = event.reference,
                dateTime = event.eventDateTime,
                customerName = event.party.customerName,
                rows = UniversalWhatsAppMessageComposer.expenseRows(event).map {
                    ReceiptRenderer.EventReceipt.Row(it.label, it.value)
                },
                heading = "PAYMENT VOUCHER",
                partyLabel = "Paid to"
            )
        val lines = ReceiptRenderer.buildEventLines(image).map { it.text }

        assertEquals(listOf("Test Shop", "PAYMENT VOUCHER", "Expense Details"), lines.take(3))
        assertTrue(lines.contains("Paid to: Test Customer"))
        assertTrue(lines.contains("Amount: OMR 12.500"))
        assertTrue(lines.contains("Payment Channel: Cash Tender"))
        assertEquals(UniversalWhatsAppMessageComposer.FOOTER, lines.last())
    }
}
