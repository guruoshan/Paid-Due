# Production migration-history reconciliation — 2026-11-04

Status: **draft evidence only**. This document does not authorize a production migration, migration-history repair, function deployment, or other production mutation.

Repository: `guruoshan/Paid-Due`  
Evidence branch base: `main` at merge commit `dc21e1c62dc558a38b4c9cbf716a24e3868c061f` (PR #156)  
Supabase project: `ttvdpgxqvvtjnxxfkoui`  
Production evidence: read-only Supabase catalog queries, `list_migrations`, `list_edge_functions`, and PostgreSQL logs queried on 2026-11-04.

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
