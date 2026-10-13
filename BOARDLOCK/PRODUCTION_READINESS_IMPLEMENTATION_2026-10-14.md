# Paid&Due Production Readiness Implementation Log — 2026-10-14

Status: IN PROGRESS — NOT A RELEASE CANDIDATE

Branch: `fix/production-readiness-financial-sync`
Pull request: #37

## Implemented in this branch

- Replaced cash-only outbox trigger admission with operation-family admission for the existing supported families.
- Aligned cashier shift lifecycle outbox classification with the server's existing accepted operation policy.
- Updated outbox instrumentation tests for online and mixed-tender sales and trigger replacement.
- Changed the Ktor client engine from Android to CIO because the Android engine does not support WebSockets required by Supabase Realtime.
- Added a Room-backed, credential-free cloud staff identity projection; synchronizes Admin-visible staff identities before inbound financial processing and validates remote actors against scoped mappings without creating login accounts.
- Added cashier metadata refresh before financial sync. Permission values are refreshed from the server, and revoked/branch-moved/role-changed sessions are cleared fail-closed.
- Added a configurable customer-directory permission to the local membership model, cashier management UI/payload mapping, API permission allowlists, and a Supabase migration for server-side enforcement of customer-directory sync RPCs.

## Explicitly not yet verified

- CI on the final commit.
- Execution of the new Supabase migration against a disposable full-schema database.
- Physical-device Admin↔Cashier sync and the previously rejected sale's exact-once application and replay behavior.
- Durable rejected-envelope quarantine/recovery that allows unrelated envelopes to progress without losing or silently skipping rejected evidence.
- Full end-to-end support for every financial operation family, including expense/cash movement, through upload, server acceptance, download, and atomic materialization.
- Comprehensive branch/business isolation, permission revocation, offline, crash recovery, replay, migration, and reconciliation tests.
- Function-by-function review of exposed SECURITY DEFINER functions, confirmation of safe grants, and enabling leaked-password protection in Supabase Auth.

## Data integrity

No production migration was applied, no Edge Function was deployed, no production financial data was changed, and no historical actor IDs, membership IDs, operation IDs, checksums, or original payloads were modified by this work.

## Release gate

Do not merge or deploy this branch until CI is green, the migration and Edge Functions are verified in an isolated environment, the previously rejected operation is proven to apply once and replay safely, and all security blockers are closed with evidence.
