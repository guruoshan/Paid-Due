# Production migration reconciliation plan — 2026-11-01

Status: planning only. This document makes no production or migration-history changes. No migration file was renamed, deleted, re-dated, applied, or modified.

Repository: `guruoshan/Paid-Due`  
Base: `main` at `abb25c66ddc294df1d6562a8d61c1cbeae28a5c9`  
Supabase project: `ttvdpgxqvvtjnxxfkoui`  
Evidence refreshed: 2026-11-01 using read-only `list_migrations` and read-only catalog/definition SELECTs.

## 1. Live versus main migration history

The live migration list was retrieved again on 2026-11-01. The entries below match section 2 of `docs/audits/2026-10-31-pr-disposition.md`. **No change detected** in the same-name/different-version, live-only, or main-only sets.

### Same migration name, different version

| Migration | Live version(s) | Main version |
|---|---|---|
| reactivate_removed_cashier_identity | 20261015185018, 20261015185332, 20261015185405 | 20261015203000 |
| allow_reinvite_removed_cashier_same_branch | 20261016125950 | 20261016150000 |
| allow_reinvite_same_local_user_activation_history | 20261016131840 | 20261016132000 |
| retire_cashier_feature | 20261023044205 | 20261023050000 |
| remove_staff_auth_rate_limits | 20261023045447 | 20261023060000 |
| remove_cashier_customer_sync_rpcs | 20261023045544 | 20261023070000 |
| purge_cashier_notifications | 20261023050312 | 20261023080000 |
| restore_historical_actor_user_ids | 20261023052001 | 20261023100000 |
| repair_admin_customer_directory_sync_after_rpc_retirement | 20261024181559 | 20261024190000 |

### Applied live, absent from main

- `20261018140123_add_financial_reporting_inventory_rpc`
- `20261024174802_capture_financial_operation_collisions`

### Present on main, absent from live

- `20261027175000_reject_customer_directory_identity_conflicts`
- `20261027190000_customer_directory_keyset_pagination`
- `20261027193000_financial_envelope_metadata_consistency`
- `20261029010000_enforce_admin_only_role`

The nine main-side migration files for the version mismatches and the four main-only files were checked on current main. The live list also includes both live-only entries above.

## 2. PR #72 — collision capture migration

PR #72 proposes:

`supabase/migrations/20261024180000_capture_financial_operation_collisions.sql`

Live migration history contains:

`20261024174802_capture_financial_operation_collisions`

The PR migration creates `private.collision_audit` and replaces `public.ingest_financial_envelope`. The live catalog confirms that the collision-audit table exists with the expected 15 columns, including incoming/stored digests, canonical text, payloads and field diff; the operation/time index exists; RLS is enabled; and no policies were returned for that table.

A read-only `pg_get_functiondef` snapshot of live `public.ingest_financial_envelope` confirms the collision capture path and additionally contains `ENVELOPE_METADATA_MISMATCH` checks for branch, device, operation type, command schema version and occurred-at metadata. Those checks are absent from the PR #72 function body. This is evidence that the PR copy is older/less strict than the live definition; it is not evidence of a live-side defect.

### Decision options

1. **Adopt live as-is (recommended):** record the already-applied migration on main under its actual live version, `20261024174802_capture_financial_operation_collisions.sql`, after approval and exact content reconciliation. Do not replay it against production.
2. Replace live history with the PR file: this would require an explicitly approved production-history correction and would risk losing the additional metadata-consistency checks currently present in the live function. Not recommended on current evidence.

Before choosing option 1, retain/export the exact live migration source if available, compare it byte-for-byte with the PR migration and a complete `pg_get_functiondef` snapshot, and verify the table definition, indexes, constraints, RLS state, grants and function grants. The current read-only evidence supports the live version as the source of truth, but does not substitute for an authoritative exported migration artifact.

## 3. Reporting inventory RPC evidence

The live list contains `20261018140123_add_financial_reporting_inventory_rpc`; main does not contain that migration. A read-only `pg_get_functiondef` snapshot confirms live `public.fetch_financial_reporting_inventory_v1` with this identity signature:

`(text, text[], timestamptz, timestamptz, text, timestamptz, text, integer)`

It returns operation identity, business/branch/type, occurred/received timestamps, canonical text, canonical payload and canonical SHA-256. It validates authenticated Admin membership for every requested branch, validates the half-open time scope and pagination cursor, and orders pages by received timestamp and operation ID. The client split must call this existing live RPC with the exact signature and returned-field contract. No new RPC migration is proposed by this plan.

## 4. Human-approved reconciliation sequence

No step below is authorized to run automatically. Approval must be explicit for each production-history-changing step.

1. Export the live migration history and obtain the authoritative SQL source for every applied migration, including the duplicate-version entries. Preserve checksums and timestamps. This is an evidence-gathering step only; do not mutate production.
2. Export read-only `pg_get_functiondef` snapshots for every affected function, plus table/index/constraint/RLS/grant definitions. Store the snapshots with the audit evidence.
3. Compare live definitions against every candidate main migration. Classify each migration as identical, equivalent with formatting differences, live-ahead, main-only, or unresolved. Record one source of truth per migration.
4. **HUMAN GATE — approve source of truth:** a human owner must approve the reconciliation matrix, especially the nine version mismatches, the four main-only migrations, and both live-only migrations. No production history changes before this approval.
5. **HUMAN GATE — approve repository history edits:** after approval, prepare a separate reviewed PR that records already-applied migrations on main using their approved live versions and exact authoritative contents. Renaming migration files is allowed only after this approval. Do not delete, overwrite, or re-date files as a shortcut.
6. **HUMAN GATE — approve production-history action:** before any production history change, the human owner must explicitly approve the exact operation, target migration versions, expected live state and rollback/recovery plan. This plan does not authorize applying migrations, DDL, DML, deployment, or migration-history edits in production.
7. After any separately approved repository-only reconciliation PR, re-run a read-only migration-list comparison and verify that repository filenames map unambiguously to the recorded live history. Any future production apply/deploy requires its own explicit authorization and is outside this plan.

## 5. Explicit non-actions

- PR #8 and PR #72 remain open and unchanged.
- No migration was renamed, deleted, re-dated, applied or edited.
- No SQL writes, DDL, DML, deployment, Edge Function changes or production-history mutations were performed.
- No branches were deleted.
