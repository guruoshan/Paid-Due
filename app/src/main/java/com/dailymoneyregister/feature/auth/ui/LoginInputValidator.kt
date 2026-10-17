package com.dailymoneyregister.feature.auth.ui

internal object LoginInputValidator {
    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun error(identifier: String, password: String): String? = when {
        identifier.isBlank() -> "Email address is required"
        !emailPattern.matches(identifier.trim()) -> "Enter a valid email address"
        password.isBlank() -> "Password is required"
        else -> null
    }
}
