# Supabase staff invitations and cashier administration

Paid&Due invitations use Supabase as the authoritative invitation store and GitHub Pages as the static HTTPS landing page. No email delivery, SMS/phone OTP, WhatsApp Business API, or Cloudflare Worker is used.

## Required Supabase Edge Function secrets

Under **Edge Functions → Secrets**, configure `AUTH_RATE_LIMIT_SECRET` and `CASHIER_PIN_PEPPER` with strong random values of at least 32 characters. Keep both stable and private. Never place these values in Android configuration or commit them to GitHub. Supabase provides `SUPABASE_URL` and the service-role secret in the Edge Function environment.

## Invitation flow

1. An authenticated Admin enters the cashier's name, phone number, branch, and permissions in Manage Cashiers.
2. The `staff-invite` Edge Function validates the Admin session, creates a 32-byte random URL-safe token, stores only its SHA-256 hash, and returns a link that expires in 48 hours.
3. Android opens WhatsApp with a prefilled message and the invitation link. The Admin taps Send manually; there is no WhatsApp Business API integration.
4. The canonical landing URL is `https://guruoshan.github.io/Paid-Due/invite/#<token>`. The token stays in the URL fragment so it is not sent to the static host in HTTP requests or access logs. The GitHub Pages page displays an explicit **Open Paid&Due** button and hands off to `paiddue://invite?t=<token>`.
5. The Android app extracts the token from the deep link and routes to the activation screen. The static landing page is required because Supabase Edge Functions rewrite `text/html` responses to `text/plain`; do not return the staff invite landing HTML from an Edge Function.
6. The cashier reviews business/branch information, enters and confirms a six-digit PIN, and taps Activate. Possession of the unique invitation link is the invitation credential; no email, phone OTP, or WhatsApp Business API verification is performed.
7. The `staff-activate` Edge Function rate-limits attempts, derives a salted PBKDF2-SHA256 PIN verifier on the server, and calls `activate_staff_invite`. PostgreSQL atomically consumes the valid invitation and creates the staff identity and membership.
8. Supabase remains the online source of truth. Room stores the activated projection needed for offline operation; it is not the primary account store.

## URL contract and regression protection

The Android validator and `staff-invite` must agree on the exact canonical landing prefix above. The raw token must remain a URL-safe fragment; never log or persist it in plaintext. Android unit tests and Deno tests cover URL acceptance/rejection. Do not switch the Edge Function back to `/functions/v1/staff-open#...` unless the Android contract and static landing design are deliberately changed together.

## Security / cashier administration

- The raw token is only returned when creating the link. PostgreSQL stores its hash, not the original secret.
- Tokens expire after 48 hours and PostgreSQL allows only one activation. Admins may revoke/replace unused links by creating a replacement invite for the same phone and branch.
- If a link is forwarded, anyone possessing it can claim the invitation first. Treat it like a password and share it only with the intended cashier.
- Admin permission changes are authorized by the current Supabase Auth Admin session and the same business/branch membership; changes revoke existing cashier sync sessions and create an audit event.
- Removing a cashier soft-disables their membership, revokes active sync sessions, and keeps financial transactions and audit evidence intact. It is not a destructive database delete.

Never place a service-role or secret API key in the Android app.
