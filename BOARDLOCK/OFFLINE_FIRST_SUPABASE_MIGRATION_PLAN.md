# BOARDLOCK — Paid & Due Offline-First Supabase Migration Plan

**Plan version:** 1.0  
**Prepared:** 2026-10-09  
**Source branch:** `migration/supabase-edge-only`  
**Source commit:** `6a45e5b04ec975df4142428cd86ef0b83d5724e6`  
**Working branch:** `migration/offline-first-supabase-v1`  
**Status:** Architecture and execution plan recorded; implementation and release gates remain open.  
**Production data/schema/deployment:** unchanged by this plan commit.

## 1. Executive decision

Adopt an offline-first Android architecture with Room as the local operational store, a durable transactional outbox for unsynchronized commands, and PostgreSQL as the authoritative cloud record for accepted financial operations. Use Supabase Edge Functions as the stable server API for protected cashier operations and consolidate admin/cashier financial intake behind the same server-side validation and idempotency contract. Use Supabase Auth for administrator identity where the existing implementation already depends on it. Keep the custom cashier session only while its authentication and authorization contracts are explicitly tested; do not bypass it or expose service-role credentials in the app.

Realtime is a notification mechanism only. It must trigger an authorized catch-up query; it is not a durable delivery mechanism or financial source of truth.

This is a redesign of the connection and synchronization layers, not a rewrite of financial business rules, Room's financial source records, or UI flows.

## 2. Non-negotiable invariants

1. Never delete, reset, recreate, or silently rewrite existing financial history to simplify sync.
2. Preserve existing operation IDs, transaction IDs, business/branch identities, occurred-at timestamps, payment and settlement semantics, correction/refund/void links, and audit records.
3. One user financial intent has one stable operation ID. Retries must reuse it.
4. A matching operation ID and canonical digest is an idempotent duplicate; the same ID with a different digest is an integrity conflict and must never be overwritten.
5. A server receipt is accepted only when operation ID and canonical digest match the queued command.
6. Save the local financial operation and its pending outbox row atomically in the same Room transaction. Outbox rows are delivery metadata, not duplicate financial records.
7. Server-side validation enforces authenticated actor, membership, business, branch, device/session eligibility, permission, supported schema, payload integrity, and financial invariants.
8. A server-side financial operation and its associated evidence commit atomically in PostgreSQL.
9. Do not mark a command cloud-confirmed before a valid server receipt; do not delete a pending command after an HTTP 2xx alone.
10. Do not automatically enable inbound application until each supported operation type has an idempotent, atomic materializer and migration/recovery tests.
11. Keep online and offline user-visible states honest: locally recorded, pending upload, server-confirmed, rejected, or needs attention.
12. Preserve offline functionality, SQLCipher/encryption protections, staff permissions, and existing financial business rules.
13. No Cloudflare dependency. Supabase is the remote backend.
14. No production schema changes, function deployments, data writes, branch merge, or release are part of this planning commit. Execution changes are isolated to this working branch until the release gate is satisfied.

## 3. Current-state findings (source audit)

- Android already has Room, operation records, a financial sync outbox entity/DAO, envelope codec/contract, sync engine, retry logic, and a Supabase client.
- Admin financial upload/download and cashier Edge Function upload/download use different request paths and must be unified at the contract/validation layer.
- The existing receipt guard validates operation identity and digest; preserve and strengthen it.
- The outbox DAO and policy contain legacy/foundation-only language and operation-type restrictions; inspect actual enqueue paths and supported-operation policy before widening support.
- Inbound application is disabled by default through `PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES=false`. The current materializer does not cover every financial operation type and must not be enabled wholesale.
- Cursor/retry state includes in-memory or preferences-backed state; durable, monotonic catch-up behavior must be verified before relying on it for all records.
- Live Supabase currently contains 26 financial envelopes observed in the audit: 23 `RECORD_SALE`, 3 `OPEN_CASH_SHIFT`. These counts are a baseline observation, not proof of full synchronization. Re-query and reconcile read-only before migration.
- Supabase Security Advisor reported six private tables with RLS enabled and no policies, one SECURITY DEFINER RPC executable by anon, and eleven executable by authenticated users; these findings require function-by-function ACL and body review, not blanket grant changes.
- Performance Advisor reported two unindexed foreign keys. Handle only after query patterns and migration impact are reviewed.
- The previous cross-device handout contains stale foundation-only statements and must be updated to match verified implementation status after code changes.

## 4. Target architecture and responsibilities

