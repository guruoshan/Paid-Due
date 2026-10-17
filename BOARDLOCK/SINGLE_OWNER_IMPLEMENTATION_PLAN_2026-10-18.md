# Paid & Due — Single-Owner Implementation Plan

**Base:** `main@e20f484c38284bd0e42d8b30bfc8e4cbeaed055e`  
**Branch:** `simplify/single-owner-login-audit-2026-10`  
**Status:** Plan prepared from the audit. Implementation must remain additive and branch-only. No production migration/deployment or main-branch mutation is part of this plan.

## Target behavior

- One authenticated business owner account is the only supported login.
- The same owner account can sign in on multiple devices; each device uses the existing business ID and branch scope.
- Existing customers, local Room database, financial operations, outbox, cloud envelopes, operation IDs, and historic actor IDs remain intact.
- New transactions are attributed to the authenticated owner actor; historical actor fields are never rewritten.
- Existing sync correctness requirements remain unchanged: idempotency, canonical digest checks, retry safety, atomic local writes, and fail-closed tenant isolation.
- Cashier management, invitation activation, staff permission requests, and cashier-specific report filters are removed from active user-facing navigation. Legacy server objects are not dropped in this release.

## Change-by-change plan

| File / area | Current behavior | New behavior | Why | Risk | Required tests |
|---|---|---|---|---|---|
| `app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt` | Routes include signup role selection, cashier hub/management, invite activation, notification permission request | Owner-only entry flow; no active cashier/invite/permission-request destinations; retain customer/register/history/reports/settings | Remove product surface area without rewriting core app | Medium | Navigation graph tests; all core screens remain reachable; invite URI does not bypass login |
| `app/src/main/java/com/dailymoneyregister/app/MainActivity.kt` | Captures invitation token and passes it to app navigation | Stop initiating staff invite activation in owner-only release; preserve normal Supabase auth deep links | Remove staff invite path | Medium | Intent/deep-link tests; Supabase auth callback still works |
| `feature/onboarding/ui/SignUpRoleScreen.kt` and onboarding references | User chooses Admin/Cashier in onboarding | New local business onboarding creates owner/admin only | Prevent creation of new local cashier accounts | Medium | Owner onboarding test; cashier option absent; existing DB not reset |
| `feature/admin/ui/CashierManagementScreen.kt`, `CashierManagementViewModel.kt` | Create/edit/remove cashiers and permission config | No longer reachable in UI; source can be deleted only after compile/reference checks | Remove cashier management from product | Low/Medium | Navigation tests; no active caller; compile |
| `feature/admin/data/CashierManagementRepository.kt`, `CashierLifecycleRepository.kt`, codecs | Creates local staff operations and calls remote staff endpoints | Retain only until reference graph and historical operations are proven irrelevant; then remove in a later isolated commit | Avoid orphaning old operation payload decoders or audit evidence | Medium | Static reference search; tests for legacy decode; no new staff operation emitted |
| `feature/invite/**` | Staff invite parsing/activation and UI | No active product route; code deletion deferred until reference and deep-link scan is clean | Old APK and invite links may still hit existing API | Medium | No invite navigation; old URI safely returns to owner login |
| `core/security/TrustedSessionRestorer.kt`, `AuthRepository.kt`, `LoginViewModel.kt` | Local restore/login accepts ADMIN password and CASHIER PIN paths | New login/onboarding supports owner only; preserve local DB and owner trusted session | One login experience | Critical | Existing owner login/restore; app upgrade preserves owner session; no data clear |
| `core/security/PermissionChecker.kt`, `FinancialMutationAuthorizer.kt`, `ReportingAccess.kt` | Role + membership permissions guard operations | Owner gets the existing full owner policy; every operation still requires active session and matching business/branch; remove cashier-specific branches without global allow-all | Simplify roles without weakening tenant boundary | Critical | Negative tests for absent session, wrong business, wrong branch; owner allowed expected operations |
| `core/cloud/sync/FinancialSyncScheduler.kt` | Branches sync based on ADMIN vs CASHIER | Owner session uses existing owner upload/download path on every device; cashier branch is unreachable in new owner-only client | One sync transport | Critical | Two-device upload/download; legacy envelope receive; scheduler starts/stops correctly |
| `core/cloud/sync/FinancialSyncEngine.kt`, `RemoteFinancialSyncRepository.kt` | Role parameter and separate server route contracts | Keep owner sync path as default and avoid role-based classification for new owner sessions only after server parity tests pass | One cloud contract | Critical | Supported-operation matrix; digest, cursor, replay and duplicate tests |
| `FinancialSyncOperationPolicy.kt`, outbox trigger and policy | Role-dependent outbox allowlist | New owner role maps to the proven owner allowlist; never broaden unsupported inbound operations without materializer support | Prevent transactions stuck unsyncable | Critical | Every operation family classification; Room trigger/outbox integration |
| `CashierCloudSession.kt`, staff metadata synchronizers, `CloudStaffIdentityEntity/Dao` | Custom staff token/session and membership metadata refresh | Not used for new owner sessions; retained until historical inbound actor validation and old-device compatibility are proven | Avoid unnecessary staff identity dependency while preserving history | High | Existing historic actor envelope materialization; no staff network call from owner scheduler |
| `RemoteEnvelopeMaterializer.kt`, envelope codec and operation entities | Validates actor identity and stores immutable financial evidence | New owner envelopes validate against owner identity; legacy envelopes keep historical actor IDs and can materialize through an explicit compatibility resolver | Preserve audit trail | Critical | Legacy actor test; owner actor test; real Room FK integration; exact-once replay |
| `feature/reporting/**` | Cashier-specific report filters/attribution plus general reports | Remove cashier-filter UX; retain all business/branch totals and authoritative operation attribution | Remove cashier-specific reporting only | High | General report totals unchanged; old actor data doesn't affect totals |
| Supabase `staff-invite`, `staff-activate`, `staff-open`, staff routes in `api/handlers.ts` | Serve staff lifecycle and cashier sync | Do not deploy endpoint deletion in same release; mark legacy routes deprecated only after old-client telemetry confirms no active use | Avoid breaking installed APKs and recovery | High | Deno route contract; owner APIs still work; usage/telemetry review |
| Supabase migrations | Historical staff/membership tables, role checks, RLS, financial RPCs | First migration additive: owner-only capability/contract or metadata only if proven necessary; no DROP/DELETE/UPDATE to financial evidence | Preserve production state and rollback | Critical | SQL behavioral tests, disposable full-schema migration, before/after counts and digests |
| CI workflows / tests | Existing tests encode both owner and cashier behavior | Add owner-only release gate; preserve historical compatibility tests until removal gate | Prevent regression | High | Android unit/instrumentation, Deno, SQL, signed build and physical-device test |

