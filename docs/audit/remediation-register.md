# Code-health remediation register

**Pinned source:** `f4c78e6c052720e270b88e42ce940335e7715069`  
**Status:** PRELIMINARY — opportunities below are evidence-backed review items, not approved changes or confirmed defects.

## Executive summary

The measured subset contains several very large files, including the 8,757-line `RegisterRepository.kt`, 3,502-line `RegisterViewModel.kt`, and 1,243-line `RemoteEnvelopeMaterializer.kt`. The largest files sit on financial command, sync, encryption, Room and reporting boundaries. File size indicates maintenance cost but does not prove incorrectness.

The primary active UI graph is in `DmrApp.kt`; startup and Supabase auth deep-link handling are in `MainActivity.kt`. ADMIN-only route gates are visible in the inspected graph. Hilt, Room and serialization introduce dynamic reachability that prevents safe deletion based on simple text searches.

No production code was modified. No code is recommended for deletion. The audit remains incomplete because full source-set declaration indexing, top-50 line ranking, Kotlin-aware largest-function analysis and complete dynamic dependency tracing were not performed.

## Prioritized register

| Priority | File | Symbol / area | Issue / evidence | Risk | Recommendation | Test impact | Est. PR size |
|---|---|---|---|---|---|---|---|
| Critical | `RegisterRepository.kt` | Sales command paths | 8,757 lines; contains cash sale, credit sale, coupon, repayment, correction, refund, void and recovery entry points | Critical | First map function boundaries and invariants; no extraction until characterization coverage is mapped | Financial integration, idempotency, replay, offline tests | L (analysis first; later split into multiple PRs) |
| Critical | `RemoteEnvelopeMaterializer.kt` | `materializeInboundEnvelope`, `materializeNonTransactionOperation`, `materializeEnvelope` | 1,243 lines; JSON/envelope-heavy inbound materialization | Critical | Trace operation dispatch, ordering, idempotency and evidence writes before proposing extraction | Sync replay, envelope contract, collision/idempotency tests | M |
| Critical | `DatabaseEncryptionMigrator.kt` | Encryption migration lifecycle | 1,111 lines; plaintext/encrypted snapshot verification, integrity checks and atomic move helpers | Critical | Treat as protected; only fix a separately proven defect | Encryption migration and real-data instrumentation tests | L |
| Critical | `DmrDatabase.kt` | Room migration registration | 898 lines; 106 Migration-token occurrences in source | Critical | No size-driven change; map schema and migration registration only | Full migration chain and schema export tests | L |
| High | `RegisterViewModel.kt` | Register orchestration | 3,502 lines | High | Map state/event ownership; separate UI orchestration only after behavior characterization | Register UI and financial flow tests | L |
| High | `RegisterShiftReportReadRepository.kt` | Shift/day-close reads | 898 lines; shift calculations and day-close batch loading | High | Document report inputs and invariants before extraction | Day-close, cash-shift, reporting integration tests | M |
| High | `ReportingRowMappers.kt` | `mapSale`, `mapRefund`, `mapVoid`, `mapCorrection`, `mapCreditRepayment`, `mapExpense` | 830 lines; explicit financial lifecycle row mapping | High | Preserve field mapping and exact amount semantics; consider grouping only after mapping tests | Reporting mapper and financial reconciliation tests | M |
| High | `CustomersViewModel.kt` | Customer workflow orchestration | 846 lines | Medium–High | Map customer loading/filtering/payment-related responsibilities | Customer directory and payment-flow tests | M |
| Medium | `CustomerDetailScreen.kt` | Customer detail UI | 1,661 lines | Medium | Inspect composable boundaries and local state; presentation-only extraction | Compose UI tests and screenshot/interaction checks | M |
| Medium | `CouponCompactDialogR7A.kt` | Coupon UI | 1,898 lines | Medium | Identify cohesive presentation sections; keep command/business logic outside UI | Coupon purchase/top-up UI and integration tests | M |
| Medium | `RegisterExpensePresentation.kt` | Expense presentation | 1,338 lines | Medium | Separate reusable presentation pieces only | Expense UI tests | S–M |
| Medium | `RegisterOverlayHost.kt` | Overlay composition | 1,092 lines | Medium | Review overlay selection and wiring boundaries | Register navigation/overlay tests | S–M |
| Medium | `ReportingScreen.kt` | Reporting UI | 892 lines | Medium | Map filter/summary/activity composables | Reporting UI tests | S–M |
| Low | `DmrApp.kt` | Obsolete cashier-session comment | Comment says cashier sessions are no longer admitted; current surrounding route gate is ADMIN-only | Low | Optional comment wording cleanup only; no behavior change | None expected; diff review | XS |
| Low | Source inventory | Full per-file metadata | 382 source files indexed; per-file measurements incomplete | Low | Complete line/package/declaration inventory before approving cleanup candidates | Audit verification only | M (analysis) |
| Low | Large-function analysis | >800-line files | Top-20 function-body rankings not measured with Kotlin-aware parser | Low | Generate syntax-aware function span report | Audit verification only | M (analysis) |
| Low | Hilt graph | Providers and consumers | MainActivity injection and DmrApp ViewModel use observed; complete graph absent | Medium | Build complete binding/consumer graph before dead-binding decisions | Hilt compile/DI tests | M (analysis) |
| Low | Navigation | Routes and deep links | Main NavHost and Activity auth deep-link calls traced; manifest closure incomplete | Medium | Complete manifest/route/deep-link crosswalk | Navigation/auth deep-link tests | M (analysis) |
| Low | Serialization | JSON and persisted dispatch | Remote materializer is JSON-centric; adapters/reflection/persisted keys not fully indexed | High | Inventory serializer adapters and persisted operation/type strings | Serialization compatibility and sync replay tests | M (analysis) |
| Low | Unused declarations | All visibility levels | No declarations proven unreferenced; complete cross-source-set indexing outstanding | High | Do not delete; finish semantic/dynamic reference analysis first | Tests for any eventual candidate | M (analysis) |