### Android / Room
- Keep existing local business logic and financial commands.
- In one Room transaction: persist operation + all related local financial rows + outbox delivery metadata.
- Outbox row references the operation; it does not copy monetary authority.
- Outbox states: QUEUED, IN_FLIGHT, WAITING_RETRY, AUTH_REQUIRED, ACKNOWLEDGED, REJECTED, NEEDS_ATTENTION, INTEGRITY_CONFLICT, NOT_APPLICABLE. State transitions must be guarded and persisted.
- Recover stale IN_FLIGHT rows after process death and retry using bounded exponential backoff with jitter.
- Preserve queued commands through logout/session expiry; do not upload them under another business or actor. Re-authenticate and revalidate scope before sending.
- Room migrations must be forward-only and tested against both fresh and prior-version databases. Never destructive-migrate financial data.

### Supabase API
- Keep one documented command envelope and receipt contract for admin and cashier paths, even if authentication adapters differ.
- API validates input and session, then calls narrowly scoped PostgreSQL RPC functions.
- No direct client writes to canonical financial tables.
- Edge Function `verify_jwt=false` remains acceptable only for routes that intentionally use custom bearer sessions and validate them themselves; public health/invite routes are separately scoped. Every protected route must reject missing, expired, revoked, wrong-tenant, wrong-branch, or insufficient-permission sessions.
- Service-role key and other privileged secrets remain server-side only.
- API errors are classified as retryable, authorization-required, rejected, or integrity-conflict; never disguise a committed/rejected database result as an ambiguous success.

### PostgreSQL
- PostgreSQL records the canonical accepted command/envelope and server receipt atomically.
- Enforce uniqueness for operation identity in the appropriate tenant scope and verify same-ID/same-digest duplicates versus same-ID/different-digest conflicts.
- Validate business/branch/membership/device ownership from server-side records, not only client payload claims.
- Use explicit grants and safe SECURITY DEFINER functions (fixed search_path, minimal privileges, explicit checks).
- Keep canonical financial history immutable; corrections/voids/refunds are new linked lifecycle events, not destructive updates to prior evidence.
- Use stable pagination: `(received_at, operation_id)` or another immutable unique composite cursor. A timestamp-only cursor can skip rows with equal timestamps.
- Do not change public/private schema boundaries or RLS policies in bulk without a function-by-function access review.

### Inbound sync and notifications
- Fetch committed records through authorized, bounded catch-up queries using a durable cursor.
- Realtime only wakes the sync worker. Always catch up from the last committed cursor on app start, network restoration, session restoration, and notification.
- Materialize only explicitly supported operations. Apply operation and all related local records atomically and idempotently.
- Advance cursor only after successful application or a durable, auditable terminal disposition. Never skip an unsupported operation silently.
- Unknown schema/type: preserve the payload/cursor context, mark needs-attention, and avoid corrupting local financial state.

## 5. Canonical command and receipt contract

Each command must include or derive server-validated values for:
- `operation_id` (stable existing operation ID)
- `schema_version`
- `business_id`, `branch_id`
- authenticated `actor_membership_id` and authorized `device_id`
- `operation_type`
- device `occurred_at` and server `received_at` (separate)
- business timezone and business-local date with validation
- canonical payload/text and SHA-256 digest
- links to related original operations for refunds, voids, corrections, repayments, and lifecycle actions

Receipt outcomes:
- `ACKNOWLEDGED`: operation ID and digest match server's committed canonical record; include duplicate indicator and server receipt metadata.
- `RETRYABLE`: no durable acceptance result; retry same ID and same bytes.
- `AUTH_REQUIRED`: refresh/re-authenticate; keep command pending.
- `REJECTED`: deterministic validation/permission/schema failure; preserve locally and surface a recoverable reason.
- `INTEGRITY_CONFLICT`: operation ID exists with a different digest; stop automatic retries and require investigation.
- `NEEDS_ATTENTION`: server accepted/rejected status cannot safely be resolved automatically; preserve evidence.

The protocol must explicitly define canonical JSON normalization and byte-for-byte hashing. Do not independently reserialize payloads on retries in a way that changes the digest.

## 6. Operation coverage matrix (implementation must prove each row)

