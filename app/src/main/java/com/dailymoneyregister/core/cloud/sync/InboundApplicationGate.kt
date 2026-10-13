package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.BuildConfig

/**
 * Release-safe inbound switch.
 *
 * The first production vertical slice is RECORD_SALE only. Other operation types
 * remain fail-closed until their complete cross-device materialization contract
 * is explicitly approved. Debug builds and explicit test overrides retain their
 * existing behavior.
 */
internal object InboundApplicationGate {
    var testOverride: Boolean? = null

    private val releaseSafeOperationTypes = setOf("RECORD_SALE")

    fun enabled(): Boolean = testOverride ?: BuildConfig.APPLY_INBOUND_FINANCIAL_ENVELOPES

    fun enabledFor(operationType: String): Boolean =
        shouldEnable(
            operationType = operationType,
            buildFlag = BuildConfig.APPLY_INBOUND_FINANCIAL_ENVELOPES,
            override = testOverride
        )

    internal fun shouldEnable(
        operationType: String,
        buildFlag: Boolean,
        override: Boolean?
    ): Boolean = override ?: (buildFlag || operationType in releaseSafeOperationTypes)
}
