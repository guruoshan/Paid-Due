# BOARDLOCK — Supabase-primary / offline foundation audit

**Scope:** foundation audit only, on `boardlock/supabase-primary-online-offline-v1`.
**Result:** audited; added an inert Kotlin authority policy contract and focused unit tests. No runtime login/session wiring, database, production configuration, or deployment changes made.
**Release state:** FOUNDATION CONTRACT ONLY; integration NOT IMPLEMENTED / NOT VERIFIED. Do not merge or release.

## Agreed target

1. Supabase/PostgreSQL is the online authority for identity, active membership, permissions, and cloud financial records.
2. Room/SQLite is a local cache and durable queue for explicitly permitted offline work. It is not proof of current online authorization.
3. Reconnect uses durable, idempotent reconciliation. A matching operation ID and digest is a duplicate; the same ID with a different digest is an integrity conflict. Financial history is never silently overwritten or deleted.
4. Online server revocation/disabled membership overrides cached local authority. Offline operation cannot know about a revocation until reconnection, so a bounded offline authorization policy must be designed and approved before enabling it.
5. A replacement phone must authenticate and restore only the business/branch data authorized for that identity, using stable server IDs and without clearing or replacing existing local financial history.
6. No SQLite wipe, transaction-history deletion, schema migration solely for authentication changes, production SQL execution, live deployment, or merge to `main`.

## Audit findings

### Authentication and session authority

- `AuthRepository.loginAdmin` validates the local Room password when a local business exists. The remote Admin sign-in/restoration path is currently used only when the local business is absent. Therefore local credentials can still establish an online session without fresh server authorization.
- `AuthRepository.loginCashier` validates the local Room PIN and creates a local session before attempting cashier cloud authentication. Cloud authentication is best-effort rather than an online-login gate.
- `TrustedSessionRestorer.restore` reconstructs sessions using local business, branch, active-user, and membership rows only. It does not ask Supabase/the server to validate current membership or revocation.
- `DmrApp` calls the local trusted-session restorer during startup and can route a remembered session into the operational app. The debug bootstrap path is another separate path that must be checked and excluded from production authority.
- `TrustedSessionStore` persists business/branch/user IDs, not server-issued authorization. Those IDs alone must not be treated as proof that the session remains authorized.
- `RemoteBusinessRestorer.restoreAdminIfEmpty` returns success for an existing local business without authenticating Admin credentials. That behavior is only a restoration shortcut and cannot be used as online authentication.

### Backend routing and synchronization

- `CashierCloudSession` calls `BuildConfig.PAID_DUE_BACKEND_BASE_URL` for login, upload, and download. The Gradle default points to the Cloudflare Worker, while the repository also contains the Supabase Edge Function `supabase/functions/api` with the cashier login/upload/download/metadata routes. A configured override may exist locally, but the repository default is not evidence that the app is using the Supabase function.
- Cashier server sessions are encrypted locally with Android Keystore, but `tokenFor` checks local membership ID and expiry only. Server validation occurs only when an endpoint is called; startup currently does not force a server validation.
- The financial sync engine has outbound outbox handling and an inbound-envelope applier, but this is not proof of complete end-to-end data sync. Inbound apply is gated off by default, and unsupported/rejected envelopes intentionally stop cursor advancement.
- The current envelope model is a limited financial-operation protocol, not a complete sync contract for every business entity and lifecycle (for example customers, products, credit/repayments, coupons, refunds, voids, shifts, and reports). Do not describe the whole product as synchronized.
- `BOARDLOCK/CROSS_DEVICE_SYNC.md` already requires staged SQL to remain unexecuted and calls out unresolved canonicalization, server date integrity, revocation/offline work, legacy backfill, device binding, personal-data handling, and operation allowlisting.

## Foundation decision

The safe foundation boundary is **not** to patch only one login function or change the backend URL in isolation. Doing so could leave other paths authorizing from local state, route requests to an unintended server, or enable incomplete financial sync.

The first foundation artifact is now present as `OnlineOfflineAuthorityPolicy.kt` with `OnlineOfflineAuthorityPolicyTest.kt`. It is intentionally not wired into production paths; it cannot enforce authority until integrated with reliable connectivity detection and server validation.

The next implementation increment must be a coordinated, testable authentication/session integration:
- distinguish ONLINE, OFFLINE_WITHIN_APPROVED_GRACE, and DENIED/REAUTH_REQUIRED states;
- require successful server authentication and active membership/permissions for online login;
- validate remembered sessions with the server before online financial use;
- allow offline access only under an explicit, bounded policy backed by a previously validated session, without claiming offline revocation awareness;
- fail closed for online authorization when the server is reachable but rejects/revokes the identity;
- keep local history and pending outbox intact on sign-out, revocation, account switch, failed restore, or network failure;
- align the backend base URL and endpoint contract with the deployed Supabase Edge Function only after verifying the exact configured/deployed route;
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
- `app/src/main/java/com/dailymoneyregister/core/security/OnlineOfflineAuthorityPolicy.kt` (new, inert contract)
- `app/src/test/java/com/dailymoneyregister/core/security/OnlineOfflineAuthorityPolicyTest.kt` (new tests)

No Gradle build, Android device test, live server test, Supabase migration, or deployment was run as part of this audit. No test result is claimed.
