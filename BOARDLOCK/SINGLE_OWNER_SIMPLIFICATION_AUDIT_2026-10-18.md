# Paid & Due — Single-Owner Simplification Audit

**Repository:** `guruoshan/Paid-Due`  
**Audited ref:** `main` at `e20f484c38284bd0e42d8b30bfc8e4cbeaed055e`  
**Audit branch:** `simplify/single-owner-login-audit-2026-10`  
**Audit date:** 2026-10-18  
**Scope:** Static repository audit of Android source, Supabase migrations/Edge Functions, existing architecture/audit records, and test inventory. No production data/schema/functions were changed during this audit.  
**Important limitation:** This is source-level evidence. Live production catalog and two-device runtime behavior must be separately verified before destructive cleanup or release.

## Executive conclusion

The app already has an Admin/owner authentication and financial-sync path, but cashier concepts are not isolated to a removable feature. They are wired into local session restoration, financial mutation authorization, sync delivery policy, sync scheduling, remote envelope actor validation, reporting attribution, Supabase business membership rows, and server RPCs. Removing the cashier code by deleting tables/functions or stripping actor IDs would risk locking out existing data, breaking historical financial attribution, or stopping sync.

**Safe target:** make the owner account the only supported interactive login and the only new transaction actor, while preserving existing customer/transaction rows and immutable actor/membership IDs in history. Remove cashier UI and invitation workflows from the active product. Retain legacy database objects and compatibility reads initially; retire them only after production references and historical evidence are proven unnecessary. Do not delete/rewrite existing financial rows or reuse old actor IDs.

## 1. Backend audit

### Auth flow

| Component | Evidence/path | Current behavior | Dependency / risk |
|---|---|---|---|
| Owner/Admin auth | `app/src/main/java/com/dailymoneyregister/feature/auth/data/AuthRepository.kt`, `RemoteAdminAuthRepository.kt`, `RemoteAdminBootstrapRepository.kt` | Local auth/session setup includes cloud business restoration and Admin auth support | **High**: preserve existing owner credentials, business association, trusted sessions, and account recovery |
| Staff/Cashier auth | `supabase/functions/api/handlers.ts`; `supabase/functions/staff-activate/index.ts`, `staff-invite/index.ts`, `staff-open/index.ts`, `staff-preview/index.ts`; `CashierCloudSession.kt` | Custom phone/PIN invite activation, login, and custom sync token/session | **High**: separate Edge route and credential lifecycle |
| Deep-link activation | `app/src/main/java/com/dailymoneyregister/app/MainActivity.kt`; `feature/invite/**` | Reads invite link and activates staff invite | **Medium**: safe to remove from active navigation after staff login is no longer supported; keep URL behavior backward-compatible until rollout is complete |

### Supabase data model and objects

| Object family | Evidence | Depends on cashier? | Disposition / risk |
|---|---|---|---|
| `public.businesses`, `public.branches`, `public.business_memberships` | migrations; staged SQL; owner and cashier RPCs | Membership role and actor IDs are used by both owner and cashier flows | **Critical — preserve**. Existing business/membership IDs may be referenced by financial envelopes and audit records |
| `private.staff_identities`, staff PIN credentials | staff invite/login and restoration migrations; `handlers.ts` | Yes | **High — deprecate, don't drop yet**. Historical membership/actor attribution and recovery paths may reference these rows |
| `private.staff_invites`, `private.staff_invite_activations`, `private.cashier_sync_sessions`, `private.auth_rate_limits` | migrations from 2026-10-10 onward; staff Edge handlers | Yes | **Medium/High — stop creating new records after client rollout; preserve existing records until retention/reference audit** |
| `private.cashier_permission_requests`, `private.staff_admin_audit_events`, business notifications | migrations `20261010100412...`, `20261011230317...`, `20261012212829...`, `20261013230803...` | Yes, except general business notifications | **Medium — UI/API deprecate first; preserve audit history** |
| `private.financial_operation_envelopes` | `BOARDLOCK/staged_sql/financial_operation_envelopes.sql`; financial ingest/fetch migrations | Actor membership IDs are immutable evidence; admin and cashier ingest/fetch contracts differ | **Critical — preserve**. Do not drop actor fields or rewrite old envelopes |
| Owner financial RPCs | `public.ingest_financial_envelope`, `public.fetch_financial_envelopes_v2` and versioned variants | Owner/Admin membership validation | **Critical — preserve and make the only active path for new owner-device writes** |
| Cashier financial RPCs | `public.ingest_cashier_financial_envelope`, `public.fetch_cashier_financial_envelopes` and v2 variants | Custom cashier session and active membership checks | **High — retire only after owner-only release and historical reconciliation** |
| Customer directory RPCs | migrations `20261012212934...`, `20261012212941...`, `20261013230803...` | Some paths enforce cashier membership/permission; data itself is shared business data | **High — preserve shared customer data; replace staff-specific authorization with owner business ownership only after exact RPC contract audit** |
| RLS, triggers, views, functions | migration history, staged SQL and security hardening | Mixed: some owner-owned, some staff-scoped, some shared | **Critical — no blanket revoke/drop**. Trace each function signature, grant, trigger caller, and dependent view before change |

