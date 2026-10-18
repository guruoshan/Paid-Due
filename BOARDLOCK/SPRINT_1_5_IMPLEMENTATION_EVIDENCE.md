# Sprint 1–5 Financial Sync and Reconciliation Evidence

**Branch:** `fix/sprint-1-5-financial-reconciliation-2026-10-18`  
**PR:** #43 — https://github.com/guruoshan/Paid-Due/pull/43  
**Scope:** Sync path, replay safety, financial invariants, identity-based reconciliation, and 29-versus-25 classification only. No report visual redesign, PDF work, dashboard work, or branding work.

## 1. Exact 29-versus-25 count discrepancy

Read-only query against `private.financial_operation_envelopes` for `business_local_date = DATE '2026-10-18'` returned:

| Measure | Result |
|---|---:|
| All accepted cloud operations | 29 |
| Reportable financial operation types | 25 |
| Cash-shift operational events | 4 |
| Distinct operation IDs | 29 |
| Duplicate operation IDs | 0 |
| Count delta after explicit classification | 0 |

The four additional operations are cash-shift operational events, which are not transaction-detail rows:

| Operation type | Operation ID |
|---|---|
| `OPEN_CASH_SHIFT` | `01a14bd0-3b5e-70fc-bd95-24045c5fac96` |
| `COUNT_CASH_SHIFT` | `01a14ec8-c643-7461-b2da-68a56df0d31a` |
| `OPEN_CASH_SHIFT` | `01a14ec9-03e3-7e6a-a179-413e712f4fcb` |
| `COUNT_CASH_SHIFT` | `01a14eca-bb28-76f9-aa95-71f1056b05c6` |

The 25 reportable operation types in the same scope break down as follows:

| Operation type | Count |
|---|---:|
| `RECORD_SALE` | 15 |
| `RECORD_REFUND` | 2 |
| `RECORD_VOID` | 3 |
| `RECORD_AMOUNT_CORRECTION` | 1 |
| `RECORD_COUPON_PURCHASE` | 1 |
| `RECORD_CREDIT_REPAYMENT` | 1 |
| `RECORD_EXPENSE` | 2 |
| **Total reportable financial operations** | **25** |

### SQL used

```sql
SELECT
  business_id,
  branch_id,
  business_local_date,
  count(*) AS total_operations,
  count(*) FILTER (WHERE operation_type IN (
    'RECORD_SALE','RECORD_REFUND','RECORD_VOID',
    'RECORD_AMOUNT_CORRECTION','RECORD_PAYMENT_METHOD_CHANGE',
    'RECORD_CREDIT_REPAYMENT','RECORD_COUPON_PURCHASE',
    'RECORD_EXPENSE','RECORD_EXPENSE_CORRECTION','RECORD_EXPENSE_VOID'
  )) AS reportable_financial_operations,
  count(*) FILTER (WHERE operation_type IN (
    'OPEN_CASH_SHIFT','COUNT_CASH_SHIFT','CLOSE_CASH_SHIFT'
  )) AS operational_cash_shift_events,
  count(DISTINCT operation_id) AS distinct_operation_ids
FROM private.financial_operation_envelopes
WHERE business_local_date = DATE '2026-10-18'
GROUP BY business_id, branch_id, business_local_date;
```

**Finding:** the aggregate 29-versus-25 difference is explained by four cash-shift events that belong in operational history, not transaction-detail rows. This resolves the count arithmetic. Identity-by-identity validation of the 25 local report rows still depends on the new scoped inventory RPC being deployed and the app reconciliation run succeeding.

## 2. Code implementation evidence

| Workstream | Changes in this branch | Evidence required before marking complete |
|---|---|---|
| Sync path | Cashier upload/download entry points now allow only authenticated Admin/Cashier roles with business, branch, and membership scope checks. | Android CI plus real cashier-to-cloud-to-Admin device run. |
| Exactly-once | Materializer no longer immediately skips a succeeded operation solely on operation ID/fingerprint; it continues through persisted transaction/item/expense/settlement/evidence comparisons and returns replay-skipped only after the checks. | Unit/instrumentation replay and conflicting-payload tests. |
| Financial invariants | Sale tender and refund destination totals validated; voids require event-specific reversal evidence; split-tender repayment and allocation totals validated; coupon funding amount checked; full correction history checked. | Full reporting mapper/aggregator and materialization test suites. |
| Reconciliation engine | Adds scoped local operation inventory, digest-verified cloud inventory paging, local/cloud identity comparison, operation-to-row type checks, explicit cash-shift exclusions, and COMPLETE/INCOMPLETE/REQUIRES_REVIEW statuses. | RPC migration test, Android CI, and a report run with zero unexplained identities. |
| Reporting inventory RPC | Adds `public.fetch_financial_reporting_inventory_v1`, with business/branch membership checks, occurred-at bounds, actor filter, bounded pagination, and digest-bearing output. | SQL Behavioral Gate and migration validation; production deployment remains separate and is not performed by this change. |

## 3. Safety rules

- A local-only report cannot be marked `COMPLETE`.
- An unavailable or unverified cloud inventory produces `REQUIRES_REVIEW`.
- Duplicate IDs, conflicting local/cloud identity, digest mismatch, wrong operation type, or unmatched rows produce `REQUIRES_REVIEW`.
- Missing report rows, cloud-only materializations, local-only successful operations, or unresolved local operations prevent `COMPLETE`.
- Cash-shift events are explicitly classified as operational exclusions; they are not silently counted as missing transaction details.
- No production data or Supabase deployment was changed as part of this implementation branch.

## 4. CI and physical verification status

CI status is determined by the latest run on the current PR head, not by earlier branch runs. Physical cross-device verification has not been performed by this document. Do not approve release until the latest Android test/build checks, SQL Behavioral Gate, and migration checks pass, and a real cashier-to-cloud-to-Admin test demonstrates one authoritative effect with replay safely skipped.
