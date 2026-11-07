# Paid&Due — owner decisions and production evidence

**Date:** 2026-11-07  
**Repository baseline:** `main` at `502b67aac7c33c8d826c2501e14024874db76b5a` (PR #169 merge)  
**Supabase project:** `ttvdpgxqvvtjnxxfkoui`  
**Boundary:** evidence is read-only. Every proposed SQL statement below is illustrative and unapplied. No production mutation is authorized by this brief.

## Decision summary

| # | Decision | Current status | Owner action |
|---|---|---|---|
| 1 | Legacy financial-envelope RPC retirement | PR #167 owner-merged; production removal verified in its evidence | Decide whether any watch window is required for old private test clients; no agreed window is recorded |
| 2 | CASHIER-permitting membership constraints | Both role constraints still admit CASHIER; no membership rows currently exist | Approve a separate forward migration to enforce ADMIN-only after preconditions |
| 3 | `private.staff_identities` and membership FK | Table has zero rows; FK still exists | Approve or reject staged retirement after dependency review |
| 4 | Helper EXECUTE grants | Three helpers remain executable by `authenticated` | Decide whether this is intentional for RLS/helper use or should be narrowed |
| 5 | Two supported-but-uncalled public RPCs | Both functions exist; no application caller was found in the current source review | Confirm whether each belongs to the intended public API |
| 6 | Empty production memberships | `business_memberships` has zero rows; no active Admin exists | Confirm whether intentional before relying on cloud sync |
| 7 | Guard verification timestamp | Not recorded by owner | Record actual production verification time after approved apply/verification |

## 1. Legacy RPCs: `fetch_financial_envelopes` and `fetch_financial_envelopes_v2`

**Status: REMOVED by owner-merged PR #167; retained V3 and reporting inventory.**

- PR [#167](https://github.com/guruoshan/Paid-Due/pull/167) merged at `a55d2e43f8adb2999784552eefb0a40f32cc40e1`.
- It added migration `20261107032940_remove_legacy_financial_envelope_fetch_rpcs.sql`, which drops only:
  - `public.fetch_financial_envelopes(text,timestamptz,integer)`
  - `public.fetch_financial_envelopes_v2(text,timestamptz,text,integer)`
- The PR evidence says both signatures were absent after the owner-authorized production apply, with zero `pg_depend` dependency rows and no other database function-body references. Current Android main uses `fetch_financial_envelopes_v3`; V3 and `fetch_financial_reporting_inventory_v1` were retained.
- Historical `pg_stat_statements` counters from the prior read-only audit (statistics reset at **2026-10-07 03:27:16.794962+00**):
  - V1: **1,978 calls**
  - V2: **2,616 calls**
  - V3: **10,046 calls**
- These are cumulative statement counters since reset, not unique devices/users. They provide no last-call timestamp or caller identity. The unified-log review was **NOT RUN**, so no additional client/timestamp attribution is available.
- The app remains private testing only and has not been distributed to external users. Older private test APKs are plausible, not proven, as the source of historical V1/V2 traffic.
- **Watch period:** no post-removal watch-period dates or duration were recorded as an owner decision. Do not invent a completed watch window. If desired, owner should set a dated period and inspect logs for old-client failures; no monitoring result is claimed here.

**What changes this status:** an owner-specified watch period plus dated log evidence, or discovery of a supported client still calling V1/V2. The current source evidence does not show such a caller.

## 2. Membership role constraints still permit CASHIER

**Status: CONFIRMED IN LIVE CATALOG; proposed tightening unapplied.**

Read-only production catalog inspection found:

- `business_memberships_role_check`: `CHECK (role = ANY (ARRAY['ADMIN','CASHIER']))`
- `business_memberships_principal_role_check`: allows either:
  - ADMIN with `auth_user_id IS NOT NULL` and `staff_identity_id IS NULL`, or
  - CASHIER with a staff identity, or a removed/inactive CASHIER tombstone.
- `business_memberships_cashier_retirement_check` also remains; it restricts CASHIER rows to inactive tombstones with no auth/staff identity and all listed permissions false. It does not make the role column ADMIN-only.

Read-only precondition query (run immediately before any future constraint migration):

```sql
SELECT
  count(*) AS total_memberships,
  count(*) FILTER (WHERE role <> 'ADMIN') AS non_admin_rows,
  count(*) FILTER (WHERE role = 'CASHIER') AS cashier_rows,
  count(*) FILTER (
    WHERE role = 'ADMIN'
      AND (auth_user_id IS NULL OR staff_identity_id IS NOT NULL)
  ) AS invalid_admin_principals
FROM public.business_memberships;

SELECT role, is_active, removed_at IS NOT NULL AS has_removed_at,
       auth_user_id IS NOT NULL AS has_auth_user,
       staff_identity_id IS NOT NULL AS has_staff_identity,
       count(*) AS rows
FROM public.business_memberships
GROUP BY role, is_active, removed_at IS NOT NULL,
         auth_user_id IS NOT NULL, staff_identity_id IS NOT NULL
ORDER BY role, is_active;
```

At the close-out audit, the table returned **0 total rows**, so all four counts were zero. This is a current snapshot, not a guarantee that rows cannot be inserted before a future migration.

**Proposed tightened constraint definitions (NOT APPLIED):**

```sql
CHECK (role = 'ADMIN')
CHECK (
  role = 'ADMIN'
  AND auth_user_id IS NOT NULL
  AND staff_identity_id IS NULL
)
```

A reviewed migration should replace `business_memberships_role_check` and `business_memberships_principal_role_check` with the above semantics only after the precondition query returns zero non-ADMIN rows and the code/deployed-function dependency audit is complete. Decide separately whether the now-redundant cashier-retirement CHECK should be removed. Do not edit historical migrations.

**What changes this status:** owner approval and a separately reviewed forward migration with precondition and behavioral tests.

## 3. `private.staff_identities` and its membership foreign key

**Status: TABLE EMPTY; FOREIGN KEY STILL PRESENT; REMOVAL NOT EXECUTED.**

Read-only evidence:
- `private.staff_identities` row count: **0**.
- `public.business_memberships` row count: **0**.
- Foreign key: `public.business_memberships.staff_identity_id` → `private.staff_identities.id`, constraint `business_memberships_staff_identity_id_fkey`.

**Ordered removal plan (all steps unexecuted):**

1. Re-scan current main, migrations, staged SQL, tests, deployed Edge Functions, database functions, triggers, policies, views, and grants for `staff_identities`, `staff_identity_id`, and any staff identity RPC/data contract. Resolve every live dependency first.
2. Re-run read-only counts and dependency inventory immediately before the change. Require zero rows in `private.staff_identities`, zero non-NULL membership references, and no other inbound foreign keys or live dependencies.
3. Obtain explicit owner approval for retiring the table and its remaining schema contract.
4. In one reviewed forward migration, remove the membership foreign key `business_memberships_staff_identity_id_fkey`.
5. Remove `public.business_memberships.staff_identity_id` only after all code, function, trigger, policy, and constraint references have been removed and tested.
6. Drop `private.staff_identities` only after verifying no remaining inbound foreign keys, dependent objects, grants, or required audit/history use.
7. Verify catalog absence, constraints, and Admin-only membership behavior in a disposable database; verify production only after a separately approved deployment.

Do not infer that an empty table is safe to drop without the dependency checks. Do not combine this with a production apply of PR #169.

**What changes this status:** owner approval plus completed dependency inventory and a separate tested migration.

## 4. Helper function grants

**Status: CONFIRMED IN LIVE CATALOG; OWNER DECISION OPEN.**

The following private helpers are owned by `postgres` and have EXECUTE for `authenticated`:

| Function | Identity arguments | Live ACL |
|---|---|---|
| `private.is_business_admin` | `requested_business_id text` | `postgres=X/postgres, authenticated=X/postgres` |
| `private.is_branch_admin` | `requested_branch_id text` | `postgres=X/postgres, authenticated=X/postgres` |
| `private.is_branch_member` | `requested_branch_id text` | `postgres=X/postgres, authenticated=X/postgres` |

The functions are used as authorization helpers in the database security model. The existence of EXECUTE for authenticated is not by itself proof of an exposure; their definitions, caller paths, schema usage, and RLS policy use must be considered together.

**Owner decision:** retain authenticated EXECUTE for RLS/helper evaluation, or approve a separate least-privilege grant migration after tracing every SQL policy/function caller. Do not revoke grants as part of this close-out without that review.

**What changes this status:** owner decision backed by a function-by-function caller and policy audit.

## 5. Supported-but-uncalled RPCs

**Status: BOTH PRESENT IN LIVE CATALOG; CURRENT APP SOURCE REVIEW FOUND NO CALLER; OWNER API DECISION OPEN.**

| Function | Live signature | Live ACL |
|---|---|---|
| `public.update_branch_details` | `(text,text,text,text,text,text)` | postgres, authenticated, service_role |
| `public.fetch_financial_reporting_inventory_v1` | `(text,text[],timestamptz,timestamptz,text,timestamptz,text,integer)` | postgres, authenticated |

The functions are present in the production catalog. The current app source review found no supported mainline caller. The reporting-inventory RPC is also the subject of the recovered migration source candidate described in the reconciliation addendum. `pg_stat_user_functions` returned no rows for these two names; that view result is not proof of zero historical calls when function tracking may be disabled or counters may be reset.

**Owner decision:** confirm whether each is part of the intended supported API. If retained, identify the intended caller and add contract/usage documentation. If retired, trace dependencies and plan a separate deprecation/removal migration.

**What changes this status:** explicit owner API inventory decision and caller/usage evidence.

## 6. Production has zero `business_memberships` rows

**Status: CONFIRMED BY READ-ONLY QUERY; OWNER CONFIRMATION REQUIRED.**

At the close-out audit:
- Total `public.business_memberships`: **0**
- Active, current ADMIN rows (`role='ADMIN' AND is_active AND removed_at IS NULL`): **0**
- Active ADMIN rows with `removed_at IS NOT NULL`: **0**
- CASHIER rows: **0**

**Owner decision:** confirm whether the production project is intentionally empty because the app is still private testing, or whether cloud membership provisioning is incomplete. Do not rely on cloud sync, restoration, or Admin authorization working end-to-end until the intended production membership state is confirmed and a controlled Admin membership has been established through the supported onboarding path.

**What changes this status:** owner confirmation and a read-only post-provisioning verification of the expected Admin membership, business, branch, profile, and auth identity.

## 7. Removed-active Admin guard verification time

**Status: NOT RECORDED BY OWNER.**

PR #169 is merged at `502b67aa`, but the migration is not applied in production and the merge timestamp is not a production guard-verification timestamp. The previous guard DDL evidence in the migration-history document also records its verification time as not recorded by owner.

**Owner action:** after the approved production migration path is executed, record the actual UTC time and evidence of the post-apply read-only verification. Until then, the production guard state is pending.

**What changes this status:** owner-supplied verification timestamp and corresponding post-apply catalog/test evidence.

---

## Explicit non-actions

- No production migration, DDL, DML, function call, grant, RLS change, secret operation, deploy, or migration-history repair was performed for this brief.
- No role constraint, foreign key, staff identity table, helper grant, or public RPC was changed.
- No production data was inserted to test cloud sync.
