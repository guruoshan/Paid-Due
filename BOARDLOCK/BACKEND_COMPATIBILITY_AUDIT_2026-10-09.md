# BOARDLOCK — Android / Supabase Backend Compatibility Audit

**Date:** 2026-10-09  
**Base:** `migration/offline-first-supabase-v1`  
**Repair branch:** `fix/backend-compatibility-audit-2026-10`  
**Production backend/schema/data:** unchanged  
**Status:** Partial receiver implementation committed on the repair branch. Inbound is enabled by default; non-cash/split-tender sales and validated `RECORD_EXPENSE` snapshots now have materialization paths. The receiver is still incomplete and has not been build/test/device-verified after these changes.

## Required receiver contract — all supported financial operations

The inbound receiver must apply **every supported financial operation, correction, update, and transaction envelope received from the backend**, not just cash sales. It must not silently ignore, misclassify, or falsely acknowledge an operation because it is not a cash sale.

The receiver must cover the complete backend operation contract:

- Sales: `RECORD_SALE` (cash, online/card, split tender, and other payment methods represented by the canonical payload).
- Credit: `RECORD_CREDIT_REPAYMENT`.
- Coupons: `RECORD_COUPON_PURCHASE`, coupon top-up/use/redemption/refund or other coupon operations wherever they are represented in the supported backend contract.
- Post-sale changes: `RECORD_AMOUNT_CORRECTION`, `RECORD_PAYMENT_METHOD_CHANGE`, `RECORD_VOID`, and `RECORD_REFUND`.
- Expenses: `RECORD_EXPENSE`, `RECORD_EXPENSE_CORRECTION`, and `RECORD_EXPENSE_VOID`.
- Cash shifts: `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, and `CLOSE_CASH_SHIFT`.
- Any additional operation type formally added to the backend contract must be added to the receiver, its validation, and its tests before it is treated as supported.

### Application rules

1. **Use the envelope as the delivery unit, not as permission to overwrite history.** Validate operation ID, business, branch, actor/authorization context, operation type, canonical payload, timestamp/timezone/local date, and integrity hash before applying.
2. **Apply atomically and idempotently.** One envelope must produce one consistent local result even if it is downloaded repeatedly, replayed after a crash, or delivered out of order. Use the stable operation ID as the deduplication key.
3. **Preserve financial history.** Corrections, payment-method changes, voids, refunds, expense corrections, and other reversals must be represented using their proper linked operation/event semantics. Do not silently mutate or replace the original financial event, duplicate its impact, or count a compensation as a new sale.
4. **Persist related records and links in the same database transaction.** This includes tender/payment breakdowns, customer/credit references, coupon wallet/ledger links, original-sale references for refunds/voids/corrections, expense references, and cash-shift records wherever applicable.
5. **Advance the inbound cursor only after successful durable application.** A crash or local database failure must allow safe replay. A duplicate already-applied envelope is a successful no-op, not a second financial posting.
6. **Never report success for unsupported or invalid data.** Unknown operation types, malformed payloads, tenant/branch mismatches, hash failures, missing required links, and integrity conflicts must be preserved as actionable failures. Do not advance past them in a way that loses recoverability; expose a clear sync state.
7. **Respect tenant boundaries and session type.** Apply only to the authenticated, active business/branch scope. Do not fall back from a missing cashier session to Admin authorization or vice versa.
8. **Do not fabricate data.** Do not create artificial financial transactions to test production sync. Use deterministic unit/integration fixtures and safe test environments.

## Verified backend contract

- Admin financial ingestion uses `public.ingest_financial_envelope`. It requires `auth.uid()` and validates that the caller is an active Admin membership for the submitted business and branch.
- Admin inbound fetch uses `public.fetch_financial_envelopes_v2`, which requires an authenticated Supabase identity and active Admin membership.
- Cashier financial upload/download uses the `/api/financial/upload` and `/api/financial/download` Edge Function routes with the custom cashier session token. Cashier authorization is checked server-side against membership and operation permissions.
- The live Edge Function `api` is active. The Android branch config points at the matching Supabase project and API base URL.
- The inbound gate default was changed to `true` in commit `1f4c36bb29da148970720646b2d5dd92e31db7c5` on this repair branch. This only enables the gate by default; it does **not** implement missing operation handlers or prove end-to-end safety.

## Repairs already present on this branch

1. **Admin login did not establish backend auth for an already-populated local database.** `AuthRepository.loginAdmin` now attempts Supabase Auth after successful local credential verification, validates the remote Admin membership/business/branch/user against the active local session, and is bounded by a short timeout. Cloud failure does not block local offline login; sync remains fail-closed.
2. **Authorization-blocked outbox recovery was missing.** Existing `AUTH_REQUIRED` rows are requeued only when the server confirms the active Supabase Admin identity maps to the same local membership, business and branch, and only for rows belonging to that same local actor/tenant. Operation IDs and financial rows are unchanged.
3. **Admin upload accepted any decoded RPC row as an acknowledgement.** The app now requires the backend receipt status to be exactly `ACKNOWLEDGED`; unexpected statuses go to `NEEDS_ATTENTION`, not false success.
4. **Cashier inbound could fall through to the Admin RPC if the custom session was missing.** The app now fails closed rather than switching authentication paths.
5. **Admin inbound fetch explicitly requires a Supabase Auth session.** A local Room session is not treated as backend authentication.

## Implementation gap — receiver is not yet complete

The current `RemoteEnvelopeMaterializer` now accepts recognized payment categories/channels for `RECORD_SALE` (rather than rejecting everything except `CASH`/`CASH_TENDER`) and routes a tenant/branch/actor/currency-validated `RECORD_EXPENSE` payload through the existing atomic materializer. Correction/refund/void/credit/coupon/cash-shift handlers are still not implemented. Therefore, the full receiver contract above remains a **required implementation target, not a claim that the app already supports every operation**.

Required work before calling inbound complete:

- Implement and verify dedicated idempotent, atomic handlers for `RECORD_CREDIT_REPAYMENT`, `RECORD_COUPON_PURCHASE` and other supported coupon operations, `RECORD_AMOUNT_CORRECTION`, `RECORD_PAYMENT_METHOD_CHANGE`, `RECORD_VOID`, `RECORD_REFUND`, `RECORD_EXPENSE_CORRECTION`, `RECORD_EXPENSE_VOID`, and all cash-shift operations, matching the existing Room entities/DAOs and canonical backend payloads. Do not mark a handler complete until it persists the proper linked event/ledger rows and is covered by tests. Do not invent parallel financial sources of truth or add schema migrations unless a demonstrated gap makes one necessary.
- Preserve the causal links between each original operation and its correction, refund, void, repayment, coupon ledger entry, expense event, or cash-shift event.
- Handle ordering, duplicate delivery, partial failure, cursor restart, replay, and old-client/new-client compatibility.
- Add tests for every operation type, each payment method/tender pattern, repeated delivery, out-of-order delivery, invalid hashes/payloads, missing linked records, tenant/branch mismatch, crash/restart, and cursor durability.
- Make sync status actionable in the UI: pending/local-only, acknowledged by server, applied from server, authentication required, rejected/unsupported, and integrity conflict must remain distinguishable.
- Verify the feature-flag-on build and full relevant test suite, then verify Admin and Cashier devices in a safe test setup before release. Do not create artificial production transactions.

## Evidence and safety notes

- A prior device log showed five Admin outbox rows in `AUTH_REQUIRED` (two `RECORD_SALE`, one each `CLOSE_CASH_SHIFT`, `COUNT_CASH_SHIFT`, and `RECORD_EXPENSE`) and 31 envelopes fetched from the cloud. Downloaded envelopes are not proof that blocked local records uploaded or that inbound operations were applied.
- The live cloud baseline observed during the audit contained sales, cash-shift openings, a coupon purchase, and a refund. Envelope counts can change as new operations arrive; counts are not a completion criterion.
- No production SQL, RLS, function ACL, deployment, or financial data was changed by this audit update.
- Commits `318242f45c640c0abb6ef7c30af3544b04c03778` and `80c94fc7945fba83073287bcb7b4428e9d98338e` changed the materializer and audit contract. Build/device verification and focused inbound tests have not been run against the exact current head; do not claim these changes pass until verified.
- Do not merge or release solely because the inbound flag is enabled. Completion requires the full receiver contract, tests, and device verification above.
