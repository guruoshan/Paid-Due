# Paid&Due release notes — Financial integrity hotfix

Date: 2026-10-19

## Fixed
- Unpaid Credit returns reduce the authoritative Credit obligation without creating a Cash/Online payout or Coupon wallet balance.
- Refund-to-Coupon only records Coupon value when the Credit-return plan proves a positive amount of customer-paid value is refundable. For partially repaid Credit, unpaid debt reduction and paid-value return remain separate amounts.
- Added an Android instrumentation regression case for a 3,000-minor-unit unpaid Credit return with Coupon selected. It checks the Credit balance decreases by exactly the return amount, no refund settlement is created, and no Coupon wallet/value is created. The existing same-operation replay assertions also cover duplicate processing.
- Recent Transactions rows are more compact, visible transaction references/IDs are removed, and payment-method labels are shown for sales and other activity. Status, time, amount, type, and tap-through details remain.

## Verification status
This note describes the code change; it does not certify a release. The PR's Android unit/instrumentation tests, report reconciliation, export validation, signed APK/AAB generation, signing-certificate verification, and installability must pass before release.
