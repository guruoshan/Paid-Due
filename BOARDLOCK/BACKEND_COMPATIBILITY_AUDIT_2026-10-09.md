# BOARDLOCK — Android / Supabase Backend Compatibility Audit

**Date:** 2026-10-09  
**Base:** `migration/offline-first-supabase-v1`  
**Repair branch:** `fix/backend-compatibility-audit-2026-10`  
**Production backend/schema/data:** unchanged  
**Status (updated 2026-10-10):** Receiver remains incomplete. GitHub Actions run `37988695340` passed both default and explicitly inbound-enabled build/unit-test steps on commit `6fefc4d8817e76e64b006557e20f77194b464c8b`. Subsequent commits add remote digest validation, tests, and staged digest-bearing RPC contracts; their CI is pending. The live download RPCs were read-only inspected and confirmed not to return `canonical_sha256`. Inbound now fails closed until the new RPC contract is reviewed and deployed separately. No production database/function was changed.

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
- The inbound gate is **disabled by default** at current head `593cba76ea759aa3d5c6b9e9f94f576918e57d93` (`PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES=false`). A controlled test build can explicitly override it; do not enable it for production until the receiver contract and device verification pass.

## Repairs already present on this branch

1. **Admin login did not establish backend auth for an already-populated local database.** `AuthRepository.loginAdmin` now attempts Supabase Auth after successful local credential verification, validates the remote Admin membership/business/branch/user against the active local session, and is bounded by a short timeout. Cloud failure does not block local offline login; sync remains fail-closed.
2. **Authorization-blocked outbox recovery was missing.** Existing `AUTH_REQUIRED` rows are requeued only when the server confirms the active Supabase Admin identity maps to the same local membership, business and branch, and only for rows belonging to that same local actor/tenant. Operation IDs and financial rows are unchanged.
3. **Admin upload accepted any decoded RPC row as an acknowledgement.** The app now requires the backend receipt status to be exactly `ACKNOWLEDGED`; unexpected statuses go to `NEEDS_ATTENTION`, not false success.
4. **Cashier inbound could fall through to the Admin RPC if the custom session was missing.** The app now fails closed rather than switching authentication paths.
5. **Admin inbound fetch explicitly requires a Supabase Auth session.** A local Room session is not treated as backend authentication.

## Bidirectional audit findings — 2026-10-09 follow-up

### Inbound integrity and replay
- The previous receiver returned Skipped for any locally SUCCEEDED operation ID before validating the envelope's business/branch scope or immutable identity. It now validates payload metadata against the transport envelope and checks business, branch, operation type, and request fingerprint before treating an existing operation as a replay.
- The generic materializer previously ignored the affected-row count from markSucceededIfPending and returned success even if the matching operation could not be finalized. It now requires successful finalization (or a confirmed already-succeeded operation); a failed check throws inside the Room transaction so related inserts roll back.
- A resumed sale operation now also finalizes only after transaction, item, and settlement evidence is persisted.
- The current head has now passed the GitHub Actions Android build and unit-test job. These are hardening changes, not proof of full receiver completion; targeted inbound-operation coverage and device verification remain outstanding.

### Outbound evidence gap
- FinancialEnvelopeCodec currently serializes transaction/items/settlements and expense snapshots only. It does not serialize the dedicated amount-correction, payment-method-change, void, refund, credit-repayment/allocation, coupon-ledger, expense-event, or cash-shift event evidence required to reconstruct those operations on another device.
- FinancialSyncEngine.syncOutbound passes the transaction, items, settlements, and expense to the codec; it does not load and pass the other event/ledger rows. Therefore, simply adding inbound dispatch cases would not complete two-way sync.
- The outbox recognizes many operation types for Admin delivery, but its one-time classifier marks Cashier expense/cash-shift operations as NOT_SYNCABLE_YET. That policy must be reconciled with actual server permissions and product contract before claiming every operation can flow both ways; do not bypass backend authorization to force uploads.
- Inbound DTO now carries `canonical_sha256`, and the engine recomputes SHA-256 over `canonical_text` and rejects missing/mismatched digests before applying. A read-only query of the live project `ttvdpgxqvvtjnxxfkoui` confirmed `fetch_financial_envelopes_v2`, `fetch_financial_envelopes`, and `fetch_cashier_financial_envelopes` all currently return only canonical text/payload, not the digest. Therefore the new check intentionally blocks live inbound application until a digest-bearing backend contract is approved and deployed.

### Build verification
- Added `.github/workflows/paid-due-repair-validation.yml` to run `testDebugUnitTest` and `assembleDebug`.
- CI run `37980101900` failed during Android SDK setup before Gradle ran. The workflow was corrected; run `37984632299` then completed with `android-build-and-unit-tests: success` on current head `593cba76ea759aa3d5c6b9e9f94f576918e57d93`.
- CI run `37988695340` passed both the default build/test step and a second build/test step with `PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES=true`. This checks compilation/tests under both configurations; it does not validate missing operation handlers or live backend compatibility.
- No production Supabase schema, RLS, Edge Function, financial data, merge, or release was changed.

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
- Commits `318242f45c640c0abb6ef7c30af3544b04c03778` and `80c94fc7945fba83073287bcb7b4428e9d98338e` changed the materializer and audit contract. The current head's default Android build/unit-test CI passes, but feature-flag-on build, dedicated inbound-operation tests, and Admin/Cashier device verification are still outstanding.
- Do not merge or release solely because the inbound flag is enabled. Completion requires the full receiver contract, tests, and device verification above.


