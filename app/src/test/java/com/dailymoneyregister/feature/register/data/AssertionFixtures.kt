package com.dailymoneyregister.feature.register.data

internal inline fun <reified T : Throwable> assertRejectedWith(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        org.junit.Assert.assertTrue(
            "expected ${T::class.java.simpleName}, got ${e::class.java.simpleName}",
            e is T
        )
        return
    }
    org.junit.Assert.fail("expected ${T::class.java.simpleName}, but no exception was thrown")
}
