# BOARDLOCK — Supabase Connection Audit Matrix

**Audited source:** `migration/supabase-edge-only` at `6a45e5b04ec975df4142428cd86ef0b83d5724e6`  
**Working redesign branch:** `migration/offline-first-supabase-v1`  
**Live project:** `ttvdpgxqvvtjnxxfkoui`  
**Audit date:** 2026-10-09  
**Scope:** Read-only source and database metadata inspection. No production rows/schema/functions changed.

## 1. End-to-end route matrix

| Flow | Android entry/source | Remote path | Database contract | Current assessment |
|---|---|---|---|---|
| App Supabase configuration | `app/build.gradle.kts`, `SupabaseModule.kt` | Supabase project URL and publishable key | Auth/PostgREST configuration | Present; publishable key is client config, not a secret |
| Admin financial upload | `RemoteFinancialSyncRepository.uploadEnvelope` | Supabase Kotlin SDK → PostgREST RPC | `public.ingest_financial_envelope` | Separate from cashier path; verify server grants/body and actual client auth |
| Cashier login | `CashierCloudSession` | Edge Function `POST /api/staff/login` | `cashier_login_credential`, `fetch_cashier_restoration`, `create_cashier_sync_session` through service role | Implemented custom session flow; protected by Edge Function checks; requires route-level contract tests |
| Cashier financial upload | `RemoteFinancialSyncRepository.uploadEnvelope` | Edge Function `POST /api/financial/upload` | `public.ingest_cashier_financial_envelope` | Implemented for the current cashier-supported operation allowlist; different server path from admin |
| Admin financial download | `RemoteFinancialSyncRepository` / sync engine | Supabase Kotlin SDK → PostgREST RPC | `public.fetch_financial_envelopes_v2` | Cursor uses received timestamp + operation ID; local application is incomplete/gated |
| Cashier financial download | `CashierCloudSession.download` / sync repository | Edge Function financial download route | `public.fetch_cashier_financial_envelopes` via service role | Server RPC is scoped to active cashier membership/branch; Edge route must validate the bearer session and pass only verified scope |
| Local outbox creation | Financial command/repository enqueue paths; `FinancialSyncOutboxEntity` and DAO | Room only until scheduler uploads | Room operation + `financial_sync_outbox` | Entity/DAO exist; audit must prove atomic enqueue for every operation type |
| Outbound orchestration | `FinancialSyncEngine.syncOutbound` | Admin RPC or cashier Edge Function depending on role | Ingest receipt | Retry and receipt digest checks exist; support differs by role/type |
| Inbound materialization | `FinancialSyncEngine.syncInbound`, `InboundEnvelopeApplier`, `RemoteEnvelopeMaterializer` | Download RPC/Edge route | Envelopes in `private.financial_operation_envelopes` | Not complete for all operation types; build flag defaults false |
| Notification/catch-up | `FinancialSyncScheduler` | 15-second active-session loop + explicit trigger | Query-based download | Polling exists; Realtime is not implemented as the authority and must not replace catch-up queries |

## 2. Confirmed operation support policy in current source

The current Kotlin policy declares the following admin uploadable operations:
- `RECORD_SALE`
- `RECORD_CREDIT_REPAYMENT`
- `RECORD_COUPON_PURCHASE`
- `RECORD_AMOUNT_CORRECTION`
- `RECORD_PAYMENT_METHOD_CHANGE`
- `RECORD_VOID`
- `RECORD_REFUND`
- `RECORD_EXPENSE`
- `RECORD_EXPENSE_CORRECTION`
- `RECORD_EXPENSE_VOID`
- `OPEN_CASH_SHIFT`
- `COUNT_CASH_SHIFT`
- `CLOSE_CASH_SHIFT`

The current Kotlin policy declares the following cashier uploadable operations:
- `RECORD_SALE`
- `RECORD_CREDIT_REPAYMENT`
- `RECORD_COUPON_PURCHASE`
- `RECORD_REFUND`
- `RECORD_VOID`
- `RECORD_AMOUNT_CORRECTION`
- `RECORD_PAYMENT_METHOD_CHANGE`

The live cashier intake RPC uses a corresponding allowlist and permission mapping. It rejects other types. Do not broaden either allowlist until the canonical payload and server-side validation are proven for each type. The client policy is not proof that an operation's payload is complete or inbound materialization is supported.

## 3. Confirmed live database facts

- `public.fetch_financial_envelopes_v2` checks `auth.uid()`, active admin membership, active business/branch, and paginates by the tuple `(received_at, operation_id)`.
- `public.fetch_cashier_financial_envelopes` is granted to `service_role` and checks active cashier membership, business, branch, and a bounded page size; the Edge Function must verify the custom session before invoking it.
- `public.ingest_cashier_financial_envelope` validates membership and per-operation permissions, canonical SHA-256, payload JSON equality, operation/business identity, and idempotent insert/conflict behavior.
- `public.create_cashier_sync_session` accepts a membership ID plus token hash and inserts a 12-hour session after checking active cashier membership. Its only valid caller should be the trusted Edge Function service role.
- The live audit previously observed 26 envelope rows (23 `RECORD_SALE`, 3 `OPEN_CASH_SHIFT`). Recount read-only before any data migration; counts may have changed.
- Security Advisor: six private tables with RLS enabled and no policies; one SECURITY DEFINER RPC callable by `anon`; eleven callable by `authenticated`. Review each finding in context; do not bulk revoke grants without tracing callers.
- Performance Advisor: two unindexed foreign keys. These are optimization findings, not evidence of data loss.

## 4. Critical gaps before enabling full sync

1. No single admin/cashier server intake contract despite overlapping payload fields.
2. Outbound enqueue atomicity has not yet been proven for every operation family.
3. Role-specific operation allowlists leave cash-shift and expense events unavailable to cashier upload.
4. Inbound materializer does not support all financial operation types.
5. Inbound application is disabled by default; enabling it wholesale would be unsafe.
6. Durable cursor and materialization checkpoint must be coordinated so a crash cannot skip unapplied records.
7. Need contract tests for every protected Edge route because `verify_jwt=false` is configured for the function.
8. SECURITY DEFINER grants need function-by-function review, particularly `preview_staff_invite` and financial fetch/intake functions.
9. Must reconcile current cloud envelopes and local operations before backfill or schema changes.
10. Need physical-device offline/restart/reconnect and cross-device reconciliation evidence.

## 5. Execution status

- [x] Working branch isolated from source branch.
- [x] Migration plan written.
- [x] Source/configuration and live database RPC metadata inspected.
- [x] Current operation allowlists and split upload/download paths documented.
- [ ] Trace every financial command to its atomic Room enqueue path.
- [ ] Review complete Edge Function routing and session validation tests.
- [ ] Finalize versioned wire contract and server intake parity.
- [ ] Implement and test changes on the working branch.
- [ ] Run Android Gradle tests/build and Deno tests in an available runner.
- [ ] Verify two physical devices and reconcile existing records.
- [ ] Production release gate.

## 6. Safety boundary

This matrix is a read-only audit artifact. It does not authorize or imply a production schema migration, Edge Function deployment, data backfill, deletion, or merge. Implementation work belongs on the isolated redesign branch; production rollout remains blocked until the release evidence exists.