## Follow-up — inbound digest contract (2026-10-10)

- Android now carries the server-provided `canonical_sha256` field through `RemoteEnvelopeV2Dto` and `RemoteEnvelopeV2`.
- Before any envelope reaches the materializer, the sync engine computes SHA-256 over the exact `canonical_text` and requires a non-empty matching digest. Missing/mismatched digest is reported as an inbound rejection; the cursor is not advanced.
- Added a unit-test assertion for a valid digest and a negative test that verifies a mismatched digest is rejected before the inbound applier is called.
- Read-only inspection of the live function signatures confirmed the three existing download RPCs do not return `canonical_sha256`. No live SQL or Edge Function was changed.
- Added `BOARDLOCK/staged_sql/fetch_financial_envelopes_digest_v3.sql`, proposing new Admin `fetch_financial_envelopes_v3` and Cashier `fetch_cashier_financial_envelopes_v2` RPCs that include the stored digest and preserve scoped authorization. Android and the Cashier Edge Function source now target these new names.
- **Deployment dependency:** the staged SQL and Edge Function are not deployed. Inbound remains blocked until backend review, deployment, read-only signature verification, and safe device testing. Do not enable inbound in production.
- Latest branch CI for these follow-up commits is pending; no pass is claimed for the new digest changes yet.


## Follow-up — cashier download authorization defect (2026-10-10)

- Read-only review of `BOARDLOCK/staged_sql/fetch_financial_envelopes_digest_v3.sql` found that `fetch_cashier_financial_envelopes_v2` joined `public.profiles` using `m.auth_user_id`, while the active cashier memberships under review have `auth_user_id IS NULL` and use `staff_identity_id`. That join made the authorization existence check impossible for those cashier memberships.
- Removed only the `profiles` join from the **staged cashier RPC**; the active membership/business/branch checks and requirement for a non-null `staff_identity_id` remain. The Admin RPC's profile check was preserved.
- Commit: `2ee414b0c986b05745827f1ed118544e48995afb` on `fix/backend-compatibility-audit-2026-10`.
- This is a source/staging correction only. It has not been deployed or tested against a database. Production SQL and financial data remain unchanged. The two local Pixel `NEEDS_ATTENTION` operation IDs still require a read-only local outbox export; this fix does not claim to resolve those outbound rows.


## Follow-up — local outbox terminal-state diagnosis (2026-10-10)

- Source inspection confirms `FinancialSyncOutboxDao.getPendingUpload()` selects only `QUEUED` and `WAITING_RETRY`. Rows in `NEEDS_ATTENTION` are not automatically retried. This is a terminal delivery state by design, so seeing `NEEDS_ATTENTION` plus no later upload request is consistent with the scheduler excluding those rows.
- This proves why a row already in `NEEDS_ATTENTION` will not be sent again; it does **not** prove what originally moved either Pixel row into that state or identify the two operation IDs. The server-side candidate IDs must not be substituted for local outbox IDs.
- Required read-only evidence remains the Pixel outbox rows (operation ID, status, last error/attention message, attempt count, timestamps) plus matching logcat entries around the last state transition. Do not bulk-reset or auto-retry `NEEDS_ATTENTION` rows: permanent validation/integrity failures require diagnosis first.


## Applied repair — cashier download authorization (2026-10-10)

- Production Supabase project `ttvdpgxqvvtjnxxfkoui`: migration `fix_cashier_financial_download_auth` applied successfully. It replaces the cashier download RPC's impossible `profiles.auth_user_id = membership.auth_user_id` check with active `staff_identities`, active membership, active business, and same-branch checks. The RPC remains executable by `service_role` only; `authenticated` and `anon` remain denied.
- Post-migration read-only check: both active cashier memberships returned successfully from the RPC (one-row bounded probe); function privilege check confirmed service_role=true, authenticated=false, anon=false. The query did not modify financial records.
- Source migration committed on `fix/backend-compatibility-audit-2026-10`: `5682f40c54cbbe98f4a0e56c34da67c7c6495bae`.

## Applied source repair — inbound receiver gate (2026-10-10)

- Production contains 41 financial envelopes; read-only integrity query found 0 missing/invalid SHA-256 digests and 0 canonical-text/JSONB mismatches.
- Debug builds now default inbound application ON so the receiver can apply supported validated envelopes and the cross-device path can be tested. Release builds remain OFF by default. The existing Gradle property/environment override remains available for an explicitly approved release rollout.
- This is source configuration only; no Android build was deployed. The physical Admin device must be rebuilt/reinstalled from this repair branch to exercise inbound application. Any rejected envelope must be diagnosed; do not bypass validation.

## Still unresolved

- The two local Pixel `NEEDS_ATTENTION` outbox rows have not been exported, so their original failure and exact IDs are not yet proven. Do not substitute server-side candidate IDs for local IDs.
- Cashier upload still intentionally allowlists only sale/lifecycle operations with existing membership permission columns. Cash-shift and expense sync need an explicit permission model and server contract before they can safely be enabled; do not weaken the allowlist blindly.
- Full end-to-end Android build/test and physical-device reconciliation are still required. No release build or app deployment has been performed.
