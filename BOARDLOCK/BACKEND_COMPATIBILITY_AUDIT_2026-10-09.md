# BOARDLOCK — Android / Supabase Backend Compatibility Audit

**Date:** 2026-10-09  
**Base:** `migration/offline-first-supabase-v1`  
**Repair branch:** `fix/backend-compatibility-audit-2026-10`  
**Production backend/schema/data:** unchanged  
**Status:** Targeted compatibility repairs committed; build/device verification and inbound coverage remain open.

## Verified backend contract

- Admin financial ingestion uses `public.ingest_financial_envelope`. It requires `auth.uid()` and validates that the caller is an active Admin membership for the submitted business and branch.
- Admin inbound fetch uses `public.fetch_financial_envelopes_v2`, which also requires an authenticated Supabase identity and active Admin membership.
- Cashier financial upload/download uses the `/api/financial/upload` and `/api/financial/download` Edge Function routes with the custom cashier session token. Cashier authorization is checked server-side against membership and operation permissions.
- The live Edge Function `api` is active. The Android branch config points at the matching Supabase project and API base URL.
- The current inbound materializer only applies cash `RECORD_SALE`. Other operation types return `UnsupportedYet`; inbound application remains disabled by default as required.

## Defects repaired on this branch

1. **Admin login did not establish backend auth for an already-populated local database.** `AuthRepository.loginAdmin` only called `restoreAdminIfEmpty` when the local business was absent. The new path attempts Supabase Auth after successful local credential verification, validates the remote Admin membership/business/branch/user against the active local session, and is bounded by a short timeout. Cloud failure does not block local offline login; sync remains fail-closed.
2. **Authorization-blocked outbox recovery was missing.** Existing `AUTH_REQUIRED` rows remain preserved. They are requeued only when the server confirms the active Supabase Admin identity maps to the same local membership, business and branch, and only for rows belonging to that same local actor/tenant. Operation IDs and financial rows are unchanged.
3. **Admin upload accepted any decoded RPC row as an acknowledgement.** The app now requires the backend receipt status to be exactly `ACKNOWLEDGED`; unexpected statuses go to `NEEDS_ATTENTION`, not a false success.
4. **Cashier inbound could fall through to the Admin RPC if the custom session was missing.** The app now fails closed rather than switching authentication paths.
5. **Admin inbound fetch now explicitly requires a Supabase Auth session.** A local Room session is not treated as backend authentication.

## Still open — do not enable inbound yet

- Add idempotent, atomic inbound materializers for refunds, voids, amount corrections, payment-method changes, credit repayments, coupon purchases, expenses, expense corrections/voids, and cash-shift operations; verify associated records and links in each transaction.
- Add durable cursor recovery and migration/replay tests for every supported operation.
- Run Android compilation and all relevant unit/instrumentation tests, then verify on Admin Motorola and Cashier Pixel without creating artificial financial test transactions.
- Inspect and expose actionable sync state in the UI so an Admin can distinguish local-only/pending, server-confirmed, authentication-required, rejected, and integrity-conflict records.

## Evidence / safety

- Device log previously showed five Admin outbox rows in `AUTH_REQUIRED` (two `RECORD_SALE`, one each `CLOSE_CASH_SHIFT`, `COUNT_CASH_SHIFT`, `RECORD_EXPENSE`) and 31 envelopes fetched from the cloud. Those 31 cloud envelopes are not proof that the blocked local records uploaded.
- The live cloud baseline contained 26 `RECORD_SALE`, 3 `OPEN_CASH_SHIFT`, 1 `RECORD_COUPON_PURCHASE`, and 1 `RECORD_REFUND` envelope at audit time.
- No production SQL, RLS, function ACL, deployment, or financial data was changed.
- Automated tests and an Android build have **not yet been run for this branch**. Do not merge or release until they pass.
