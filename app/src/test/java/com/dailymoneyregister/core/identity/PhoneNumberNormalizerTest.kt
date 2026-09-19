package com.dailymoneyregister.core.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test fun localOmanNumberBecomesE164() {
        assertEquals("+96891234567", PhoneNumberNormalizer.normalize("91234567", "OM"))
    }

    @Test fun internationalNumberBecomesE164() {
        assertEquals("+96891234567", PhoneNumberNormalizer.normalize("+968 9123 4567", "OM"))
        assertEquals("+96891234567", PhoneNumberNormalizer.normalize("00968 91234567", "OM"))
    }

    @Test(expected = InvalidPhoneNumberException::class)
    fun malformedNumberIsRejected() {
        PhoneNumberNormalizer.normalize("not-a-phone", "OM")
    }
}
