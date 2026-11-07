# Production migration-history reconciliation — 2026-11-04

Status: **draft evidence only**. This document does not authorize a production migration, migration-history repair, function deployment, or other production mutation.

Repository: `guruoshan/Paid-Due`  
Evidence branch base: `main` at merge commit `dc21e1c62dc558a38b4c9cbf716a24e3868c061f` (PR #156)  
Supabase project: `ttvdpgxqvvtjnxxfkoui`  
Production evidence: read-only Supabase catalog queries, `list_migrations`, `list_edge_functions`, and PostgreSQL logs queried on 2026-11-04.

## Manual production changes, 3–4 November 2026

The following entries document owner-reported manual production actions and read-only catalog/log evidence. They do not authorize replay, migration repair, or production mutation.

### 1. Cashier-retirement guard DDL

- **Action:** Function `private.prevent_new_cashier_memberships()`, trigger `public.trg_prevent_new_cashier_memberships`, and constraint `business_memberships_cashier_retirement_check` applied through the Supabase Dashboard SQL Editor.
- **Execution:** 2026-11-03 23:25:36 UTC, corroborated by the PostgreSQL log.
- **Verification time:** not recorded by owner.
- **Catalog state on 2026-11-04:** all three objects present; the constraint is validated.
- **Body comparison:** function matches the repository definition except for explicit `SECURITY INVOKER` (the PostgreSQL default); trigger definition matches; constraint expression is textually different; constraint behavior tested against a fixture predicate in PR #163; parity with the production predicate not yet proven. The isolated CHECK/trigger behavior contract passed in PR #163's SQL Behavioral Gate run 41802243815.
- **Repository representation:** `supabase/migrations/20261029010000_enforce_admin_only_role.sql` is the intended source representation; its remote migration-ledger status remains a separate reconciliation item. Any history reconciliation must be handled in a separate PR and must not reapply the DDL.

### 2. Five retired cashier/staff tables dropped manually

- **Action:** Dropped `private.cashier_sync_sessions`, `private.staff_invite_activations`, `private.staff_invites`, `private.cashier_permission_requests`, and `private.staff_admin_audit_events`.
- **Time:** not recorded by owner.
- **Preconditions:** owner-reported checks established zero rows and no inbound foreign keys before each drop.
- **Repository representation:** `supabase/migrations/20261023050000_retire_cashier_feature.sql` contains DELETE statements for all five named tables (`cashier_sync_sessions`, `staff_invite_activations`, `staff_invites`, `cashier_permission_requests`, and `staff_admin_audit_events`) and also for `staff_identities`. It does not DROP any of these tables. The manual table-drop operations are represented by a separate migration still to be added; do not replay it against production as part of this closeout.

### 3. RLS enabled on financial operation envelopes

- **Action:** RLS enabled on `private.financial_operation_envelopes`.
- **Time:** not recorded by owner.
- **Catalog state on 2026-11-04:** `relrowsecurity=true`, `relforcerowsecurity=false`; no grants to `anon`, `authenticated`, or `public`.
- **Repository representation:** `BOARDLOCK/staged_sql/financial_operation_envelopes.sql` contains the RLS enablement and revoke statements, but is staged SQL rather than an applied migration. A reviewed migration representation is **to be added in a separate PR**; do not apply it as part of this documentation change.

### 4. PIN pepper secret removed

- **Action:** owner deleted the PIN pepper secret.
- **Time:** not recorded by owner.
- **Secret name:** not recorded in the available owner evidence; no value is recorded here.
- **Repository representation:** secrets are operational project configuration and must not be embedded in a SQL migration. A non-secret inventory/runbook record is **to be added in a separate PR** after the owner supplies the exact secret name.

### 5. Retired staff Edge Functions

- **Action:** `staff-invite`, `staff-activate`, `staff-open`, and `staff-preview` were redeployed with `verify_jwt=true` (versions v15, v9, v13, and v6 respectively), subsequently deleted from production (404 confirmed), and their source removed in PR #159.
- **Time:** exact deployment/deletion times not recorded by owner.
- **Repository representation:** source retirement is represented by PR #159; deployment JWT settings and production deletion are operational Dashboard/API actions, not SQL migration effects. Any durable non-secret operational inventory note is **to be added in a separate PR** if required by the owner.

## 1. Count reconciliation: the earlier 15/13 statement is not reproduced

The latest production migration list and the existing 2026-11-01 reconciliation evidence produce **13 local-only version numbers and 13 remote-only version numbers**, not 15 local-only and 13 remote-only.

The arithmetic is important: nine migration *names* have different local and remote versions; one of those names has three remote version entries, so it contributes three remote-only versions for one local version. Four further local versions have no matching ledger version, and two further remote versions have no local file.

No version has been silently renamed, deleted, re-dated, repaired, or applied as part of this work. The 15/13 count should be treated as an earlier unverified count until its underlying version list is produced.

## 2. Version mapping

| Local version | Remote version (if matched) | Evidence | Decision needed |
|---|---|---|---|
| `20261015203000` — reactivate_removed_cashier_identity | `20261015185018`, `20261015185332`, `20261015185405` | Local migration defines `public.activate_staff_invite`; live catalog no longer has that function, consistent with the later cashier-retirement migration. Historical function body cannot be reconstructed from the current catalog alone. | Obtain authoritative deployed SQL for all three remote entries; decide whether the local file is equivalent, superseded, or must remain a distinct historical artifact. |
| `20261016150000` — allow_reinvite_removed_cashier_same_branch | `20261016125950` | Local migration creates partial unique index `uq_business_memberships_staff_branch`; that index exists in the live catalog. | Compare full index definition/predicate and migration source/checksum before accepting equivalence. |
| `20261016132000` — allow_reinvite_same_local_user_activation_history | `20261016131840` | Local migration drops the activation local-user unique constraint and creates `idx_staff_invite_activations_local_user`; that index exists in the live private schema. | Compare constraint/index definitions and authoritative deployed SQL/checksum. |
| `20261023050000` — retire_cashier_feature | `20261023044205` | The retired staff RPCs are absent from `pg_proc`; the historical staff/notification tables still exist. Current cashier membership count is zero. | Confirm exact historical migration source and destructive row effects from backup/audit evidence; do not infer prior row deletion from current table existence. |
| `20261023060000` — remove_staff_auth_rate_limits | `20261023045447` | `public.check_auth_rate_limit(text,text)` and `private.auth_rate_limits` are absent in the live catalog. | Compare authoritative SQL/checksum and confirm no additional side effects. |
| `20261023070000` — remove_cashier_customer_sync_rpcs | `20261023045544` | Both legacy functions `public.sync_customer_directory_list` and `public.sync_customer_directory_upsert` are absent; current admin RPCs use admin-only signatures. | Compare the exact deployed DROP signatures and grants; retain the historical migration. |
| `20261023080000` — purge_cashier_notifications | `20261023050312` | Local SQL deletes cashier-recipient and retired permission notification types. Current schema still contains the notification table; current table presence cannot prove the historical deletion set. | Obtain the deployed migration SQL and audit/backup evidence for the affected rows. |
| `20261023100000` — restore_historical_actor_user_ids | `20261023052001` | Local SQL restores membership local-user IDs from canonical financial envelope actor evidence. Current historical cashier tombstones remain; exact row-level result requires a separate read-only aggregate comparison. | Compare deployed SQL and validate the affected membership/envelope actor mapping without changing it. |
| `20261024190000` — repair_admin_customer_directory_sync_after_rpc_retirement | `20261024181559` | Live `sync_customer_directory_admin_upsert` and keyset-list functions exist; the upsert has the direct private-table path and identity-conflict failure, while the list has the 5-argument keyset signature. | Compare complete live function definitions, grants, and exact deployed source/checksum with the local migration. |
| `20261027175000` — reject_customer_directory_identity_conflicts | — | No ledger version. Live `sync_customer_directory_admin_upsert` contains `CUSTOMER_IDENTITY_CONFLICT` checks and the explicit matching-retry behavior from this migration. | Determine which deployed SQL/version introduced the live definition; do not mark unapplied merely because its filename is absent from the ledger. |
| `20261027190000` — customer_directory_keyset_pagination | — | No ledger version. Live `sync_customer_directory_admin_list` exists with the 5-argument keyset signature and tuple cursor ordering. | Determine the authoritative deployed source/version and whether the migration should be recorded as already applied. |
| `20261027193000` — financial_envelope_metadata_consistency | — | No ledger version. Live `public.ingest_financial_envelope` contains the branch/device/type/schema-version/occurred-at metadata checks and raises `ENVELOPE_METADATA_MISMATCH`. | Obtain authoritative deployed SQL and compare the complete live function before any history repair. |
| `20261029010000` — enforce_admin_only_role | — | No ledger version. Live guard function, trigger, and validated constraint are present. PostgreSQL logs show the full guard DDL submitted through `supabase/dashboard-query-editor` at 2026-11-03 23:25:36 UTC. This is direct evidence of a manual Dashboard SQL action, not an automatic merge deployment. | Owner must confirm the manual application and approve whether/how to record this already-present effect in migration history. Do not reapply the DDL. |
| — | `20261018140123` — add_financial_reporting_inventory_rpc | — | Live ledger-only version. Read-only catalog confirms `public.fetch_financial_reporting_inventory_v1(text,text[],timestamptz,timestamptz,text,timestamptz,text,integer)` exists. | Export authoritative migration SQL and compare the live function definition, grants, and result contract; add the exact source to repository only after owner approval. |
| — | `20261024174802` — capture_financial_operation_collisions | — | Live ledger-only version. Read-only catalog confirms `private.collision_audit` exists and live `ingest_financial_envelope` includes collision capture plus the metadata mismatch checks. | Obtain exact deployed migration source and compare table columns, indexes, RLS/grants, and complete live function definition. The older PR #72 copy is not a substitute. |

## 3. Production state observed on 2026-11-04 (read-only)

- `business_memberships` cashier rows: **0**.
- `private.prevent_new_cashier_memberships()`: present.
- `public.trg_prevent_new_cashier_memberships`: present.
- `business_memberships_cashier_retirement_check`: present and validated.
- Ledger versions `20261029010000`, `20261027175000`, `20261027190000`, and `20261027193000`: absent from the remote migration ledger.
- `staff-invite`, `staff-activate`, `staff-open`, and `staff-preview`: deployed with `verify_jwt=true` (v15, v9, v13, v6), then deleted from production and removed from `main` (PR #159). Historical state, recorded 4 November 2026.
- `sync_customer_directory_list` and `sync_customer_directory_upsert`: absent from `pg_proc`; no live function definition references either legacy function name.
- The Supabase project migration list still shows the two live-only versions and the nine version-name mismatches above. No migration-history repair was run.

The Dashboard log event for the guard DDL occurred before PR #156 was merged. Its timestamp and source do not support attributing the guard's live presence to a merge-triggered deployment.

## 4. RPC dependency conclusion

The old branch-scoped customer-directory RPCs are absent in production and have no live function callers. Current Android code and Edge API handler use the Admin-only customer-directory API/RPC path. Historical migration definitions and tests still mention the legacy functions, as expected.

Therefore the runbook's **zero-rows RPC check is valid for these two exact retired RPCs**. It must not be generalized to all customer-directory functions: the Admin-only functions remain live and are used.

## 5. Staging availability

**Staging not available** in the inspected Supabase account/project state:

- `list_projects` returned only production project `ttvdpgxqvvtjnxxfkoui`.
- `list_branches` returned only the default `main` branch (`b6a3dc29-16aa-4451-b6da-0277fe3bc976`), with `persistent=false`, `with_data=false`, and status `FUNCTIONS_DEPLOYED`.
- No separate staging project or development branch was returned.
- Repository migrations README states that the migration folder is not a complete fresh-project bootstrap baseline.

No staging branch/project was created.

## 6. Required owner decisions

1. Confirm the authoritative SQL/checksum for each of the nine version-mismatched migrations.
2. Confirm the origin and exact deployed source for the four local versions whose effects are present but whose versions are absent from the live ledger.
3. Export the authoritative source for the two live-only migrations and decide how they should be represented in the repository.
4. Explicitly approve any repository history edit in a separate reviewed PR.
5. Explicitly approve any production migration-history repair or Edge Function deployment as a separate action. No such approval is implied by merging PR #156.

## 7. Prohibited actions / explicit non-actions

- No `supabase db push --linked` was run; it must not be used.
- No `supabase migration repair` was run.
- No migration was applied by this work.
- No Edge Function was deployed.
- No production data, schema, migration history, project setting, or branch was changed by this work.
- No historical migration, staged SQL file, `CloudStaffIdentityEntity`, or `findIdentity()` was deleted.

## Source reconciliation: functions tracked after live application

The files below are **source snapshots only**, not migrations. Each function definition was captured with read-only `pg_get_functiondef` on 2026-11-07; trigger statements (where applicable) are recorded as comments and are not executable. Normalised body hashes are SHA-256 of `prosrc` with whitespace collapsed.

| Live function | Repository source file | Normalised body SHA-256 | Migration ledger status |
|---|---|---|---|
| `private.prevent_envelope_mutation()` | `supabase/source-reconciliation/prevent_envelope_mutation.sql` | `209c0bcec37edf8a6a14fea298748ad16dc705fe1f3834c2726b55fd8bea4afa` | not in migration ledger; applied out of band; to be reconciled by owner |
| `private.enforce_membership_branch_business()` | `supabase/source-reconciliation/enforce_membership_branch_business.sql` | `5e514eec1f9eceffbc60f1bec5d8c1a9bc04f62fa95dbe27c7a58d20093d9671` | not in migration ledger; applied out of band; to be reconciled by owner |
| `private.touch_updated_at()` | `supabase/source-reconciliation/touch_updated_at.sql` | `ea50a08e96e3b58836dd8778cdebc980cf343acd1786dd0a8a561ce3f2288615` | not in migration ledger; applied out of band; to be reconciled by owner |
| `public.fetch_financial_reporting_inventory_v1(...)` | `supabase/source-reconciliation/fetch_financial_reporting_inventory_v1.sql` | `aa5e8c05aa0c505549017622c2949ac87bed9fa062a5664ba6e2a572d43536d8` | live ledger version `20261018140123_add_financial_reporting_inventory_rpc` exists; its migration source file is absent from this repository |
| `public.restore_admin_by_auth_id()` | `supabase/source-reconciliation/restore_admin_by_auth_id.sql` | `bbdad76fb26c6705a9f01e1af617fe0dc0317c18bbd45e3635b8c36fc1ab536c` | not in migration ledger; applied out of band; to be reconciled by owner |

**Important ledger distinction:** the reporting-inventory function is not an out-of-band/no-ledger object. The live migration list contains version `20261018140123`; the gap is that the matching migration source is missing from `main`. The other four functions have no matching function-definition migration entry in the live ledger.

**Existing staged restore source:** `BOARDLOCK/staged_sql/restore_admin_by_auth_id.sql` remains unchanged. It is not equivalent to the live definition: the live version requires exactly one eligible Admin membership and checks active branch/profile plus a nonblank local user ID. The staged version does not enforce those same eligibility checks. The live version also uses SQLSTATE `28000` for missing authentication; the staged version uses `P0401`. The live source snapshot is therefore retained separately rather than treating the staged SQL as an equivalent definition.

These snapshots do not authorize production DDL, migration-history repair, replay, grants, or deployment. Any future migration-history reconciliation requires separate owner approval.


## 8. PR #169 authorization correction — 2026-11-07

This addendum records repository state and production reconciliation status. It does not authorize applying the migration or repairing migration history.

- **Repository change:** PR [#169](https://github.com/guruoshan/Paid-Due/pull/169) merged into `main` at `502b67aac7c33c8d826c2501e14024874db76b5a`.
- **Migration:** `supabase/migrations/20261107120000_fix_removed_admin_membership_authorization.sql` is **merged but not applied to production**. A read-only production migration-ledger check returned no row for version `20261107120000`; `list_migrations` also does not list it.
- **Changed live objects in the migration:**
  1. `public.restore_admin_by_auth_id()`
  2. `public.ingest_financial_envelope(...)`
  3. `public.profiles_self_or_shared_business_select` policy on `public.profiles`
- **Scope expansion:** the original defect report concerned Admin restoration. Review found the same missing `removed_at IS NULL` eligibility predicate in the financial-envelope write authorization lookup and in the shared-business profile-visibility predicate. Those paths were included because a removed-but-still-active membership must not retain write authority or expose another profile. This is a bounded authorization correction; it does not change accounting or collision semantics.
- **Verification status:** SQL Behavioral Gate passed on the PR head, including a negative control that failed when the restore guard was removed. Production application remains pending a separate owner approval and the runbook in [`docs/runbooks/apply-20261107120000.md`](runbooks/apply-20261107120000.md).
- **Guard verification time:** **not recorded by owner**. Repository merge time is not a production verification timestamp.

### Reporting-inventory migration source status

Version `20261018140123` (`add_financial_reporting_inventory_rpc`) is present in the production migration ledger, but its migration source is absent from `main`. A recovered copy from commit `057b2c62dc7ed08ac9ba45293bf9c487c382d464` is being proposed separately. Status: **candidate, not yet restored to main; provenance is not provable from the original submission bytes**. The recovered file is for source-history completeness and must not be replayed against production. See the separate reporting-inventory restore PR when opened.

### Close-out decision references

- Owner decisions and evidence: [`docs/audits/2026-11-07-owner-decisions.md`](audits/2026-11-07-owner-decisions.md).
- Production apply preflight, approval gate, apply-path restriction, post-checks, and rollback plan: [`docs/runbooks/apply-20261107120000.md`](runbooks/apply-20261107120000.md).

No other migration-history entry or historical SQL artifact is changed by this addendum.
