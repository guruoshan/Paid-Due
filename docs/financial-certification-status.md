# Financial certification status

This table is an implementation queue, not a claim of certification. An operation is PASS only when its current commit has a matching `ci-results/cert-latest.md` and every required stage has verified evidence.

| Order | Operation | Status | Next required work |
|---:|---|---|---|
| 1 | RECORD_EXPENSE | BLOCKED | Complete the real sender → outbox → upload → digest → download → receiver materialization → after-snapshot → replay → reports path; resolve only the first failing stage and require matching-SHA evidence. |
| 2 | RECORD_SALE | BLOCKED | Build a separate real sale driver; validate sale transaction/items, tender/settlement, ledger/allocation, cash-shift effects, replay invariance, and reporting totals. |
| 3 | RECORD_REFUND | BLOCKED | Add real refund command/outbox/sync/materialization path; prove append-only refund records, original-sale linkage, totals, replay, and reports. |
| 4 | RECORD_VOID | BLOCKED | Add real void path; prove append-only void/correction semantics, reversal accounting, replay invariance, and reports. |
| 5 | RECORD_CREDIT_REPAYMENT | BLOCKED | Add repayment path; prove customer balance and allocation changes, tender/ledger rows, replay, and reports. |
| 6 | RECORD_COUPON_PURCHASE | BLOCKED | Add coupon purchase path; prove coupon liability/balance, sale/tender rows, replay, and reports. |
| 7 | RECORD_PAYMENT_METHOD_CHANGE | BLOCKED | Add method-change path; prove immutable event history and derived payment-method totals across sync and replay. |
| 8 | RECORD_AMOUNT_CORRECTION | BLOCKED | Add correction path; prove append-only correction history and conservation of financial totals; never overwrite source transactions. |
| 9 | OPEN_CASH_SHIFT | BLOCKED | Add shift-open path; prove shift state/event replication, ownership/scope, replay, and shift reports. |
| 10 | COUNT_CASH_SHIFT | BLOCKED | Add cash-count path; prove count events and expected-vs-counted cash values replicate without duplicate events. |
| 11 | CLOSE_CASH_SHIFT | BLOCKED | Add shift-close path; prove final shift state, reconciliation values, event history, replay, and shift reports. |
| 12 | RECORD_EXPENSE_CORRECTION | BLOCKED | Add append-only expense correction path; prove original/correction linkage, audit trail, ledger conservation, replay, and reports. |
| 13 | RECORD_EXPENSE_VOID | BLOCKED | Add append-only expense void path; prove original/void linkage, no destructive deletion, ledger conservation, replay, and reports. |

## Build order and release gate

Build and certify in the order above, one operation per change. Keep the driver selector fail-closed for every operation without a complete real driver. For each operation, require a matching commit SHA in `ci-results/cert-latest.md`, all required stages PASS, receiver snapshots taken from the receiver database after production materialization, replay invariance, and report-oracle agreement. Do not mark PASS from compilation, unit tests, or a source-code inspection alone.
