# Paid&Due Database / Source Reconciliation

**Audit date:** 2026-10-11  
**Supabase project:** `ttvdpgxqvvtjnxxfkoui`  
**Repository base reviewed:** `main` at `6a95bc8f297504c5b6f55d72857bbe37d244da5d`  
**Working branch:** `fix/db-source-reconciliation-20261011`

## Scope and safety

Compared live migration history, table inventory, function signatures and execution grants with repository SQL and API handlers. No production schema changes, migration-history edits, record writes, resets, or deployments were performed. The repaired notification migration was executed inside a transaction and rolled back.

## Migration reconciliation

- Live Supabase history contains **13 applied migrations**.
- Seven applied migration SQL files were missing from `supabase/migrations`. Their exact statements were recovered from `supabase_migrations.schema_migrations.statements`, not recreated from memory:
  - `20261008184418_admin_financial_envelope_fetch_authenticated.sql`
  - `20261008200017_boardlock_cashier_authenticated_business_restoration.sql`
  - `20261009061446_add_admin_ingest_financial_envelope.sql`
  - `20261009064450_add_admin_update_cashier_permissions_and_branch_details.sql`
  - `20261009154450_boardlock_cashier_financial_envelope_download.sql`
  - `20261009160938_cashier_restoration_payload.sql`
  - `20261009215007_add_digest_bearing_financial_envelope_download_rpcs.sql`
- Two existing applied files had drifted from the exact SQL stored in live migration history. They were aligned to the database records:
  - `20261010100412_phone_link_staff_invites_and_cashier_admin.sql`
  - `20261010102029_allow_stronger_staff_pin_verifier.sql`
- After restoration, all 13 applied migration files compare exactly (trimmed text equality) with the corresponding stored SQL statements.
- `20261010112000_business_notifications_and_permission_requests.sql` is **not applied** and has no live migration-history row. It remains a pending migration source; it must not be treated as already deployed.

## Notification migration repairs

The pending migration had three concrete defects:

1. A truncated cash-difference `IF` expression caused a SQL syntax error.
2. A stray duplicated tail of the notification trigger function appeared after the cashier-permission update function.
3. The migration revoked execute privileges from two trigger functions before those functions were created, which could fail on a clean database.

The source repair:
- Uses a complete, guarded numeric predicate for cash discrepancies.
- Removes the orphan duplicate fragment.
- Moves the two revocations after their respective function definitions.
- Preserves the existing cashier notification/session RPC definitions and their service-role-only grants.

**Verification:** the complete repaired migration ran successfully inside `BEGIN … ROLLBACK` against the live database. The rollback check confirmed `private.business_notifications` and `private.cashier_permission_requests` remained absent and the live migration history remained at 13 rows. This proves the SQL can execute against the current schema; it does not mean the migration has been deployed.

## API source repair

`supabase/functions/api/index.ts` routed to `listCashierNotifications`, `markCashierNotificationRead`, and `requestCashierPermission` without importing them from `handlers.ts`. The missing imports were added.

The handler's dedicated `verify_cashier_notification_session` call was preserved. It intentionally allows read-only notification access to survive the revocation of a financial sync session; replacing it with the ordinary financial-session verifier would change the security contract.

A pull-request CI workflow was added to run Deno API tests and type-check the API entrypoint and handlers. Its result must be green before merge.

## Deployment drift

The deployed Supabase Edge Function inventory at audit time was: `api` v12, `staff-invite` v9, `staff-activate` v7, `staff-open` v8, and `staff-preview` v4. The deployed `api` v12 source is older than repository `main`: its entrypoint does not include the current staff-activation, public invite landing, cashier notification, or permission-request routes. The branch fixes the source import error but does not deploy it. Numeric function versions are independent; the important mismatch is the missing route/contract behavior.

## Live inventory snapshot

The live database inventory returned these tables:

- `public.businesses` (1 row)
- `public.branches` (1 row)
- `public.profiles` (1 row)
- `public.business_memberships` (1 row)
- `private.staff_invites` (13 rows)
- `private.staff_identities` (0 rows)
- `private.staff_invite_activations` (0 rows)
- `private.cashier_sync_sessions` (0 rows)
- `private.financial_operation_envelopes` (5 rows)
- `private.auth_rate_limits` (2 rows)
- `private.staff_admin_audit_events` (0 rows)

The notification tables are absent because the pending migration has not been applied. The inventory does not by itself prove cross-device financial reporting works; there are five stored financial envelopes, but no cashier sync sessions and no end-to-end cashier sale was performed during this read-only audit.

## Permissions and security findings requiring separate decisions

1. **Critical advisor finding — RLS disabled:** `private.staff_admin_audit_events` has RLS disabled. Supabase recommends reviewing/enabling RLS, but enabling it without an intended policy can block access. No automatic RLS change was made. Candidate SQL to discuss is:
   `ALTER TABLE private.staff_admin_audit_events ENABLE ROW LEVEL SECURITY;`
   Define and test the intended policy/privileged-function access before running it.
2. **Anonymous SECURITY DEFINER RPC:** `public.preview_staff_invite(text)` is executable by `anon`. Review whether anonymous execution is required; if the Edge Function uses service-role access, consider revoking anonymous execution in a separate reviewed migration.
3. **Fourteen authenticated SECURITY DEFINER functions** are flagged by the Supabase security advisor. Several are intentional authenticated RPCs with in-function business/branch checks; review each function body and grant individually rather than blanket-revoking them.
4. Six private tables have RLS enabled without policies. They should remain inaccessible directly unless a reviewed access policy is required; server-side RPCs are the current intended access path.

## Remaining release gates

- CI must pass for Android and the new Deno backend workflow.
- Review the security findings above and decide whether to create a separate forward-only security migration.
- Review the pending notification migration as a proposed production change. Only after approval should it be applied once and verified with the expected migration-history row, tables, policies, functions, triggers, and RPC grants.
- Then run authenticated API tests for cashier notifications and permission requests, followed by a multi-device test: cashier request/permission update → admin notification → cashier refresh → durable financial sale → admin sees the same transaction exactly once.

**No live database changes or deployments were made by this reconciliation branch.**
