# Inbound envelope application

Inbound financial-envelope application is enabled by default in release builds for the approved **RECORD_SALE** vertical slice. The Android client still validates the envelope and its tenant, branch, actor, payment settlements, and linked evidence before writing local records.

- Debug default: `APPLY_INBOUND_FINANCIAL_ENVELOPES=true`.
- Release default: `APPLY_INBOUND_FINANCIAL_ENVELOPES=true`.
- Release operation allowlist: `RECORD_SALE` only. Refunds, corrections, voids, credit repayments, coupon operations, cash shifts, and all other types remain fail-closed until their complete materialization contracts are approved.
- The Gradle property/environment variable `PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES` remains an override for debug/test configurations; it does not widen the release operation allowlist.
- Invalid envelopes, tenant/branch mismatches, missing linked evidence, and unsupported operation types must be rejected and inspected. Never bypass validation or silently advance past failed envelopes.

This change configures future release builds; it does not modify an already-published APK. A new signed release must build, pass CI, be published, and be installed before the setting takes effect on devices.