## Migration and rollback plan

### Migration 1 — additive compatibility only

1. No table or column drops.
2. No financial row update/delete.
3. No user/membership/staff identity deletion.
4. No alteration of existing canonical payloads, digests, operation IDs, actor IDs, or business/branch IDs.
5. If an owner-only backend contract change is required, use a new versioned RPC or additive function body only after comparing live definitions and caller contracts.
6. Snapshot object definitions, migration ledger, counts, and envelope digest checks before and after.

### App rollout

1. Build owner-only navigation and owner-only session creation on this branch.
2. Keep old server endpoints temporarily so existing installations do not break during staged upgrade.
3. Verify owner login on two devices and shared customer/financial synchronization before any staff endpoint is disabled.
4. Release a signed APK; test upgrade-in-place with existing production app data, not a fresh-install-only test.
5. Observe logs/callers and reconcile all old envelope types before retiring staff endpoints.

### Rollback

- Roll back by reinstalling the prior signed version only if signature/version constraints allow, or release a forward-fix APK.
- Keep schema additions backward compatible and legacy endpoints/tables available.
- Never reverse a migration by deleting financial data or resetting Room.
- If owner sync fails, restore the prior app sync path while preserving outbox and local evidence; do not manually clear queued operations.

## Execution gates

Do not call the migration or release production-ready until all pass:
- Android compile and all unit tests.
- Room migration and instrumentation tests on real SQLite constraints.
- Deno API tests and SQL behavioral tests.
- Owner-only navigation/auth regression tests.
- Idempotency retry test after a lost response and app restart.
- Two physical Android devices on the same owner account: transaction from A appears once on B and vice versa.
- Existing customer count and financial envelope/operation identities remain unchanged.
- Supabase production schema/RLS/functions compared read-only to the proposed migration.
- Signed release APK installs as an upgrade without signature mismatch or local database reset.

## Current implementation boundary

This plan intentionally does not authorize a broad deletion pass. First implementation increment should remove cashier/invite routes from the active app while retaining code needed by historical financial evidence; next increments should migrate auth/sync/authorization in separately testable changes. Production DB object removal is a later, separately approved change after observed caller evidence.