| Operation family | Outbound | Server validation | Inbound apply | Required invariant |
|---|---|---|---|---|
| Cash sale | Verify/enforce | Required | Required | One sale and matching cash settlement |
| Online/card sale | Verify/enforce | Required | Required | Correct channel/category and settlement totals |
| Credit sale | Verify/enforce | Required | Required | Customer debt and sale linkage preserved |
| Credit repayment | Verify/enforce | Required | Required | Allocation/order and outstanding balance preserved |
| Refund/return | Verify/enforce | Required | Required | Linked to original sale; compensation, not a new sale |
| Void | Verify/enforce | Required | Required | Original operation retained; void linkage idempotent |
| Amount correction | Verify/enforce | Required | Required | Original evidence retained; signed delta correct |
| Payment method change | Verify/enforce | Required | Required | Before/after payment evidence and balance consistent |
| Expense/correction/void | Verify/enforce | Required | Required | Expense and cash effects reconcile |
| Cash shift open/count/close | Verify/enforce | Required | Required | Cash accountability and business-day scope reconcile |
| Coupon purchase/top-up/redemption/refund | Verify/enforce | Required | Required | Wallet/ledger identity and balances are authoritative |
| Invoice/report projections | Derive from canonical data | Scope required | Rebuild/query | No independent duplicated financial authority |

“Verify/enforce” is not assumed complete. Each row requires source tracing, unit/contract tests, and reconciliation evidence before being marked done.

## 7. Ordered execution phases and gates

### Phase 0 — Read-only baseline (in progress)
- Capture branch/commit and relevant source paths.
- Inventory entities, DAOs, Room schema version/migrations, enqueue points, operation enum/types, session/auth routes, RPC signatures/grants, existing envelopes and current row counts.
- Record build/test commands and available device-test constraints.
- No data mutation.

**Exit gate:** every operation type mapped from local command through outbox, network, RPC, server receipt, and inbound materializer; unknown paths listed explicitly.

### Phase 1 — Contract and threat model
- Write one contract spec for request/receipt/canonicalization, auth scope, idempotency, error classification, pagination, and clock-skew policy.
- Review every SECURITY DEFINER function's owner, search_path, ACL, tenant check, and expected caller.
- Resolve the anon `preview_staff_invite` finding and authenticated RPC findings individually only after client usage is mapped; do not blanket revoke.
- Define recovery and audit evidence for rejected/conflicting commands.

**Exit gate:** reviewed API/database contract plus negative security tests.

### Phase 2 — Server intake parity
- Consolidate admin/cashier paths behind the same validation and receipt semantics.
- Keep route-specific auth adapters if required, but pass a normalized authenticated actor context into the shared intake layer.
- Test duplicate matching digest, duplicate conflicting digest, wrong tenant/branch, inactive/revoked membership, expired session, unsupported schema, malformed timestamps, bad digest, and transactional rollback.
- Preserve current endpoint compatibility until app rollout is verified.

**Exit gate:** contract tests pass; no production deployment.

### Phase 3 — Durable offline outbox
- Trace every financial command commit path and ensure operation + outbox enqueue are atomic.
- Add/adjust DAO transitions and recovery for stale in-flight commands only with forward migration and migration tests.
- Expand operation support only after envelope payloads for each type are proven complete.
- Keep permanent rejection and integrity conflict distinct from retryable network errors.

**Exit gate:** offline/restart/retry/idempotency tests pass for each enabled type.

### Phase 4 — Inbound materialization and durable cursor
- Define per-operation materializers and transaction boundaries.
- Ensure duplicate inbound operation is a no-op only if canonical identity/digest agrees; mismatches are conflicts.
- Persist cursor durably and only after a successful apply/recorded disposition.
- Add bounded pagination, equal-timestamp tests, interruption recovery, and unsupported-schema behavior.
- Keep feature flag off until all gate tests pass; then enable only in a staging/test build.

**Exit gate:** full supported-operation matrix passes; no silent cursor advancement.

### Phase 5 — Existing-data reconciliation
- Read-only export/aggregate of current envelopes and relevant local datasets.
- Compare operation IDs, digests, business/branch, operation type, timestamps, and financial evidence.
- Produce a discrepancy report. Do not automatically backfill or rewrite financial rows.
- Any backfill is a separate reviewed, idempotent migration with a dry-run and row-count/hash reconciliation.

**Exit gate:** no unexplained discrepancies; approved mapping for legacy rows.

### Phase 6 — Security and performance
- Re-run Supabase advisors.
- Review every exposed SECURITY DEFINER function and grant.
- Add the two FK indexes only through a reviewed migration if still needed and appropriate.
- Verify no service-role secret or pepper in APK, logs, repo, or public configuration.
- Verify logs redact PINs, bearer tokens, invite tokens, customer data, and canonical payloads.