### Edge API

- `supabase/functions/api/index.ts`, `router_test.ts`, `handlers.ts`, `supabase.ts` implement a combined API with custom staff login/activation/permission-request/financial upload-download routes.
- `staff-invite`, `staff-preview`, `staff-activate`, and `staff-open` are invitation lifecycle endpoints.
- These can be removed from the product's active route/UI after clients stop calling them. Deleting endpoints immediately is unsafe while installed older APKs may still call them.
- Never embed Supabase service-role credentials in Android; the existing Edge service-role boundary must remain server-side.

## 2. Android audit

### Active dependency map

```text
MainActivity.kt
  ├─ DmrApp.kt navigation
  │   ├─ LoginScreen / LoginViewModel / AuthRepository
  │   ├─ InviteActivateScreen / InviteActivateViewModel
  │   └─ CashierManagementScreen / CashierManagementViewModel
  ├─ SessionManager / TrustedSessionStore / TrustedSessionRestorer
  │   └─ UserRole.ADMIN vs UserRole.CASHIER + local branch membership
  └─ FinancialSyncScheduler
      ├─ ADMIN -> RemoteFinancialSyncRepository / Supabase RPC
      └─ CASHIER -> CashierCloudSession / custom Edge API

Register / Customers / History / Invoice / Reporting repositories
  └─ FinancialMutationAuthorizer / PermissionChecker
      └─ role + branch membership + per-action permission bits

FinancialSyncEngine
  ├─ FinancialSyncOperationPolicy (role-specific upload allowlists)
  ├─ FinancialSyncOutboxPolicy / Room outbox
  ├─ RemoteFinancialSyncRepository (owner/Admin cloud path)
  ├─ CashierCloudSession (cashier cloud path)
  ├─ StaffIdentitySync / CashierMetadataSynchronizer
  └─ InboundEnvelopeApplier / RemoteEnvelopeMaterializer
      └─ actor membership/identity validation and immutable operation evidence
```

### Key files and impact

| File/path | Current responsibility | Single-owner change | Risk |
|---|---|---|---|
| `app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt` | Navigation routes for cashier management and invite activation | Remove active destinations and links; preserve core screens | Medium |
| `app/src/main/java/com/dailymoneyregister/app/MainActivity.kt` | Invite deep-link handling, startup recovery, sync scheduler | Remove invite activation entry path after verifying no required owner deep links | Medium |
| `feature/admin/ui/CashierManagementScreen.kt`, `CashierManagementViewModel.kt` | Cashier management UI | Remove from active app navigation; retain until reference audit passes | Low/Medium |
| `feature/admin/data/CashierManagementRepository.kt`, `CashierLifecycleRepository.kt`, `CreateCashierPayloadCodec.kt`, `StaffLifecyclePayloadCodec.kt` | Invite/create/edit/remove cashier calls and payloads | Deprecate after all callers removed; tests must prove no active route invokes them | Medium |
| `feature/invite/**` | Invite link activation | Remove active route and screen entry; preserve compatibility source until rollout | Medium |
| `core/security/PermissionChecker.kt`, `feature/register/data/FinancialMutationAuthorizer.kt` | Per-role/action gating | Make owner session the sole active principal, but keep tenant/business boundary checks; never simply return true for every user | **Critical** |
| `core/security/TrustedSessionRestorer.kt`, `SessionManager`, `AuthRepository.kt` | Restore owner and cashier local sessions | Owner-only session restore; do not delete local database or trusted owner sessions | **Critical** |
| `core/cloud/sync/FinancialSyncScheduler.kt`, `FinancialSyncEngine.kt` | Branches between admin and cashier sync paths | New owner-device sessions use only owner path; legacy cashier sessions fail closed and show upgrade/sign-in guidance | **Critical** |
| `core/cloud/sync/FinancialSyncOperationPolicy.kt`, `FinancialSyncOutboxPolicy.kt` | Role-specific outbox admission | Remove role fork only after supported operation matrix and backend RPC parity tests pass | **Critical** |
| `RemoteFinancialSyncRepository.kt`, `CashierCloudSession.kt` | Different upload/download contracts | Keep owner repository; deprecate custom cashier transport only after inbound history is reconciled | **Critical** |
| `StaffIdentitySync.kt`, `DefaultStaffIdentitySync.kt`, `CashierMetadataSynchronizer.kt`, `CloudStaffIdentityEntity.kt`, `CloudStaffIdentityDao.kt` | Sync staff metadata for actor validation and permission refresh | Stop staff metadata refresh for new owner sessions; preserve historical actor resolution needed by old envelopes/reports | **High** |
| `RemoteEnvelopeMaterializer.kt`, `InboundEnvelopeApplier.kt`, `FinancialEnvelopeCodec.kt` | Validate and materialize inbound financial operations | Preserve actor metadata as immutable history; support legacy envelopes while new events use owner actor | **Critical** |
| `feature/reporting/**`, `ReportingReadDao.kt` | Reports and actor attribution | Remove cashier-filter UI only after verifying general reports do not rely on actor membership joins | High |
| `app/src/main/java/com/dailymoneyregister/feature/customers/**` | Shared customer directory and customer financial history | Preserve business/customer IDs and sync; eliminate staff permission gate without weakening business isolation | **Critical** |

