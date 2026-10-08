package com.dailymoneyregister.feature.auth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginInputValidatorTest {
    @Test fun `admin requires email and password`() {
        assertEquals("Enter a valid email address", LoginInputValidator.error(LoginMode.ADMIN, "+96890000000", "secret"))
        assertEquals("Password is required", LoginInputValidator.error(LoginMode.ADMIN, "admin@example.com", ""))
        assertNull(LoginInputValidator.error(LoginMode.ADMIN, "admin@example.com", "secret"))
    }

    @Test fun `cashier keeps mobile identifier flow independent`() {
        assertNull(LoginInputValidator.error(LoginMode.CASHIER, "+96890000000", ""))
        assertEquals("Mobile number is required", LoginInputValidator.error(LoginMode.CASHIER, "", ""))
    }
}
