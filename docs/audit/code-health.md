# Code Health Audit — Current Main

## Status

**INCOMPLETE — preliminary static review only.** This report deliberately does not claim that the requested full audit has been completed. No application, Gradle, schema, or resource file was changed.

- Repository: `guruoshan/Paid-Due`
- Base audited: `main`
- Audited commit: `f4c78e6c052720e270b88e42ce940335e7715069`
- Report branch: `audit/code-health-main-20261031`
- Search method: GitHub repository tree at the pinned SHA; exact-file reads for governance, release checklist, selected large Kotlin files and the debug bootstrapper; repository code searches for `DebugSessionBootstrapper`, `CASHIER`, `StaffInvite`, `TODO FIXME HACK`, `FLAG_DEBUGGABLE`, `@Composable`, `@Provides @Binds`, and `@Preview`. Line counts below come from the fetched source text, not byte-size estimates.

## Governance read

Read `AGENTS.md`, `PROJECT_GOVERNANCE.md` §§1, 2, 3A, 9, 12 and 14, and `RELEASE_CHECKLIST.md` from the audited `main` commit. No instruction conflict was identified.

Applicable constraints: preserve immutable financial history and the single financial source of truth; preserve exact money arithmetic, idempotency and offline/sync behavior; keep dependencies/assets light; do not weaken security or tests; and require human approval before high-risk changes. This was a read-only audit. No device, emulator, or Supabase command was run.

## Source inventory

The recursive Git tree at the audited SHA was not truncated. It contains **242 Kotlin/Java files under `app/src/main`**, **89 under `app/src/test`**, and **51 under `app/src/androidTest`**.

**Important limitation:** the available repository inspection path did not permit a complete, reliable fetch and parse of all 242 production source files plus both test source sets in this pass. Therefore this report cannot truthfully provide the requested line count for every file, a complete declaration inventory, or exhaustive symbol-reference counts. The god-file table is a confirmed subset, not a complete list.

## Part 1 — God files (confirmed subset)

Line counts are exact for the source files individually fetched at the audited SHA. Declaration/function statistics are omitted where a syntax-aware parser was not available; naive brace counting produced incorrect ranges for Kotlin expression bodies and nested lambdas, so those figures are not represented as facts.

| File | Lines | Main responsibilities observed / scoped review |
|---|---:|---|
| `feature/register/data/RegisterRepository.kt` | 8,757 | Financial command orchestration and repository operations: cash sales, refunds, credit repayment, amount corrections, replay/recovery paths and credit-return planning. Contains very large functions; the refund/replay paths warrant decomposition review under §6, but financial behavior is high-risk. |
| `feature/register/ui/RegisterViewModel.kt` | 3,502 | Register screen state and orchestration for sales, refunds, corrections, credit sales, expenses and day closing. Large UI coordinator; any split must preserve command boundaries and financial invariants. |
| `feature/register/ui/CouponCompactDialogR7A.kt` | 1,898 | Coupon purchase/top-up dialog and associated UI presentation. |
| `feature/customers/ui/CustomerDetailScreen.kt` | 1,661 | Customer detail UI, invoice history, receive-payment dialog and WhatsApp interaction. |
| `feature/register/ui/RegisterExpensePresentation.kt` | 1,338 | Expense detail and expense dialog presentation, including supporting evidence and labels. |
| `core/cloud/sync/RemoteEnvelopeMaterializer.kt` | 1,243 | Inbound remote-envelope materialization and non-transaction operation handling. **Sync/financial review required** before any structural change. |
| `feature/register/ui/RegisterOverlayHost.kt` | 1,092 | Register overlay/dialog composition and presentation wiring. |
| `core/database/security/DatabaseEncryptionMigrator.kt` | 1,111 | Database encryption migration/key-state handling. **Security/migration review required** before any change. |
| `core/database/DmrDatabase.kt` | 898 | Room database definition, migrations, database construction and migration registration. **Migration review required** before any change. |
| `feature/register/data/RegisterShiftReportReadRepository.kt` | 898 | Cash-shift/day-close reporting read paths and related projections. Financial reporting; human review required before structural changes. |
| `feature/reporting/ui/ReportingScreen.kt` | 892 | Reporting screen, period/scope selectors, filters, summary and activity-row presentation. |
| `feature/customers/ui/CustomersViewModel.kt` | 846 | Customer list/detail workflow coordination, filtering, invoice linking and receive-payment flow. |
| `feature/reporting/data/ReportingRowMappers.kt` | 830 | Maps authoritative financial/lifecycle records into reporting rows. Financial read-model code; human review required before changes. |

Other individually fetched files below 800 lines are not included in this table. The full requested all-file line-count table and the exact top-10 function ranking for each god file remain unchecked.

## Part 2 — Unused-code candidates

No declaration is classified as `CONFIRMED_UNREFERENCED` or `UNREFERENCED_DYNAMIC_RISK` in this incomplete pass. A search result absence is not proof of non-use, and the full source-set reference count was not established for individual declarations.

