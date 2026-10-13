package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.BuildConfig

/**
 * Release-safe inbound switch.
 *
 * Release builds apply only RECORD_SALE until each other operation type has a
 * complete, verified cross-device materialization contract. Debug builds may
 * exercise expanded supported handlers, and tests may explicitly override the gate.
 */
internal object InboundApplicationGate {
    var testOverride: Boolean? = null

    private val releaseSafeOperationTypes = setOf("RECORD_SALE")

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
        operationType in releaseSafeOperationTypes
    }
}
