# Supabase Sync and Permission Fix — Engineering Log (2026-10-12)

## Scope and safety

Audited the live Supabase project and the current GitHub main branch. This log separates deployed changes from client changes awaiting CI. Do not treat the client patch as released until the PR's Android unit/build gates pass and a signed APK is produced.

## Confirmed live schema

- Core tables are `public.businesses`, `public.branches`, `public.profiles`, and `public.business_memberships`, plus private session, invite, notification, audit, and financial-envelope tables.
- RLS is enabled on the live public/private tables inspected. Existing public member reads use tenant-aware helper policies. The requested `public.customers`, `public.sales`, `public.permissions`, `customers_inbound`, and `sales_inbound` tables do not exist in this project.
- Therefore no guessed RLS policies or staging-table triggers were added. Doing so would not connect to the real application path and would risk introducing a second, conflicting financial source of truth.
- `private.financial_operation_envelopes` was empty at the time of the read-only count. No customer table exists in the remote schema.
- The cashier's live `business_memberships` row had `can_view_reports=false` and `can_manage_products=false` at inspection time; other permission flags were true. The RPC accepts all 14 permission keys, including those two flags.
- `public.ingest_cashier_financial_envelope` checks the active cashier membership and each operation's current permissions before inserting an immutable, idempotent envelope.
- The live API Edge Function is active at version 13. It exposes `GET /api/staff/metadata`, `POST /api/staff/login`, and financial upload/download routes. Login creates the custom cashier sync session; activation by itself does not create that session.
- Release inbound application is disabled by default. The documented receiver is not certified for the full operation matrix, so it was not enabled as a blanket production switch.

## Production change applied

Applied Supabase migration `20261012190015_permission_updates_preserve_cashier_sync_sessions`.

- Replaced `public.update_admin_cashier_permissions(text,jsonb)` while retaining:
  - authentication requirement;
  - exact 14-key boolean allowlist;
  - active Admin, business, branch, and target-cashier authorization checks;
  - row locking;
  - all 14 permission-column updates;
  - before/after audit event insertion.
- Removed the block that revoked every active `private.cashier_sync_sessions` token whenever permissions changed.
- This prevents an ordinary permission edit from immediately invalidating the cashier's transport credential. It does not weaken the server's financial authorization check: the ingest RPC reads the current membership permissions for each upload.
- Post-apply read-only verification confirmed the deployed function contains the permission update and audit insert and no longer contains the session-revocation update.

## Client patch prepared on this branch

Branch: `fix/supabase-permission-session-refresh`

1. `app/src/main/java/com/dailymoneyregister/core/cloud/sync/CashierCloudSession.kt`
   - Added an authenticated call to the existing `GET /api/staff/metadata` route.
   - Rejects mismatched membership metadata and clears rejected sessions on HTTP 401/403.
2. `app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncScheduler.kt`
   - Refreshes server-authoritative permissions during cashier sync.
   - Validates business, branch, membership, and role before applying the response.
   - Updates the Room membership and `SessionManager` state so reactive UI reads see changed permissions without logout.
   - A temporary metadata/network failure is logged and does not by itself block an otherwise valid upload; server-side permission checks remain authoritative.
   - A rejected/expired session keeps the durable outbox pending and does not falsely acknowledge financial data.
3. `supabase/migrations/20261012190015_permission_updates_preserve_cashier_sync_sessions.sql`
   - Records the exact production migration for source-of-truth tracking.

## Root causes still requiring implementation/verification

