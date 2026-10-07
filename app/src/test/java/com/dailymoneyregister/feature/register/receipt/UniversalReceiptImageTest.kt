package com.dailymoneyregister.feature.register.receipt

import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BOARDLOCK_UNIVERSAL_RECEIPT_IMAGE_V4 — pure text checks, no Android classes. */
class UniversalReceiptImageTest {

    private val party =
        UniversalWhatsAppMessageComposer.Party(
            businessName = "Test Shop",
            customerName = "Test Customer",
            customerPhone = "+96890000000"
        )

    private fun saleReceipt() =
        ReceiptReadModel(
            businessName = "Test Shop",
            customerName = "Test Customer",
            customerPhone = "+96890000000",
            reference = "SAL-1",
            transactionTimestamp = 1_700_000_000_000L,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            subtotalMinor = 9_000L,
            taxMinor = 0L,
            shippingMinor = 0L,
            packingMinor = 0L,
            totalMinor = 9_000L,
            items = listOf(ReceiptReadModel.Item("Tea", 1, 9_000L)),
            tenders = emptyList()
        )

    private fun topUpEvent(reference: String? = "CPN-1") =
        UniversalWhatsAppMessageComposer.CouponEvent(
            party = party,
            kind = UniversalWhatsAppMessageComposer.CouponEventKind.PURCHASE_OR_TOP_UP,
            title = "Coupon top up successful",
            formattedAmount = "OMR 100.000",
            formattedBalance = "OMR 213.000",
            reference = reference,
            eventDateTime = "06 Oct 2026, 07:02 AM"
        )

    private fun eventImage(event: UniversalWhatsAppMessageComposer.CouponEvent) =
        ReceiptRenderer.EventReceipt(
            businessName = event.party.businessName,
            title = UniversalWhatsAppMessageComposer.couponEventTitle(event),
            reference = event.reference.orEmpty(),
            dateTime = event.eventDateTime,
            customerName = event.party.customerName,
            rows = UniversalWhatsAppMessageComposer.couponEventRows(event).map {
                ReceiptRenderer.EventReceipt.Row(it.label, it.value)
            }
        )

    @Test
    fun saleAndEventReceipts_shareTheSameHeaderAndFooter() {
        val sale = ReceiptRenderer.buildLines(saleReceipt()).map { it.text }
        val event = ReceiptRenderer.buildEventLines(eventImage(topUpEvent())).map { it.text }

        assertEquals(sale.take(2), event.take(2))
        assertEquals(listOf("Test Shop", "RECEIPT"), sale.take(2))
        assertEquals(sale.takeLast(2), event.takeLast(2))
        assertEquals(UniversalWhatsAppMessageComposer.FOOTER, event.last())
    }

    @Test
    fun topUpImage_showsTheSameValuesAsTheWhatsAppText() {
        val event = topUpEvent()
        val imageText = ReceiptRenderer.buildEventLines(eventImage(event)).map { it.text }
        val message = UniversalWhatsAppMessageComposer.compose(event)

        assertTrue(imageText.contains("Coupon Purchase / Top-up"))
        listOf(
            "Coupon value added: OMR 100.000",
            "Current Coupon Balance: OMR 213.000",
            "Reference: CPN-1",
            "Customer: Test Customer"
        ).forEach {
            assertTrue("image is missing: $it", imageText.contains(it))
        }
        assertTrue(message.contains("Coupon value added: OMR 100.000"))
        assertTrue(message.contains("Current Coupon Balance: OMR 213.000"))
        assertTrue(message.contains("Coupon Purchase / Top-up"))
    }

    @Test
    fun genericCouponEvent_usesAmountAndCouponBalanceLabels() {
        val event =
            topUpEvent().copy(
                kind = UniversalWhatsAppMessageComposer.CouponEventKind.GENERIC,
                title = "Refund credited to Coupon"
            )
        val labels = UniversalWhatsAppMessageComposer.couponEventRows(event).map { it.label }

        assertEquals(listOf("Amount", "Coupon Balance"), labels)
        assertEquals(
            "Refund credited to Coupon",
            UniversalWhatsAppMessageComposer.couponEventTitle(event)
        )
    }

    @Test
    fun eventImage_leavesOutBlankReferenceAndMissingBalance() {
        val event = topUpEvent(reference = "").copy(formattedBalance = null, eventDateTime = null)
        val imageText = ReceiptRenderer.buildEventLines(eventImage(event)).map { it.text }

        assertFalse(imageText.any { it.startsWith("Reference:") })
        assertFalse(imageText.any { it.startsWith("Date & Time:") })
        assertFalse(imageText.any { it.contains("Balance") })
    }
}
