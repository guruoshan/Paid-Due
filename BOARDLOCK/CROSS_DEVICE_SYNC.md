# BOARDLOCK — Cross-device financial synchronization

**Status:** FOUNDATION ONLY — NOT FUNCTIONAL, NOT DEPLOYED.

## Current verified problem
Cashiers on separate devices record financial transactions locally in Room.
Admin reporting reads the Admin device's Room database only.
Remote Supabase identity and Cloudflare employee activation do not synchronize money.

## Financial authority
One original immutable transaction plus append-only authoritative financial
operations, settlements and lifecycle events. No second independent cloud balance,
no per-device reporting totals, no destructive overwrite or ad-hoc merges.

## Required completion stages
1. Inventory all Room financial entities, parent/child dependencies and commands.
2. Define versioned canonical financial envelopes and validate full signed evidence.
3. Implement Room outbox with atomic commit, encrypted persistence, migrations and
   historical migration/backfill safety.
4. Introduce server-side staff session authentication with branch/business
   authorization and device binding. Staff PIN alone is not Admin Auth.
5. Implement server intake with full schema/financial validation, immutable
   idempotent commit, digest conflict protection, tenant checks, replay controls,
   authenticated read endpoints, no direct client writes.
6. Implement upload backoff, acknowledgement verification, reconciliation,
   dependency ordering, partial failure and restart recovery.
7. Build Admin reporting from complete verified server evidence, with branch
   timezone, original-sale and signed-lifecycle period accounting.
8. Prove existing sales, credit/repayment, refunds, voids, corrections, coupons,
   expenses, shifts and invoices retain accounting parity.
9. Run unit/instrumented/migration/security/concurrency and real three-device
   tests before rollout. Keep live production cloud untouched until approval.

## Known blocker
The cloud presently contains identity/membership/invitation tables but no
authoritative financial ledger. The existing Android Room schema is version 12.
Neither Cloudflare Worker deployment nor production Supabase has been modified
by this work.

## Current branch changes
- `FinancialSyncContract.kt`: pure identity and fail-closed acknowledgement guard
- `FinancialSyncAckGuardTest.kt`: focused unit test source (not executed here)
- Staged SQL for **private inert** envelope storage; do not apply until the
  complete ingest/security design is approved.

These files alone DO NOT synchronize transactions or change Admin reports.
