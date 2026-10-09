# Inbound envelope application

Inbound envelopes are fetched and validated by the Android client. Debug builds apply supported, validated envelopes by default so cross-device behavior can be tested. Release builds keep application disabled by default.

- Debug default: `APPLY_INBOUND_FINANCIAL_ENVELOPES=true`.
- Release default: `APPLY_INBOUND_FINANCIAL_ENVELOPES=false`.
- The Gradle property/environment variable `PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES` explicitly overrides either build type. Do not set it to `true` for a release until the full operation matrix, migration, security, and physical-device verification are approved.
- The materializer rejects invalid digests, tenant/branch mismatches, missing linked evidence, and unsupported operation types. Rejections must be inspected; do not skip validation or silently advance past failed envelopes.

A debug build must still pass automated tests and a physical-device reconciliation before it is treated as production-ready. This configuration change does not deploy a new app build.
