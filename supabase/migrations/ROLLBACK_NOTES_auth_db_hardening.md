# TASK 3B–3E rollback and application notes

**All migrations in this PR are NOT APPLIED.** No database write/deploy was performed by creating this PR.

## 3B — single active cashier session
Rollback: re-create the prior live definition of `public.create_cashier_sync_session(text,text)` from the pre-apply `pg_get_functiondef` snapshot. Do not drop it. Existing sessions revoked by this migration cannot be un-revoked from the new state; if rollback is required, affected cashiers must log in again to mint fresh sessions. This is why this proposal must not be applied until the single-device policy is explicitly accepted.

## 3C — permission updates revoke sessions and audit
Rollback: restore the pre-apply `public.update_cashier_permissions(text,jsonb)` definition from the reviewed live snapshot. Keep its existing `authenticated` EXECUTE grant. Existing `revoked_at` timestamps are irreversible without a separately reviewed session-restoration action; affected cashiers must authenticate again. Audit rows remain as history and must not be deleted during rollback.

## 3D — cashier envelope validation parity
Rollback: restore the pre-apply `public.ingest_cashier_financial_envelope(...)` definition from the reviewed live snapshot, preserving its exact 13-argument signature and four-column result. Already accepted envelopes are not deleted or rewritten. Before applying, run fixture tests for old offline timestamps, branch timezone, local date, future timestamps (+10 minutes boundary), each supported operation type, permissions, duplicate operation ID/hash, and operation collision.

## 3E — IP rate limiting
Rollback: restore the pre-apply `public.check_auth_rate_limit(text,text)` definition. The migration does not delete rate-limit rows. The matching TypeScript change must be deployed with the SQL migration in a coordinated release; do not apply SQL alone because the current TypeScript scope union rejects `LOGIN_IP`.

## Required pre-apply snapshot
Before any future application, capture exact function definitions and grants:
```sql
select n.nspname, p.proname, pg_get_function_identity_arguments(p.oid),
       pg_get_function_result(p.oid), pg_get_functiondef(p.oid)
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where (n.nspname,p.proname) in (
  ('public','create_cashier_sync_session'),
  ('public','update_cashier_permissions'),
  ('public','ingest_cashier_financial_envelope'),
  ('public','check_auth_rate_limit')
);
```

## Important rollout constraints
- 3B is a single-active-session product policy, not a purely technical cleanup. With no device binding, login on phone B invalidates phone A. Shared-phone/multiple-device use is not proven safe.
- 3C must be tested against the exact live audit-table definition, especially RLS, grants and SECURITY DEFINER ownership.
- 3D allows historical/offline `occurred_at`; it rejects only timestamps more than 10 minutes in the future. It does not reject old timestamps merely for being old.
- 3E TypeScript patch is described in the PR body; do not deploy or apply SQL in this task.
