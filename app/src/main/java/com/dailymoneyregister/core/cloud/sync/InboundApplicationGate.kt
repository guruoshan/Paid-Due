package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.BuildConfig

/** Release-safe switch. Enable with -PPAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES=true. */
internal object InboundApplicationGate {
    var testOverride: Boolean? = null

    fun enabled(): Boolean = testOverride ?: BuildConfig.APPLY_INBOUND_FINANCIAL_ENVELOPES
}
