# Supabase staff invitations and cashier administration

Paid&Due invitations use a short-lived token hosted by Supabase. The app opens WhatsApp with a prefilled message; an Admin presses Send. No email delivery, SMS/phone OTP, WhatsApp Business API, Worker, or external invitation webpage is required.

## Required Supabase Edge Function secrets

Under **Edge Functions → Secrets**, configure AUTH_RATE_LIMIT_SECRET and CASHIER_PIN_PEPPER with strong random values of at least 32 characters. Keep both stable and private. Never place these values in Android configuration or commit them to GitHub. Supabase provides SUPABASE_URL and the service-role secret in the Edge Function environment.

## Invitation flow

1. An authenticated Admin enters the cashier's name, phone number, branch, and permissions in Manage Cashiers.
2. The staff-invite Edge Function validates the Admin session, creates a 32-byte random token, stores only its SHA-256 hash, and returns a link which expires in 48 hours.
3. Android opens WhatsApp using the normal wa.me click-to-chat URL with the message and invitation link prefilled. The Admin taps Send manually. There is no WhatsApp Business API integration.
4. The invitation link is an Android `intent://` link targeting the app's `paiddue://invite?t=...` handler. When Paid&Due is installed, tapping the link should open the app directly. The URL includes the HTTPS `staff-open` landing page as a browser fallback for phones where the app is not installed.
5. The app extracts the token from the deep link and routes to the activation screen. The HTTPS landing page remains a fallback, not the primary path.
6. The cashier reviews business/branch information, enters and confirms a six-digit PIN, and taps Activate. No email, phone, OTP, or Supabase Auth verification is required for the employee; possession of the unique invitation link is the authorization credential.
7. The `staff-activate` Edge Function rate-limits attempts, derives a salted PBKDF2-SHA256 PIN verifier on the server, and calls `activate_staff_invite`. PostgreSQL atomically consumes the valid invitation and creates the staff identity and membership.
8. Supabase remains the online source of truth. Room stores the activated projection needed for offline operation; it is not the primary account store.

## Security / cashier administration

- The raw token is only returned when creating the link. PostgreSQL stores its hash, not the original secret.
- Tokens expire after 48 hours and PostgreSQL allows only one activation. Admins may revoke/replace unused links by creating a replacement invite for the same phone and branch.
- If a link is forwarded, anyone possessing it can claim the invitation first. Treat it like a password and share it only with the intended cashier.
- Admin permission changes are authorized by the current Supabase Auth Admin session and the same business/branch membership; changes revoke existing cashier sync sessions and create an audit event.
- Removing a cashier soft-disables their membership, revokes active sync sessions, and keeps financial transactions and audit evidence intact. It is not a destructive database delete.

Never place a service-role or secret API key in the Android app.
