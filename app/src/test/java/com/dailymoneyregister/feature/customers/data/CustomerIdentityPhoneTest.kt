package com.dailymoneyregister.feature.customers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CustomerIdentityPhoneTest {

    @Test
    fun spacesAndDashesGiveTheSameStandardPhone() {
        assertEquals("+96891234567", CustomerIdentity.canonicalPhone("+968 9123 4567"))
        assertEquals("+96891234567", CustomerIdentity.canonicalPhone(" +968-9123-4567 "))
        assertEquals("+96891234567", CustomerIdentity.canonicalPhone("+96891234567"))
    }

    @Test
    fun otherCountriesAreAccepted() {
        assertEquals("+919876543210", CustomerIdentity.canonicalPhone("+91 98765 43210"))
    }

    @Test
    fun phoneWithoutCountryCodeIsRejected() = assertRejected("91234567")

    @Test
    fun tooShortPhoneIsRejected() = assertRejected("+968123")

    @Test
    fun lettersAreRejected() = assertRejected("+968abcdefgh")

    @Test
    fun blankPhoneIsRejected() = assertRejected("   ")

    private fun assertRejected(raw: String) {
        try {
            CustomerIdentity.canonicalPhone(raw)
            fail("Expected rejection for '$raw'")
        } catch (expected: CustomerIdentityException) {
            // ok
        }
    }
}