## Top 20 improvement opportunities

The 20 register rows above are investigation/remediation opportunities, not 20 confirmed defects. The strongest maintainability opportunities are:
1. Map the financial command boundaries in `RegisterRepository.kt`.
2. Map register UI state and event ownership in `RegisterViewModel.kt`.
3. Trace inbound envelope operation dispatch and evidence application.
4. Map encryption migration phases and invariants.
5. Map Room migration registration and schema dependencies.
6. Characterize shift/day-close report reads.
7. Lock down reporting mapper field/amount contracts.
8. Map customer workflow responsibilities.
9. Identify composable sections in customer detail.
10. Separate coupon UI presentation boundaries.
11. Separate expense presentation components.
12. Clarify register overlay selection/wiring.
13. Identify reporting screen filter/summary/activity boundaries.
14. Complete source-set line inventory.
15. Complete Kotlin-aware largest-function ranking.
16. Complete Hilt provider/consumer graph.
17. Complete Compose route/deep-link/manifest crosswalk.
18. Inventory Room annotation and migration reachability.
19. Inventory serializers, adapters and persisted dispatch keys.
20. Complete unused-symbol analysis before any deletion.

## Safe-first sequence

1. Complete missing audit measurements and dynamic-reference tracing.
2. Optional comment-only cleanup in `DmrApp.kt`, if still accurate at review time.
3. Presentation-only UI decomposition in small isolated PRs.
4. ViewModel orchestration decomposition after tests characterize state/events.
5. Financial repository and reporting extraction only after characterization tests and human review.
6. Keep encryption and Room migration code protected; no opportunistic cleanup.

## High-risk characterization-test gates

Before touching the corresponding files, require:
- Cash sale: exact settlement split, stock/ledger effects, duplicate submission and offline replay.
- Refund/void: partial/full refund, unpaid credit return, settlement behavior, audit reason and idempotent replay.
- Credit: credit sale, repayments, allocation, outstanding balance and coupon-credit interactions.
- Amount/payment correction: persisted command replay, collision behavior, exact amount arithmetic and ledger reconciliation.
- Cash shift/day close: open/count/close lifecycle, external movements, expected cash and report totals.
- Remote materialization: operation ordering, tenant/actor identity, duplicate envelopes, changed digest collision, retry and evidence application.
- Reporting: sale/refund/void/correction/repayment/expense row parity and exact sums.
- Encryption/Room: every supported migration path, interrupted migration recovery, encrypted/plaintext integrity and real-data fixtures.

## Estimated remediation PR count

Preliminary estimate: **8–12 PRs**, excluding audit-only work:
- 3–5 presentation/UI decomposition PRs;
- 1–2 ViewModel decomposition PRs;
- 2–3 financial/sync/reporting extraction PRs, each isolated by responsibility;
- 0–1 comment-only cleanup PR;
- encryption/database work is not included unless a separately proven defect justifies it.

This is a planning estimate, not a commitment. A completed function/dependency audit may change the count.

## Completion blockers

This register must not be treated as a completed Batch A sign-off until the following are produced: complete per-file inventory and top-50 ranking; all >800-line file class/function counts and top-20 function rankings; source-set-wide symbol indexing; complete Hilt/Compose/Room/serialization/reflection/deep-link tracing; and reviewed responsibility maps for the four specified financial files.