**Exit gate:** security findings closed or documented with justified exception and negative tests.

### Phase 7 — Device and recovery verification
- Use the existing admin Motorola and cashier Pixel, same authorized business/branch.
- Test offline sale, process kill, app restart, reconnect, duplicate retry, session expiry/relogin, simultaneous uploads, cross-device download, and exact-once visibility.
- Exercise all enabled operation families without creating/deleting production test transactions; use isolated test fixtures or a dedicated non-production project for synthetic cases.
- Reconcile sale count, amount totals, payment channel totals, credit balances, cash-shift totals, and operation links.

**Exit gate:** test evidence and reconciliation report pass.

### Phase 8 — Release and retirement
- Publish a candidate APK from the working branch and document exact commit/build/test results.
- Keep old routes available until rollout confirms no pending operation is stranded.
- Provide rollback procedure that disables new sync behavior without deleting local outbox or cloud history.
- Merge/deploy only after build, unit/contract/integration/device tests and explicit release gate. Production database migration/deployment is deliberately outside this plan-only commit.

## 8. Test matrix

Required automated tests:
- canonical payload encoding stable across retry and app version;
- one local transaction writes operation + outbox atomically;
- app/process death leaves command retryable;
- duplicate server request with same digest returns the original receipt;
- same operation ID with different digest becomes integrity conflict;
- no acknowledgement mismatch can clear the outbox;
- auth expiry and membership revocation preserve pending command without cross-account upload;
- pagination with equal timestamps neither skips nor repeats unapplied rows;
- inbound apply and cursor checkpoint are atomic or recoverable;
- each supported operation applies atomically and idempotently;
- business/branch isolation for every upload and download path;
- prior Room schema migration retains operations and pending commands;
- financial reconciliation after cross-device synchronization matches server canonical records.

Required manual/device tests:
- offline create → force-stop → reopen → online → one server record;
- one device uploads → second device retrieves once;
- upload accepted but response lost → retry produces no duplicate;
- two devices submit concurrently;
- session expires while offline;
- unsupported operation/schema is held for attention and does not advance cursor silently.

## 9. Rollback and safety

- No destructive Room migration.
- No SQL `DROP`, truncation, data reset, or rewrite of financial history.
- Preserve existing endpoint and schema compatibility during transition.
- Keep inbound feature flag off until the operation matrix and device reconciliation pass.
- If sync fails, retain local operation/outbox and surface status; do not fall back to silent data loss.
- Production deployment and branch merge remain a separate release gate, even though development work is authorized.

## 10. Current execution log

| Step | Status | Evidence |
|---|---|---|
| Source baseline identified | DONE | Source commit `6a45e5b04ec975df4142428cd86ef0b83d5724e6` |
| Working branch isolated | DONE | `migration/offline-first-supabase-v1` |
| Read-only source/cloud audit | IN PROGRESS | Source tree, core sync files, RPC inventory, table schema, advisors reviewed; audit matrix records the atomic Room outbox trigger and current cash-sale-only contract |
| Complete operation path map | IN PROGRESS | Confirmed Room trigger atomically creates outbox metadata when an operation transitions to SUCCEEDED; current contract/tests intend cash-only sales only, so expansion to other operation types remains gated |
| Contract implementation | OPEN | No wire-contract changes yet |
| Outbox/inbound implementation | OPEN | No changes yet |
| Automated verification | IN PROGRESS | Deno Edge Function tests passed in GitHub Actions; Android jobs currently cannot install stable `platforms;android-37` in the hosted SDK repository. Added an API 36 compatibility validation workflow that temporarily substitutes SDK values in the runner workspace only; results pending |\n| Cash-only outbox trigger alignment | IMPLEMENTED, verification pending | Updated `FinancialImmutabilityCallback` trigger to queue only `RECORD_SALE` operations with cash-only `SALE_FUNDING` settlements and aligned trigger name with existing migration tests; production database untouched |\n| Physical-device verification | OPEN | Offline/reconnect and cross-device reconciliation not yet run |
| Production migration/deployment | NOT AUTHORIZED BY THIS PLAN | Requires release gate and independent verification |

## 11. Definition of done

Migration is complete only when all supported financial operation types pass contract and materialization tests; offline commands survive restart; retry cannot duplicate or alter financial outcomes; both devices converge; existing cloud/local history reconciles; auth and tenant isolation pass negative tests; build and test results are reproducible; and the final release report lists commit IDs, changed files, exact tests/results, unresolved risks, and rollout/rollback status.

A green compile alone is not completion.
