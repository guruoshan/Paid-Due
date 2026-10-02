package com.dailymoneyregister.feature.invoice

import com.dailymoneyregister.feature.invoice.domain.InvoiceBusinessSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceCustomerSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceItemSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshotCodec
import com.dailymoneyregister.feature.invoice.domain.validationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NOTE: needs  testImplementation("org.json:json:20240303")  in app/build.gradle.kts
 * because the Android stub org.json is not available in plain JVM unit tests.
 */
class InvoiceSnapshotTest {

    private fun valid() = InvoiceSnapshot(
        documentId = "doc-1",
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        customer = InvoiceCustomerSnapshot(title = "Mr", name = "Ali", phone = "+96891234567", address = null),
        business = InvoiceBusinessSnapshot(name = "Muscatera", taxId = "OM123"),
        items = listOf(
            InvoiceItemSnapshot(
                id = "i1", name = "Frankincense", description = null,
                quantity = 2, unitPriceMinor = 5_000L, lineTotalMinor = 10_000L,
                itemPackingMinor = 100L, itemShippingMinor = 50L
            )
        ),
        subtotalMinor = 10_000L,
        taxRateBps = 500,
        taxAmountMinor = 500L,
        txShippingMinor = 0L,
        txPackingMinor = 0L,
        itemShippingTotalMinor = 100L,
        itemPackingTotalMinor = 200L,
        grandTotalMinor = 10_800L,
        note = "Thanks",
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun validSnapshotHasNoError() {
        assertNull(valid().validationError())
    }

    @Test
    fun codecRoundTripIsLossless() {
        val original = valid()
        assertEquals(original, InvoiceSnapshotCodec.decode(InvoiceSnapshotCodec.encode(original)))
    }

    @Test
    fun encodingIsStable() {
        val s = valid()
        assertEquals(InvoiceSnapshotCodec.encode(s), InvoiceSnapshotCodec.encode(s.copy()))
    }

    @Test
    fun wrongGrandTotalIsRejected() {
        assertNotNull(valid().copy(grandTotalMinor = 10_801L).validationError())
    }

    @Test
    fun missingCustomerIsRejected() {
        val s = valid()
        assertNotNull(s.copy(customer = s.customer.copy(name = " ")).validationError())
        assertNotNull(s.copy(customer = s.customer.copy(phone = "")).validationError())
    }

    @Test
    fun inconsistentLineTotalIsRejected() {
        val s = valid()
        val bad = s.items[0].copy(lineTotalMinor = 9_999L)
        assertNotNull(s.copy(items = listOf(bad)).validationError())
    }
}
