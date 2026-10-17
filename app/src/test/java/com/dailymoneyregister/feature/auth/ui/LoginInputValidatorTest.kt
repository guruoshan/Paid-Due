package com.dailymoneyregister.feature.auth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginInputValidatorTest {
    @Test
    fun `owner login requires a valid email and password`() {
        assertEquals(
            "Email address is required",
            LoginInputValidator.error("", "secret")
        )
        assertEquals(
            "Enter a valid email address",
            LoginInputValidator.error("+96890000000", "secret")
        )
        assertEquals(
            "Password is required",
            LoginInputValidator.error("owner@example.com", "")
        )
        assertNull(LoginInputValidator.error("owner@example.com", "secret"))
    }
}
