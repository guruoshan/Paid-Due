# Supabase staff invitation setup

Paid&Due staff invitations are sent by Supabase Auth. No separate invite webpage or external Worker is required.

## Required hosted Auth settings

1. In Supabase Dashboard, open **Authentication → URL Configuration**.
2. Add the exact mobile redirect URL `paiddue://invite` to **Redirect URLs**.
3. Configure a production SMTP provider under **Authentication → SMTP Settings** and verify the sender domain. The default test email service is not suitable for production delivery.
4. Under **Edge Functions → Secrets**, ensure `AUTH_RATE_LIMIT_SECRET` and `CASHIER_PIN_PEPPER` are set to strong values of at least 32 characters. Keep them stable; do not put them in Android BuildConfig or commit their values.
5. Keep the invitation and confirmation email templates using Supabase's confirmation URL so the Auth token is verified before the app receives the deep link.

The repository config records the mobile redirect allowlist for local/CLI configuration. The hosted Dashboard settings must still be checked; a repository file alone does not prove that remote Auth settings have been applied.

## Invitation flow

- An authenticated Admin submits staff email, phone, name, branch, and permissions to `/functions/v1/staff-invite`.
- The Edge Function verifies the Admin session, applies invitation rate limiting, stores only the invitation-token hash, and asks Supabase Auth to send the invite or sign-in email.
- Supabase redirects to `paiddue://invite?t=...` after email verification/sign-in.
- Android's Supabase Auth deep-link handler establishes the session before the invitation preview and PIN activation run.
- `/functions/v1/staff-activate` requires the verified Auth session, checks email/token/phone/expiry/single-use rules, then atomically activates the existing staff identity and membership.
- The app materializes the committed remote result in Room for offline use. Supabase remains the online authority.

Never place a service-role or secret API key in the Android app.
