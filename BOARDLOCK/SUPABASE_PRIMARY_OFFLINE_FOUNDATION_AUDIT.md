# BOARDLOCK — Supabase-primary / offline foundation audit

**Scope:** foundation audit and code changes on `boardlock/supabase-primary-online-offline-v1`.
**Result:** online Admin/cashier login now requires remote authentication and tenant/membership matching; remembered sessions require fresh server validation; the debug auto-session path is removed; the cashier API default targets the Supabase Edge Function; a cashier login restoration response is implemented in source.
**Release state:** SOURCE CHANGES NOT BUILD-VERIFIED. Offline grace is deliberately not enabled. Do not merge or release until build/tests and deployed RPC contracts are verified.

## Agreed target

1. Supabase/PostgreSQL is the online authority for identity, active membership, permissions, and cloud financial records.
2. Room/SQLite is a local cache and durable queue for explicitly permitted offline work. It is not proof of current online authorization.
3. Reconnect uses durable, idempotent reconciliation. A matching operation ID and digest is a duplicate; the same ID with a different digest is an integrity conflict. Financial history is never silently overwritten or deleted.
4. Online server revocation/disabled membership overrides cached local authority. Offline operation cannot know about a revocation until reconnection, so a bounded offline authorization policy must be designed and approved before enabling it.
5. A replacement phone must authenticate and restore only the business/branch data authorized for that identity, using stable server IDs and without clearing or replacing existing local financial history.
6. No SQLite wipe, transaction-history deletion, schema migration solely for authentication changes, production SQL execution, live deployment, or merge to `main`.

## Audit findings

### Authentication and session authority

- `AuthRepository.loginAdmin` and `loginCashier` now call the remote authentication/restoration contract before constructing an `ActiveSession`; local credential hashes are no longer sufficient to create an online session.
- `RemoteBusinessRestorer` checks the authenticated server identity against local business/branch/user/membership IDs. Existing local financial data and credentials are not overwritten; permission mismatches fail closed.
- `TrustedSessionRestorer.restore` now requires a fresh server check. Cashier checks compare membership, tenant IDs, canonical phone, and all supported permissions against server metadata. Admin checks require the current Supabase auth session and a matching active Admin row from the server RPC.
- The debug bootstrap invocation was removed from startup. The class remains unused in source, but no longer establishes a session through `DmrApp`.
- The shared `OnlineOfflineAuthorityPolicy` is wired into login and remembered-session restoration. Current integration is online-only: server failure denies session restoration; bounded offline grace is not yet implemented.
- `TrustedSessionStore` still persists IDs, not authorization. IDs are now lookup keys only and are not sufficient for session restoration.

### Backend routing and synchronization

- `CashierCloudSession` defaults to the Supabase Edge Function base URL (while retaining explicit Gradle/environment overrides). The Cloudflare invite/activation Worker is no longer the default cashier financial API.
- Cashier server tokens remain encrypted with Android Keystore. Startup now calls `/api/staff/metadata` and checks the current membership/tenant/phone/permission snapshot, not merely local token expiry.
- The Edge Function source now returns a server-authorized restoration snapshot after PIN verification. The metadata RPC is still a staged SQL contract in this repository and has not been deployed by this work; runtime cashier login therefore depends on that RPC and the deployed `cashier_login_credential` response providing the expected membership/business/branch/user IDs.
- The financial sync engine has outbound outbox handling and an inbound-envelope applier, but this is not proof of complete end-to-end data sync. Inbound apply is gated off by default, and unsupported/rejected envelopes intentionally stop cursor advancement.
- The current envelope model is a limited financial-operation protocol, not a complete sync contract for every business entity and lifecycle (for example customers, products, credit/repayments, coupons, refunds, voids, shifts, and reports). Do not describe the whole product as synchronized.
- `BOARDLOCK/CROSS_DEVICE_SYNC.md` already requires staged SQL to remain unexecuted and calls out unresolved canonicalization, server date integrity, revocation/offline work, legacy backfill, device binding, personal-data handling, and operation allowlisting.

## Foundation decision

The safe foundation boundary is **not** to patch only one login function or change the backend URL in isolation. Doing so could leave other paths authorizing from local state, route requests to an unintended server, or enable incomplete financial sync.

The authority policy is now wired into online login and remembered-session restoration. The source integration is not yet verified by a Gradle build, and live Supabase RPC availability has not been checked. Because server validation cannot safely distinguish revocation from network unavailability in the current contract, this change fails closed rather than allowing offline access. The approved bounded offline-grace behavior remains a separate required increment.

Remaining foundation gates:
- distinguish ONLINE, OFFLINE_WITHIN_APPROVED_GRACE, and DENIED/REAUTH_REQUIRED states;
- require successful server authentication and active membership/permissions for online login;
- validate remembered sessions with the server before online financial use;
- implement offline access only under an explicit, bounded policy backed by a previously validated timestamp, without claiming offline revocation awareness;
- fail closed for online authorization when the server is reachable but rejects/revokes the identity;
- keep local history and pending outbox intact on sign-out, revocation, account switch, failed restore, or network failure;
- verify the configured backend URL, Edge Function response contract, `fetch_cashier_metadata` RPC, and deployed `cashier_login_credential` result shape;
- add tests for online local-credential bypass, server revocation, offline grace expiry, wrong-tenant restoration, duplicate replay, digest collision, and preserved pending outbox;
- leave inbound financial apply disabled until server contract, operation coverage, atomic apply/cursor behavior, and migration/security tests pass.

## Evidence and limits

This audit is based on source inspection of:
- `AuthRepository.kt`
- `RemoteBusinessRestorer.kt`
- `RemoteAdminAuthRepository.kt`
- `TrustedSessionRestorer.kt`
- `TrustedSessionStore.kt`
- `DmrApp.kt`
- `CashierCloudSession.kt`
- `FinancialSyncEngine.kt`
- `RemoteFinancialSyncRepository.kt`
- `InboundEnvelopeApplier.kt`
- `app/build.gradle.kts`
- `supabase/functions/api/index.ts` and `handlers.ts`
- `BOARDLOCK/CROSS_DEVICE_SYNC.md`
- `app/src/main/java/com/dailymoneyregister/core/security/OnlineOfflineAuthorityPolicy.kt`
- `app/src/test/java/com/dailymoneyregister/core/security/OnlineOfflineAuthorityPolicyTest.kt`

No Gradle build, Android device test, live server test, Supabase migration, or deployment was run as part of this audit. No test result is claimed.
