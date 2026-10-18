package com.dailymoneyregister.feature.auth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginInputValidatorTest {
    @Test fun `admin requires a valid email and password`() {
        assertEquals("Email address is required", LoginInputValidator.error("", "secret"))
        assertEquals("Enter a valid email address", LoginInputValidator.error("+96890000000", "secret"))
        assertEquals("Password is required", LoginInputValidator.error("admin@example.com", ""))
        assertNull(LoginInputValidator.error("admin@example.com", "secret"))
    }
}
