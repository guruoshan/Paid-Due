package com.dailymoneyregister.feature.customers.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppTransportTest {

    @Test
    fun recipientJid_stripsFormattingAndKeepsInternationalCountryCode() {
        assertEquals(
            "96891234567@s.whatsapp.net",
            WhatsAppTransport.recipientJid("+968 9123-4567")
        )
    }

    @Test
    fun recipientJid_rejectsMissingOrObviouslyInvalidPhoneNumbers() {
        assertNull(WhatsAppTransport.recipientJid(""))
        assertNull(WhatsAppTransport.recipientJid("   "))
        assertNull(WhatsAppTransport.recipientJid("12345"))
        assertNull(WhatsAppTransport.recipientJid("1234567890123456"))
    }

    @Test
    fun recipientJid_doesNotEmitNonAsciiDigits() {
        assertNull(WhatsAppTransport.recipientJid("٩٦٨٩١٢٣٤٥٦٧"))
    }
}
