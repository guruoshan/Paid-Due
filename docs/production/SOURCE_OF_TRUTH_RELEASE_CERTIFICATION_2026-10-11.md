# Source-of-Truth Release Certification — 2026-10-11

Status: NOT CERTIFIED FOR MERGE.

## CI

The following GitHub Actions runs completed successfully on the previously identified PR head `d2009884300fb9dfe181754a64a1a5d97c0d33ba`:

- PASS — Paid-Due backend validation: https://github.com/guruoshan/Paid-Due/actions/runs/38090881991
- PASS — Paid & Due migration validation: https://github.com/guruoshan/Paid-Due/actions/runs/38090882082
- PASS — Paid-Due repair branch validation: https://github.com/guruoshan/Paid-Due/actions/runs/38090882016

The available workflow-run response did not independently establish that this SHA remains the latest PR head. Recheck checks on the current head before merge.

## Edge Functions

### api

- Production: version 12, `verify_jwt=false`; production health handler reports backend version 3 and PBKDF2 iterations 100000.
- Repository candidate: PR branch `supabase/functions/api/`; includes newer handler routes and credential contract, with tests passing in the recorded CI run.
- Decision: PR branch is the proposed canonical implementation, but production deployment was not completed by the available deployment action. Do not claim parity.

### staff-invite

- Production: version 9, `verify_jwt=true`; uses direct app intent handoff with HTTPS fallback.
- Repository candidate: PR branch `supabase/functions/staff-invite/index.ts`; returns an HTTPS URL for the `staff-open` landing function.
- Decision: PR branch is the proposed canonical implementation, but production deployment was not completed by the available deployment action. Do not claim parity.

### staff-open

- Production: version 8, `verify_jwt=false`; implementation differs from the repository file.
- Repository candidate: PR branch `supabase/functions/staff-open/index.ts`; uses the HTTPS landing-page path and app handoff.
- Decision: PR branch is the proposed canonical implementation, but production deployment was not completed by the available deployment action. Do not claim parity.

## Hosted Auth configuration

Verified:
- Project reference: `ttvdpgxqvvtjnxxfkoui`.
- Edge Function gateway JWT flags in `supabase/config.toml` are repository-managed.

Not retrieved from connected Supabase tools:
- Enabled hosted Auth providers and provider-specific settings.
- Signup/email-confirmation behavior.
- Site URL and redirect allow-list.
- Password, OTP, session, refresh-token, rate-limit, CAPTCHA, SMTP and template settings where applicable.

These settings must remain dashboard-managed until an authorized operator captures actual production values. Do not commit keys, tokens, or secrets.

## Required manual release actions

1. In the Supabase Dashboard, open project `ttvdpgxqvvtjnxxfkoui` → Authentication → URL Configuration and Sign In / Providers. Record actual values for site URL, redirect URLs, enabled providers, signup and confirmation settings, and relevant password/OTP/session/rate-limit/email settings. Compare with the Auth record at `docs/production/SUPABASE_AUTH_CONFIGURATION.md`. Mark unsupported-by-Git settings as intentionally dashboard-managed and verify them after setup.
2. Deploy the exact PR-branch files for `api`, `staff-invite`, and `staff-open` through the Supabase Dashboard or an authorized deployment environment. Preserve the verified gateway flags: `api=false`, `staff-invite=true`, `staff-open=false`. Review the diff and perform the repository's existing CI/API contract validation and an authorized production smoke check before considering deployment complete. Do not deploy from an unverified local checkout.
3. Re-fetch PR #8's current head SHA and verify all required workflows on that exact SHA.
4. Only after the above are evidenced, merge PR #8 and verify the resulting merge commit and post-merge `main` SHA.

## Merge decision

PR #8 is NOT certified for merge because production Edge Function versions are still drifted and hosted Auth configuration is not fully evidenced. CI runs listed above passed, but their association with the latest current PR head must be confirmed. No merge or Edge Function deployment was completed during this release attempt.