1. **Financial upload has not been proven in production.** The live envelope table was empty at audit time. The expected path is: successful cashier remote PIN login -> custom session token -> outbox upload -> `/api/financial/upload` -> `verify_cashier_sync_session` -> `ingest_cashier_financial_envelope` -> immutable envelope acknowledgement.
2. **Admin-side inbound materialization remains a release blocker.** `app/build.gradle.kts` defaults `PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES` off for release. Do not enable it until the full operation receiver, cursor/replay semantics, and device reconciliation are certified.
3. **Customers are local Room records and there is no remote customer table/RPC in the live schema.** The current inbound/outbound financial-envelope contract does not implement standalone customer-directory synchronization. A customer directory sync requires a deliberately scoped server-authoritative table/RPC/API and client reconciliation; a guessed `customers_inbound` trigger is not a fix.
4. **Permission values selected in the UI must be rechecked after this client patch is built and installed.** The server RPC accepts the full 14-key set, but the live row had two false flags. The new metadata refresh updates the cashier UI state; the next production acceptance test must confirm all 14 flags from admin save through cashier refresh.
5. The custom cashier session is finite-lived. This patch handles rejection without crashing and prevents permission edits from revoking sessions, but a true session-expiry recovery flow still needs a fresh PIN/login because the app must not persist a reusable PIN.

## Verification status

- Production migration: applied successfully.
- Production function inspection after apply: passed (no session-revocation statement; permission update and audit insert remain).
- Schema/policy inspection: completed read-only; no requested customer/sales/permissions or inbound staging tables exist; live tables inspected have RLS enabled.
- Android build/unit/instrumentation tests for the new client patch: not yet run by this connector workflow. The PR must remain draft until GitHub Actions passes.
- Live end-to-end cash sale -> envelope -> admin materialization/report, cross-device customer search, and permission-refresh device tests: not yet proven. No synthetic financial data was inserted into production.

## Acceptance checklist before release

- [ ] Android build and unit tests pass on this exact PR head.
- [ ] Cashier PIN login creates one valid sync session and `/api/staff/metadata` returns matching membership/business/branch.
- [ ] A test cash sale receives a matching `ACKNOWLEDGED` receipt and appears once in `private.financial_operation_envelopes`.
- [ ] Admin receives/downloads the envelope and the supported inbound materializer applies it atomically before advancing its cursor.
- [ ] All 14 permission flags survive admin save, metadata fetch, Room update, and UI state update.
- [ ] Permission edits do not revoke the active cashier session; server rejects a forbidden operation immediately after the change.
- [ ] Expired/revoked sessions leave outbox rows recoverable and show a clear sign-in-needed state.
- [ ] Customer creation on one device is visible on the other through the newly implemented customer sync contract.


## Follow-up work added to this branch (not deployed)

### Customer directory contract prototype

- Added `supabase/migrations/20261012200000_customer_directory_sync_contract.sql`.
- Adds a tenant-scoped `private.customer_directory` keyed by business + canonical E.164 phone, with RLS enabled and direct table access revoked from `anon` and `authenticated`.
- Adds service-role-only `sync_customer_directory_upsert` and `sync_customer_directory_list` RPCs that validate active business/branch/membership scope, cap list sizes, preserve the first canonical ID for a phone, and do not silently rename an existing customer.
- Added `POST/GET /api/customers/sync` in the Supabase API handler and route, behind the existing cashier custom-session verification.
- Added unauthenticated-request tests and a dedicated isolated SQL contract gate at `.github/workflows/customer-directory-contract.yml`, with fixture/test SQL under `supabase/tests/`.

### Important limitation — not yet end-to-end

This is a backend contract prototype, **not a completed customer sync rollout**. The Android client has not yet been wired to call these endpoints or reconcile IDs. A local customer ID may already be referenced by invoices, credit ledger events, coupons, expenses, or financial operations. When two devices independently create the same phone with different IDs, the server returns the first canonical ID; client reconciliation must preserve those historical references using an explicit alias/mapping strategy before enabling the migration or deploying the Edge Function. The current admin path also needs a verified authenticated-admin route, not just the cashier custom-session route.

Therefore the customer migration and Edge Function route are **not applied/deployed to production**. The new isolated contract gate is running; do not enable or deploy this prototype until it passes and the Android alias/reconciliation and admin path are implemented and tested.
