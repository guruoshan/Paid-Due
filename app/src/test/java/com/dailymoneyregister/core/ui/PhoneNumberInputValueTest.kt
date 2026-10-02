package com.dailymoneyregister.core.ui

import com.dailymoneyregister.core.identity.InvalidPhoneNumberException
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberInputValueTest {
    @Test fun merchantCountryAndCashierCountryDriveLocalNormalization() {
        val initial = PhoneNumberInputValue("OM", "91234567")
        assertEquals("+96891234567", initial.normalizedNumber())
        val changed = initial.copy(countryCode = "GB", rawNumber = "020 7946 0018")
        assertEquals("+442079460018", changed.normalizedNumber())
        assertEquals("+44 20 7946 0018", changed.internationalNumber())
    }

    @Test fun explicitInternationalNumberOverridesDefaultCountry() {
        val value = PhoneNumberInputValue("OM", "+1 202-555-0123")
        assertEquals("+12025550123", value.normalizedNumber())
        assertEquals("+1 202-555-0123", value.internationalNumber())
    }

    @Test fun countriesOutsideSetupListAreSupported() {
        assertEquals("+33142345678", PhoneNumberInputValue("FR", "01 42 34 56 78").normalizedNumber())
        assertEquals("+81312345678", PhoneNumberInputValue("JP", "03-1234-5678").normalizedNumber())
    }

    @Test(expected = InvalidPhoneNumberException::class)
    fun invalidInputCannotProduceCanonicalValue() {
        PhoneNumberInputValue("OM", "123").normalizedNumber()
    }

    @Test(expected = InvalidPhoneNumberException::class)
    fun emptyInputCannotProduceCanonicalValue() {
        PhoneNumberInputValue("OM").normalizedNumber()
    }
}
