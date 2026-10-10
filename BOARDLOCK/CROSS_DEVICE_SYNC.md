# BOARDLOCK — Cross-device financial synchronization

**Status: FOUNDATION ONLY — NOT FUNCTIONAL, NOT DEPLOYED.**
No financial synchronization, cloud intake or Admin cross-device reporting is active.

## Current verified problem
Cashiers on different phones commit transactions to their separate local Room
databases. Admin reporting uses the Admin phone's Room data and therefore cannot
see other devices' transactions. Supabase Auth identity and email activation do
not provide financial synchronization.

## Authority and vocabulary
Committed financial transactions and their lifecycle are immutable. One cashier
intent = one operation identity. Signed **amounts** mean positive/negative minor
units and must not be confused with **cryptographic signatures**, which establish
device/source authenticity. Neither a report total nor an unverified client
payload is independent financial authority. A duplicate operation ID with a
different canonical digest is an integrity conflict, never an automatic retry.

## Thin vertical delivery sequence
1. Trace authoritative cash-sale Room transaction, operation, settlement and
   membership identities. Lock canonical JSON bytes, schema version, timezone,
   amount semantics and server-side evidence validation.
2. Create a migration-tested encrypted durable outbox, committed atomically with
   the cash-sale operation, including correct historic-data behavior.
3. Bind an independently authenticated and authorized employee session to the
   business, branch, cashier, registered device and submission rights.
4. Implement atomic server intake with exact operation-id idempotency, full
   evidence validation, no client direct table writes, replay rejection and
   cross-tenant restrictions. The staged SQL alone is NOT intake.
5. Upload one cash sale from a second physical phone, reconcile acknowledgements,
   and display exactly one verified sale in the Admin cloud report. Confirm
   retries, offline mode, recovery, revocation and report-date boundaries.
6. Expand incrementally: refunds/voids/corrections, then credit and repayments,
   coupons, shifts and expenses, invoices and remaining lifecycle features.
7. Cross-feature reconciliation, migration tests, security tests, physical
   three-device tests, rollback plan and explicit release approval.

## Open decisions
- **Clock skew — UNDECIDED.** Store device occurred_at and server received_at
  separately. Decide accepted skew window and quarantine behavior for
  out-of-window events; never rewrite occurred_at to received_at automatically.
- **Late arrival after day close — UNDECIDED.** Decide original-day
  restatement versus a visible late-arrivals adjustment, with immutable
  audit and business-day reconciliation; do not silently credit another day.
- **Revoked staff with offline work — UNDECIDED.** Decide cutoff policy,
  session/token lifetime, grace interval and treatment of operations committed
  locally before revocation but uploaded after it. Default fail-closed on intake.
- **Device binding — PROPOSED, NOT APPROVED.** Admin registers an Android
  Keystore asymmetric public key for each device; each upload signs a
  server-issued nonce plus canonical payload digest, with server verification,
  device revocation and replay window. Never copy private keys across devices.
- **Terminology — DECIDED.** 'Signed financial amount' is a positive/negative
  accounting amount; 'cryptographically signed envelope' is an authenticated
  cryptographic assertion. No interchange of the terms.
- **Outbox encryption — PROPOSED, NOT APPROVED.** Store in the existing
  SQLCipher-protected Room database; require schema migration, key lifecycle,
  device-transfer and no plaintext log verification.
- **Legacy data — UNDECIDED.** Select an explicit sync cutover time or an
  audited historical backfill with checksum reconciliation; do not infer old
  cashier/shift attribution.
- **Cross-device limit collisions — UNDECIDED.** For single-use coupons, credit
  exposure limits and stock, choose reserve-online or provisional acceptance
  with explicit flags. Never pretend stale offline balances are authoritative.
- **Customer personal data — UNDECIDED.** Prefer data minimization: financial
  IDs and essential snapshots only; names, phones and invoices leave device
  only with a defined processing purpose, retention/deletion policy, access
  model, encryption, breach response and cross-border assessment against
  Oman's Personal Data Protection Law and its applicable regulations. Legal
  review required before deployment.
- **Canonical representation — UNDECIDED.** JSONB equality is semantic, not
  byte canonicalization. Specify stable UTF-8 encoding, field ordering,
  numeric typing, timezone and schema evolution before using the staged SQL.
- **Server date integrity — UNDECIDED.** Server must independently derive
  business_local_date from an authoritative branch timezone snapshot; reject
  forged dates.
- **Authorization for cashier accounts — UNDECIDED.** Current remote
  employee activation is not itself sufficient proof of authenticated
  financial-upload authority. Define revocable server-verifiable credentials.
- **Payload operation allowlist — UNDECIDED.** Staged SQL lists current
  financial operation kinds; validate complete compatibility with all
  production command types before applying anything.

## Staging / rollout guard
- Kotlin contract and tests are inert; no financial activity is uploaded.
- Staged SQL resides at `BOARDLOCK/staged_sql/financial_operation_envelopes.sql`,
  deliberately not in `supabase/migrations/`.
- Do not execute the staged SQL; its constraints and cryptographic derivation
  require independent migration/security review in a disposable test database.
- No live backend operations, Room changes, financial writer changes
  or production configuration changes are allowed in foundation-only work.
- No test result is claimed without the actual Gradle output.
- Do not merge or release based on these foundation commits.

**FOUNDATION ONLY — NOT FUNCTIONAL, NOT DEPLOYED.**
