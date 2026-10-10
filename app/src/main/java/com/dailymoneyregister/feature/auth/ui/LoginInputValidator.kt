package com.dailymoneyregister.feature.auth.ui

internal object LoginInputValidator {
    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun error(mode: LoginMode, identifier: String, password: String): String? = when {
        identifier.isBlank() -> if (mode == LoginMode.ADMIN) {
            "Email address is required"
        } else {
            "Mobile number is required"
        }
        mode == LoginMode.ADMIN && !emailPattern.matches(identifier.trim()) ->
            "Enter a valid email address"
        mode == LoginMode.ADMIN && password.isBlank() -> "Password is required"
        else -> null
    }
}
