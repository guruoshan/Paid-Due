# Audit follow-ups (2026-10-09)

Done on this branch: crash recorder (DmrApplication), `POST /api/staff/preview`
on the worker (per-IP and per-token rate limit), security headers for the static
site (`public/_headers`).

Staged, not applied: `staged_membership_policy.sql`, `staged_preview_lockdown.sql`.

Still to do:
1. App: switch `RemoteInviteStore.previewByTokenHash` to the worker endpoint, release,
   then run `staged_preview_lockdown.sql`.
2. Verified Android App Links for invites (needs the release SHA-256 fingerprint and
   a domain you control), then drop the `paiddue://` custom scheme as the only path.
3. Cashier login endpoint + signed session claims before any cloud financial sync.
4. Coupon wallet UNIQUE(customer_id, currency_code): Room migration, after checking
   for duplicates on a real device database.
5. Turn on leaked-password protection in Supabase Auth.
6. `ONBOARDING_PROGRESS.md` still says the invite endpoint is missing; it now exists.
