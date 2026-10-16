# Paid&Due Inbound Expense + Cashier Reporting Repair — 2026-10-16

## Scope

Production source of truth: `main` in `guruoshan/Paid-Due`.
Supabase project audited read-only: `ttvdpgxqvvtjnxxfkoui`.
No financial transaction/envelope rows were deleted or overwritten.

## Confirmed Android inbound expense defect

`RemoteEnvelopeMaterializer.materializeEnvelope` wrote `settlement_entries` before inserting the owning `operations` row. Room defines a foreign key from `settlement_entries.operation_id` to `operations.id`, so a real Room database rejects a valid expense envelope at settlement insertion. The previous mocked unit test did not enforce this database constraint.

### Fix on main

- Persist the inbound expense and its operation inside the same Room transaction.
- Insert the operation first, as `SUCCEEDED`, then insert its settlement(s); this satisfies the operation foreign key.
- Keep expense operations' `transaction_id` null. The expense ID remains the operation subject/result reference, not a transaction ID.
- Do not perform a local `PENDING -> SUCCEEDED` transition for downloaded evidence. That transition can activate the local outbound outbox trigger and re-enqueue remote evidence.
- Add regression assertions for operation-before-settlement ordering, no outbound status transition, and null expense transaction linkage.

## Confirmed cashier-report attribution defect

Sale report rows used `TransactionEntity.createdByUserId` as cashier identity. That value is not guaranteed to equal the immutable actor recorded on the originating sale operation, so the cashier filter could omit/misattribute a sale and produce incorrect cashier totals.

### Fix on main

- Load originating sale operation records alongside the report evidence.
- Attribute a sale row to its `SALE_FUNDING` operation actor (or the positive credit-obligation operation when needed), with the transaction creator retained only as a legacy fallback when no authoritative operation can be resolved.
- Apply the cashier scope before sorting and aggregation, so the report summary is built from the filtered rows.
- Change the Room integration fixture so transaction creator and operation actor differ; expected report attribution remains the cashier who performed the sale.

## Read-only Supabase audit

At audit time, `private.financial_operation_envelopes` contained 101 envelopes across sale, expense, refund, correction, repayment, coupon, void, and cash-shift operations.

Integrity query results across all 101 envelopes:
- Invalid SHA-256 format: 0
- SHA-256 mismatch against `canonical_text`: 0
- Canonical JSON payload mismatch: 0
- Operation/business/branch/type identity mismatch: 0

All four stored `RECORD_EXPENSE` envelopes also passed checks for matching operation, business, branch, actor, occurrence time, currency, digest, and one matching `EXPENSE_OUTFLOW` settlement. The live fetch functions return canonical digest and actor membership metadata. This evidence points to the local Room materialization ordering—not corrupted Supabase payloads—as the expense inbound rejection cause.

## Separate Supabase permission finding — not silently changed

The live `ingest_cashier_financial_envelope` function's `RECORD_EXPENSE` case checks `can_accept_cash` / `can_accept_online`, but does not check `can_create_expense`. The active cashier memberships inspected had `can_create_expense = false`. This is a separate authorization-policy mismatch. No staff permission was silently granted and no server permission policy was changed as part of the inbound fix. Resolve the intended cashier expense permission through the Admin permission workflow before changing the server gate.

## Verification

Regression tests were added for both defects. CI verification and signed-release status must be confirmed from the GitHub Actions run associated with the `[build-apk]` commit; source changes alone are not proof of a successful release or physical-device sync. After installation, verify an expense created on a cashier device appears exactly once on Admin and that filtering Reports to that cashier excludes other cashiers' sales and totals.