| File | Line | Name | Classification | Evidence / possible dynamic path |
|---|---:|---|---|---|
| `core/security/DebugSessionBootstrapper.kt` | 19 | `DebugSessionBootstrapper` | `KEEP` | Direct references found in `MainActivity.kt` (injected and passed to `DmrApp`) and `DmrApp.kt` (startup path calls `bootstrapIfDebug()`). Its implementation checks `ApplicationInfo.FLAG_DEBUGGABLE` and returns false when not debuggable. It is production-source code but not evidence of a release authentication bypass. Keep the guard and release configuration protected by tests. |

**Not assessed to completion:** private declarations, internal/public declarations across all three source sets, Hilt bindings and injection sites, composable call sites, constants/enums/sealed subtypes, and files consisting only of unreferenced declarations. These require full-source symbol indexing and annotation-aware analysis. Room entities/DAOs, Gson/serialization adapters, reflection, generated Hilt wiring, and Compose preview tooling can make simple text search incomplete.

## Part 3 — Irrelevant-code review

| File | Line | Reason | Reachability evidence |
|---|---:|---|---|
| `app/src/main/java/com/dailymoneyregister/core/security/DebugSessionBootstrapper.kt` | 19 | Development-only authentication convenience in production source; verify release guard, do not treat its mere presence as a release bypass. | Reachable from the application startup path: `MainActivity.onCreate()` injects it and passes it to `DmrApp`; `DmrApp` calls `bootstrapIfDebug()` only after setup is complete. The method checks the app debuggable flag and exits for non-debuggable builds. Classification: `KEEP`. |
| `app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt` | 105 (approx.; source excerpt) | Contains an obsolete cashier-session comment adjacent to the current ADMIN-only trusted-session gate. This is comment cleanup, not evidence of a live cashier feature. | The active startup path admits restored ADMIN sessions; non-admin restored sessions are cleared. Login and ADMIN_HUB/REGISTER routes also reject non-admin sessions. No cashier management or staff-invite navigation route appears in the inspected `Routes`/NavHost. Do not remove historical actor, sync, migration, or compatibility data based on this UI observation. |
| `app/src/main/java/com/dailymoneyregister/feature/admin/**` and `feature/invite/**` | — | Candidate retired cashier/staff/invite UI families. | The audited `app/src/main` tree contains no Kotlin/Java files in these directories. Repository search results for these names surfaced legacy documentation and database migration artifacts, not a current app route. This supports that the old screen families are absent from the current production source tree; it does not prove that every invite/deep-link, stored-record, serialization, or backend compatibility path is absent. No source declaration is classified `RETIRED_FEATURE` on this evidence alone. |

The search for `TODO FIXME HACK` in production source returned no indexed hits, but this is not an exhaustive line-by-line scan. The age of any comment relative to the current release cannot be established without complete matches and blame/history inspection. No 10+ line commented-out blocks or always-on/always-off feature flags were confirmed in this pass.

## Part 4 — Classification counts

Counts below cover only the individually classified item(s) in this preliminary report; they are **not repository-wide totals**.

| Classification | Count |
|---|---:|
| `CONFIRMED_UNREFERENCED` | 0 |
| `UNREFERENCED_DYNAMIC_RISK` | 0 |
| `RETIRED_FEATURE` | 0 |
| `REVIEW_REQUIRED` | 0 |
| `KEEP` | 1 |

God-file size is a maintainability observation, not an authorization to remove or refactor. Financial, security, sync and migration files must receive `REVIEW_REQUIRED` if they become removal/refactor candidates, regardless of reference counts.

## Part 5 — Not checked / reasons

1. Exact line counts for all 242 Kotlin/Java production files.
2. Syntax-aware top-level declaration counts and the top 10 largest functions for every file above 800 lines.
3. Exhaustive private/internal/public symbol search across main, test and androidTest.
4. Hilt graph reachability from all injection sites, including generated bindings.
5. Full composable call graph and Compose Preview usage.
6. All constants, enum entries and sealed subtypes against constructors, exhaustive `when` branches and persisted values.
7. Reflection/serialization/Room/Gson name-based access and stored-string dispatch.
8. Every commented-out block of 10+ lines.
9. TODO/FIXME/HACK line locations and their introduction dates relative to the current release.
10. Feature-flag values across build variants and runtime configuration.
11. A complete screen/route reachability graph for legacy staff/invite behavior.

These checks were not represented as complete because the available GitHub search interface returns bounded excerpts and does not provide a full local checkout/compiler index or syntax-aware cross-source-set analysis in this run. No application code was modified to compensate.

## Completeness statement

**The list is NOT complete.** Static text search alone cannot prove absence of DI-generated references, reflection, Room/Gson/serialization access, Compose navigation/previews, persisted enum/string dispatch, or runtime/build-variant reachability. Full completion requires a pinned checkout of this exact SHA, source-set-wide indexed search, Kotlin-aware parsing, and manual tracing of dynamic entry points. No item in this report is marked safe to delete; any removal requires human review and approval under governance §14.
