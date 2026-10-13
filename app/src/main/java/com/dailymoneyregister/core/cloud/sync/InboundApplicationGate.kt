package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.BuildConfig

/**
 * Release-safe inbound switch.
 *
 * Release builds allow only operation types with an existing inbound materializer
 * and operation-specific evidence validation. New operation types must not be added
 * here until their payload, persistence, idempotency, and replay behavior are verified.
 */
internal object InboundApplicationGate {
    var testOverride: Boolean? = null

    private val releaseSafeOperationTypes = setOf(
        "RECORD_SALE",
        "RECORD_CREDIT_REPAYMENT",
        "RECORD_COUPON_PURCHASE",
        "RECORD_AMOUNT_CORRECTION",
        "RECORD_PAYMENT_METHOD_CHANGE",
        "RECORD_VOID",
        "RECORD_REFUND",
        "RECORD_EXPENSE",
        "RECORD_EXPENSE_CORRECTION",
        "RECORD_EXPENSE_VOID",
        "OPEN_CASH_SHIFT",
        "COUNT_CASH_SHIFT",
        "CLOSE_CASH_SHIFT"
    )

    fun enabled(): Boolean = testOverride ?: BuildConfig.APPLY_INBOUND_FINANCIAL_ENVELOPES

    fun enabledFor(operationType: String): Boolean =
        shouldEnable(
            operationType = operationType,
            buildFlag = BuildConfig.APPLY_INBOUND_FINANCIAL_ENVELOPES,
            override = testOverride,
            debugBuild = BuildConfig.DEBUG
        )

    internal fun shouldEnable(
        operationType: String,
        buildFlag: Boolean,
        override: Boolean?,
        debugBuild: Boolean = false
    ): Boolean = override ?: if (debugBuild) {
        buildFlag
    } else {
        buildFlag && operationType in releaseSafeOperationTypes
    }
}
