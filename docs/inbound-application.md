# Inbound envelope application

Inbound financial-envelope application is enabled by default in release builds for the operation types that currently have explicit materialization and evidence-validation paths. The Android client validates envelope identity, tenant/branch/actor scope, canonical digest, payment settlements, and linked immutable evidence before applying records atomically and idempotently.

- Debug default: `APPLY_INBOUND_FINANCIAL_ENVELOPES=true`.
- Release default: `APPLY_INBOUND_FINANCIAL_ENVELOPES=true`.
- Release allowlist:
  - Sales and coupon purchase: `RECORD_SALE`, `RECORD_COUPON_PURCHASE`.
  - Credit repayment: `RECORD_CREDIT_REPAYMENT`.
  - Post-sale changes: `RECORD_AMOUNT_CORRECTION`, `RECORD_PAYMENT_METHOD_CHANGE`, `RECORD_VOID`, `RECORD_REFUND`.
  - Expenses: `RECORD_EXPENSE`, `RECORD_EXPENSE_CORRECTION`, `RECORD_EXPENSE_VOID`.
  - Cash shifts: `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, `CLOSE_CASH_SHIFT`.
- Operation types without a dedicated inbound materialization path remain disabled. Adding a new type requires payload/evidence validation, atomic and idempotent persistence, replay/cursor tests, and cross-device verification.
- The Gradle property/environment variable `PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES` can disable inbound application; enabling it does not bypass the release operation allowlist.
- Invalid envelopes, tenant/branch mismatches, missing linked evidence, digest failures, and unsupported operation types must be rejected and inspected. The inbound cursor must not advance past a failed or unsupported envelope.

This changes future release builds only. A new signed release must pass CI, be published, and be installed before the setting takes effect on devices. Passing build/unit tests is not equivalent to physical-device end-to-end verification.