## 3. Financial integrity and sync audit

Existing source documents `BOARDLOCK/SUPABASE_CONNECTION_AUDIT_MATRIX.md`, `BOARDLOCK/BACKEND_COMPATIBILITY_AUDIT_2026-10-09.md`, `BOARDLOCK/PRODUCTION_READINESS_IMPLEMENTATION_2026-10-14.md`, and `BOARDLOCK/INBOUND_EXPENSE_AND_CASHIER_REPORTING_REPAIR_2026-10-16.md` record these non-closed concerns:

1. Owner/Admin and cashier upload/download are separate contracts.
2. `FinancialSyncOperationPolicy` has role-dependent operation allowlists.
3. Inbound application/materialization is incomplete or gated for some operation families; cashier simplification does not fix this by itself.
4. Local outbox is connected to successful operation transitions through Room trigger logic; changing actor/session role must not bypass or double-trigger it.
5. Envelope digest/idempotency and tuple cursor behavior are part of the financial contract.
6. Inbound actor validation and report attribution rely on immutable operation actor IDs and/or synced staff identity mappings.
7. Offline, crash/restart, lost-response retry, and two-physical-device proof are release gates, not proven by unit tests alone.

**Mandatory rule:** a new owner actor may be used for new entries, but old actor IDs and historical financial events are never rewritten to the owner ID. Corrections/voids remain append-only and idempotent.

## 4. Database migration strategy

No destructive database migration is approved by this audit.

### Safe phases

1. **Compatibility baseline:** read-only inventory of production table/view/function/trigger/policy definitions and row counts; snapshot migration ledger and financial-envelope references. No writes.
2. **Additive owner-only compatibility migration:** only after function-by-function comparison, add/enable owner-only path behavior or explicit feature flags; do not rename/drop legacy columns/tables; preserve business IDs, customer IDs, memberships, operation IDs, envelope payloads and checksums.
3. **Client transition:** new APK hides staff/invitation workflows and uses the existing owner authentication and owner sync path on every device. Existing staff endpoints remain available for older installed APKs during transition.
4. **Read-only legacy phase:** stop new invite creation through product UI, monitor calls, retain historic staff rows and audit events.
5. **Removal gate:** only after logs/callers/production schema prove no active dependency and retention approval exists, propose separate migration to revoke unused entrypoints. Do not drop tables or delete staff identities in this project.
6. **Rollback:** rollback by restoring prior app release/feature flag and keeping all legacy DB objects; no reverse migration that deletes or rewrites financial data.

### Never do

- Do not apply or revive `supabase/archive/production-only-migrations/20261012104437_authorized_production_data_reset_20261012.sql`; it contains DELETE statements against memberships, staff identities and related records and is historical archive only.
- Do not DROP staff tables/functions/policies in the first release.
- Do not change a historical envelope's actor membership, canonical payload, digest, operation ID, or business/branch IDs.
- Do not disable RLS or expose service-role credentials to Android.
- Do not reset Room databases or remove migration schemas.

## 5. Required tests

- Android: owner login and trusted-session restoration on two clean devices and after app restart.
- Android: no active navigation entry for invite activation/cashier management; all main register/customer/history/reporting screens remain reachable.
- Android: financial mutations continue to require a valid authenticated owner/business session.
- Sync: owner device A sale/payment/refund/expense and supported cash-shift events upload once and materialize on device B; verify database row counts and canonical digests.
- Sync: retry same idempotency key after simulated timeout and app restart; no duplicate operation/settlement/customer.
- Sync: concurrent writes from two devices remain consistent and each committed event appears once.
- Sync: old envelopes with historic staff actor IDs still validate/materialize without rewriting actor IDs.
- Database: cross-business reads/writes denied; owner cannot post for a customer belonging to another business.
- Database: append-only financial evidence; correction/void append compensating entries.
- Regression: customer directory, customer balances, ledger/history, reports and WhatsApp statements remain intact.
- Run Android unit tests, Room migration/instrumentation tests, Supabase SQL behavioral tests, Deno API tests, release build, then physical-device test.

## 6. Current audit status

- [x] Repository/main ref and source tree inspected.
- [x] Android auth, role, sync, invite, permission, customer, reporting dependency families identified.
- [x] Supabase migrations, Edge route families, owner/cashier financial RPC split identified.
- [x] Existing sync/financial-integrity findings reconciled with current main docs.
- [x] Destructive migration risk identified.
- [ ] Live production object/reference inventory and data counts refreshed for this exact change.
- [ ] Full source reference scan for all cashier-specific symbols and navigation routes completed.
- [ ] Owner-only implementation, tests, build and physical-device verification completed.
- [ ] Production migration/deployment (not authorized by this audit alone).

**Verdict:** proceed with an additive, branch-only owner-login transition. Keep main and production untouched until tests and sync evidence pass. Legacy database objects are not safe to delete yet.
