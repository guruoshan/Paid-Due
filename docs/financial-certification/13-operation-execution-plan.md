# Paid&Due 13-Operation Financial Certification

## Release rule

Financial certification is the primary release gate. Source inspection, unit-test success alone, an envelope inventory, or a green Android build is not financial parity evidence. No release may be marked financially certified until all 13 operations have complete execution evidence and every assertion passes. Missing stages are NOT_RUN or PARTIAL, never PASS.

This plan does not authorize cashier decommissioning, SQL function removal, an Admin-only migration, schema cleanup, or production-data mutation.

## Isolation and safety

1. Use a dedicated Supabase test project and test credentials. Never run certification writes against the production project ttvdpgxqvvtjnxxfkoui.
2. Create a disposable test business and branch with known currency, timezone, tax settings, and opening balances. Prefix fixtures with a unique run ID.
3. Use independent sender and receiver app databases. Never copy the sender Room database to the receiver.
4. Keep backend URL and test-only credentials in protected CI secrets. Fail closed if the configured host/project is not the approved test project. Never print credentials.
5. Tear down only fixtures created by that run after evidence is finalized. Never delete shared data.

## Required operation matrix

RECORD_SALE; RECORD_REFUND; RECORD_VOID; RECORD_EXPENSE; RECORD_CREDIT_REPAYMENT; RECORD_COUPON_PURCHASE; RECORD_PAYMENT_METHOD_CHANGE; RECORD_AMOUNT_CORRECTION; OPEN_CASH_SHIFT; COUNT_CASH_SHIFT; CLOSE_CASH_SHIFT; RECORD_EXPENSE_CORRECTION; RECORD_EXPENSE_VOID.

The registry in FinancialCertificationCaseRegistry.kt is the machine-readable source. It initializes all stages to NOT_RUN and never grants PASS by default.

## Execution for every operation

1. Create a valid operation using the real application command/repository path on sender database A.
2. Capture operation ID, business ID, branch ID, operation type, timestamp, fingerprint, and before-state snapshot.
3. Verify the persisted outbox row and status before upload.
4. Upload through the normal sync path to the isolated backend. Verify exactly one canonical envelope exists for the operation ID.
5. Independently recompute SHA-256 over the exact UTF-8 canonical text and compare with the stored digest. Validate identity and payload shape.
6. Download through the normal inbound fetch path to independent receiver database B.
7. Materialize using the normal materializer and verify all operation-linked authoritative records.
8. Rebuild/refresh read models and compare reports to an independent oracle derived from authoritative records and business rules.
9. Replay the exact envelope. Verify replay is recognized as already applied and does not change financial rows, events, balances, read models, or reports.
10. Compare normalized sender/receiver records and report totals. Capture stable primary keys and canonical row hashes; ignore only documented device-local metadata.

## Record families to compare

Map each family to actual Room entities/DAOs and backend relations from checked-in schema before implementing the collector. Do not invent table names or compare only aggregates.

- Operation, outbox, envelope and acknowledgement state.
- Transaction header and line items.
- Settlement/payment-method entries.
- Append-only refund, void, correction and payment-method-change events.
- Expenses and expense correction/void events.
- Credit ledger entries, repayments and repayment allocations.
- Coupon events, wallet balances and authorization events affected by the operation.
- Cash-shift events and snapshots.
- Customers/receivables affected by the operation.
- Derived read models and report/export source rows.

## Reports and independent oracle

Compare daily financial summary/CFO snapshot, sales and refund/void reports, expenses, customer credit/receivable balances, coupon balances, payment-method totals, cash-shift report, financial-health report, and export totals. Use integer minor units or the business currency's defined scale, never floating-point comparisons. The oracle must not call the same production aggregation function as the report under test.

## Evidence bundle

Store one immutable JSON record per operation plus a run manifest. Include run ID, commit SHA, app version, non-secret environment identifier, test business/branch IDs, sender/receiver build IDs, timestamps, operation ID/type, stage statuses, envelope ID, canonical digest, independently recomputed digest, verification result, normalized record counts/hashes before materialization/after materialization/after replay, report expected/actual totals, currency scale, comparison result, assertion failures and artifact paths.

Never include access tokens, service-role keys, personal customer data, or production identifiers. Use synthetic data and pseudonymous IDs.

## PASS / FAIL rules

- PASS: all stages ran; exactly one canonical envelope uploaded/downloaded; digest verified; sender/receiver authoritative records match expected state; independent report oracle matches; replay changes no financial rows or totals; evidence validates.
- FAIL: any mismatch, missing required event/evidence, invalid digest, duplicate application, wrong report total, or replay mutation.
- PARTIAL: a required stage could not execute due to environment/device/backend availability or fixture prerequisites.
- NOT_RUN: execution did not start. Source inspection alone can never upgrade status.

A release is financially certified only when all 13 operations are PASS on the same candidate commit and CI retains the evidence bundle. Any code fix invalidates certification for the changed candidate and requires rerunning the full matrix.

## Automation phases

1. Contract tests: registry completeness and evidence schema validation.
2. Local Room instrumentation: real sender/receiver databases, materialization, atomicity, replay invariance, and normalized snapshots.
3. Isolated backend integration: create/upload/download/digest validation using a dedicated test Supabase project.
4. Report oracle: compare actual report entry points against independent calculations over authoritative records.
5. Release gate: run all 13 cases; retain machine-readable evidence and test reports; fail closed if any case is absent, non-PASS, skipped, or backend isolation cannot be proven.

The registry contract tests are a foundation only; they are not the end-to-end certification suite. Remaining phases must be implemented and executed before any operation or release can be marked PASS.
