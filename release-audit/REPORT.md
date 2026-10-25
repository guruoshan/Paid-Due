# Paid&Due Release-Readiness Audit

**Audited ref:** `main` (repository default branch, observed 2026-10-25)  
**Audit branch:** `audit/release-readiness-2026-10-25`  
**Scope:** read-only review. No application files or Supabase data were changed. This report intentionally excludes security-sensitive findings; those are communicated only in chat.

## Confidence and limitations

This is a source/configuration and GitHub metadata review, not a certified build or a full runtime certification. Files and selected source excerpts were fetched from `main`; GitHub search was used to locate code/tests. A full unit/instrumentation suite, release artifact inspection, Actions billing usage, and physical multi-device tests were not executed. Findings depending on those checks are explicitly marked CANNOT CHECK or LIKELY. No temporary scanner workflow was added because the available session did not establish safe, verifiable pinned scanner action revisions and execution conditions.

Every finding has an ID, evidence, verification method, and status.

## Phase 0 — Inventory

- Repository: `guruoshan/Paid-Due`, public, default branch `main`; GitHub metadata reports repository size 3,797 KB.
- Android project uses Gradle Kotlin DSL, one `:app` module, Kotlin/Jetpack Compose, Room, Hilt, KSP, SQLCipher and Supabase.
- `settings.gradle.kts`: root project name is `Daily Money Register`, includes `:app`.
- `app/build.gradle.kts`: namespace/applicationId `com.dailymoneyregister`, compile SDK 37, min SDK 26, target SDK 37. Version code/name derive from `GITHUB_RUN_NUMBER`, with local fallback 1 / `1.0.0`.
- `gradle/libs.versions.toml`: Android Gradle Plugin 9.4.0, Kotlin 2.2.10, Room 2.7.2, SQLCipher Android 4.19.0, Compose BOM 2026.02.01, Hilt 2.60.1.
- `RELEASE_CHECKLIST.md` exists and includes financial integrity, tests, security/privacy, build/size, Android compatibility, localization, signing and final approval.
- Open PR inventory surfaced #77 (localization workflow), #72 (collision evidence), #68 (expense correction/void reporting), plus older #64/#63/#60 and several older drafts. See Phase 1 and the chat summary for recommendations.
- Workflow inventory is incomplete because the available GitHub connector did not provide a full directory listing. A temporary workflow was not added; therefore no scan run is claimed.

## Phase 1 — Ghost files, code and workflow inventory

### A-001 — Legacy cashier UI text remains in base/localized resources
**Status: CONFIRMED**  
**Evidence:** `app/src/main/res/values/strings.xml`, `values-ar/strings.xml`, `values-ur/strings.xml`, `values-b+bn/strings.xml` contain cashier login, cashier creation/management, PIN, permissions and related copy. Supabase migration `supabase/migrations/20261023050000_retire_cashier_feature.sql` retires server-side cashier records but also references deletion of cashier session/invite/permission-request rows.  
**Verification:** fetched the base and locale string files and searched for cashier session identifiers and retirement migration.  
**Assessment:** The resources prove legacy strings remain; they do not prove all associated UI code is reachable. Do not delete based on string search alone.

### A-002 — Lint identifies unused-resource candidates; semantic reachability still requires review
**Status: CONFIRMED scan results; deletion status CANNOT CHECK**  
**Evidence:** committed `release-audit/scan-results.md` reports 109 `UnusedResources` lint issues and lists candidates. It also reports 131 zero/one-mention Kotlin declarations and 232 filename-based unreferenced-file candidates.  
**Verification:** successful `:app:lintDebug` workflow run 40228223025 and committed scan report.  
**Recommendation:** inspect each candidate against manifests, generated references, reflection/dynamic lookup, locale overlays, Gradle, tests and runtime paths before any deletion. No cleanup was performed.

### A-003 — Branch and PR inventory contains substantial stale-looking work
**Status: CONFIRMED**  
**Evidence:** GitHub branch search listed many long-lived branches, including `feature/remove-cashier-facing-access`, multiple `fix/*` branches, `ci-smoke-test`, `feature/customer-excel-export`, and audit/diagnostic branches. Open PR search surfaced #77, #72, #68, #64, #63, #60 and older PRs including #49, #43, #36, #25, #23, #20, #18, #14, #13, #12, #10, #8 and #1.  
**Verification:** GitHub branch and open-PR searches; results may be paginated/connector-limited.  
**Recommendation:** reconcile each open PR against current main and close/archive only after owner review. Do not delete branches based on age alone.

### A-004 — Workflow inventory is complete; 14-day job-minute total is blocked
**Status: PARTIAL**  
**Evidence:** the main branch has 12 workflow files. Their triggers, all `uses:` lines, token permissions and explicit secret references were inspected. All 20 open PRs and 95 branches were also inventoried.  
**Verification:** recursive GitHub repository tree, workflow source retrieval, Actions run API.  
**Limit:** the available connector rejects workflow-specific run/timing endpoints and the repository run list is capped at 2,500 records for the filtered query. Complete per-workflow job-minute totals cannot be computed reliably here; see chat for the full workflow security inventory and exact blocker.

## Phase 2 — Android release readiness

### A-005 — Version fallback can produce a non-release version identity
**Status: CONFIRMED**  
**Evidence:** `app/build.gradle.kts` sets `versionCode = GITHUB_RUN_NUMBER ?: 1` and `versionName = "1.0.$runNumber" ?: "1.0.0"`.  
**Verification:** direct source inspection.  
**Risk:** builds outside Actions can share versionCode 1/versionName 1.0.0, and Actions run numbers are not a deliberate semantic release policy. Verify the signed artifact metadata for every distribution build.

### A-006 — Release shrinking and packaged build properties not verified
**Status: CANNOT CHECK**  
**Evidence:** the fetched Gradle excerpt did not include the complete build-type configuration; no local Gradle task ran. `RELEASE_CHECKLIST.md` explicitly requires checking minification, R8 rules, release debuggability and resource shrinking.  
**Verification:** checklist and partial Gradle inspection; no artifact inspection.  
**Recommendation:** inspect the full `buildTypes` and release APK/AAB, run release lint/build, and verify `debuggable=false`, mapping/keep rules, packaged resources, ABI and native SQLCipher compatibility.

### A-007 — App branding differs by context
**Status: CONFIRMED**  
**Evidence:** `settings.gradle.kts` names the root project `Daily Money Register`; base `values/strings.xml` names the app `Paid&Due`; Hindi, Bengali, Arabic and Urdu string resources use translations/transliterations of “Daily Money Register”.  
**Verification:** fetched these source files.  
**Risk:** user-facing identity is inconsistent between English, localized locales and build metadata. Confirm the intended product/locale naming before changing copy.

### A-008 — In-app updater depends on public GitHub releases and exact tag/asset naming
**Status: CONFIRMED**  
**Evidence:** `ReleaseUpdater.kt:22-86` calls the latest stable GitHub release endpoint, expects `v1.0.N` tags and an asset named `paid-due-v{tag}.apk`.  
**Assessment:** the updater is coupled to public repository access and the release naming convention. Making the repository private would break unauthenticated update discovery/download unless a supported authenticated distribution path is introduced.

### A-010 — Raw update errors can be shown to users
**Status: CONFIRMED**  
**Evidence:** `ReleaseUpdateCard.kt` displays `error.message` when an exception is thrown, falling back to a localized generic message only when no message exists.  
**Verification:** direct source inspection.  
**Risk:** raw technical/server errors may appear in the UI and confuse users. Map failures to stable localized user messages and retain diagnostics separately.

## Phase 4 — Data and sync health

### A-012 — Operation collision is an intentional fail-closed server condition; recovery path needs proof
**Status: CONFIRMED**  
**Evidence:** migrations including `supabase/migrations/20261009061446_add_admin_ingest_financial_envelope.sql` compare the existing operation digest and raise `OPERATION_COLLISION` when the same business/operation identity has a different digest. Current update UI/source references collision as a raw exception candidate; user reports describe stale totals and collision on multiple Admin devices.  
**Verification:** GitHub code search of migration definitions and inspected update error handling; user-reported runtime symptoms are not independently reproduced here.  
**Risk:** silently retrying cannot safely resolve a genuine identity/digest mismatch; users need a clear non-destructive recovery path and support diagnostics. Do not weaken idempotency or collision checks.

### A-013 — Safe test-data reset must account for immutable-envelope trigger
**Status: CANNOT CHECK**  
**Evidence:** repository history and user-provided context identify an immutable-envelope trigger and test-data reset requirement; this audit did not connect to Supabase or inspect live trigger definitions.  
**Verification:** no live database query executed.  
**Recommendation:** use an explicitly approved, single-transaction reset procedure that temporarily handles only the intended immutability trigger, deletes only agreed test tables, restores the trigger even on failure, and verifies row counts in-transaction. Validate FK dependencies and trigger semantics against the live schema before execution. No reset was performed.

### A-014 — Migration source and deployed schema drift need reconciliation
**Status: LIKELY**  
**Evidence:** older PR descriptions in GitHub search document migration-history mismatches and a notification migration syntax concern; they are historical PR statements, not proof that the current live schema remains mismatched. The repository contains many timestamped migrations and multiple captured/reconciliation migrations.  
**Verification:** inspected available migration names and PR descriptions; did not query live migration history.  
**Recommendation:** compare `supabase migration list` with every local migration, confirm missing applied versions from deployment evidence, and run migration validation on a disposable schema before any deployment.

### A-015 — Notification and PDF timestamps use different timezone presentation paths
**Status: CONFIRMED source behavior; phone rendering not independently reproduced**  
**Evidence:** the notification SQL stores `closed_at`, `received_at` and `branch_timezone_id` in the payload (`20261015183613_fix_cashier_expense_sync_and_day_close_notifications.sql:238-250`). `NotificationsScreen.kt:154` displays `notification.createdAt` as a raw serialized timestamp, without converting it. `ReportingPdfActions.kt:109-143` formats report dates in the selected/session branch timezone, with system-default fallback. Other date/time formatters mix branch timezone and device-default timezone; the call-site inventory is in A-040.  
**Recommendation:** format user-facing notification timestamps using the payload's branch timezone, and explicitly choose a consistent timezone for exports and receipts. Keep machine-readable storage in UTC.

## Phase 5 — Money logic

### A-016 — Current main has an append-only expense lifecycle projection; regression certification remains outstanding
**Status: PARTIAL**  
**Evidence:** `ReportingReadRepository.kt:405-450` emits the original expense row plus lifecycle event rows. `ReportingRowMappers.kt:746-825` maps correction/void events to signed deltas. This is the intended replacement-and-reversal shape, but the open PR #68 still needs comparison against main to determine whether it contains additional fixes.  
**Verification:** source trace; no test suite or physical-device reproduction run.  
**Risk:** source structure alone does not prove all time-window, legacy-data and device paths reconcile correctly. Require explicit correction-chain/void regression tests before release.

### A-017 — Expense correction is replacement semantics in current source; device result remains unexplained
**Status: SOURCE TRACE CONFIRMED; DEVICE CAUSE BLOCKED**  
**Evidence:** `ExpenseFinancialRepository.kt:84-107` selects the latest non-void corrected amount, stores previous/new values and computes correction delta as previous minus next. A later void uses the effective amount. The same implementation is present at tag `v1.0.152`; it implies 0.599, not 5.599, for the described sequence.  
**Blocker:** the installed phone's exact local expense event/settlement rows and exact installed APK identity were not available read-only in this audit, so the observed 5.599 display cannot be tied to a specific stored row or UI path.

### A-018 — Day Closing counters omit expense lifecycle operations
**Status: CONFIRMED**  
**Evidence:** `RegisterShiftReportReadRepository.kt:662-704` increments `voidCount` for `RECORD_VOID` and `correctionCount` for `RECORD_AMOUNT_CORRECTION`, but not for `RECORD_EXPENSE_VOID` or `RECORD_EXPENSE_CORRECTION`. The expense-void branch also skips its reversal settlement in the monetary loop, which is intentional for avoiding double-counting.  
**Conclusion:** when the performed lifecycle actions are expense corrections/voids, the displayed counters can remain zero even though the operations exist.

### A-019 — Reporting PDF expected cash remains null in the current aggregation path
**Status: CONFIRMED**  
**Evidence:** `ReportingPdfActions.kt:152-156` shows the not-available label when `summary.expectedCash` is null. `ReportingModels.kt:649-650` defaults it to null, and `ReportingAggregator.kt:243-285` does not populate it. Day Closing calculates expected cash separately in `RegisterShiftReportReadRepository.kt:39-62,777-788`; that value is not wired into the reporting summary.  
**Conclusion:** the current PDF path does not calculate/populate expected cash; there is no runtime condition in this path that makes it available.

### A-020 — Cross-screen money consistency not certified
**Status: CANNOT CHECK**  
**Evidence:** report aggregation is separated from the Room read repository by design; code comments in `ReportingReadRepository.kt` say it supplies detailed rows/activity counts while the Phase 3 aggregator owns financial summary amounts. Existing test search surfaces recent activity, credit-return and amount-correction tests.  
**Verification:** inspected repository contract and selected test names only; no full suite or oracle run.  
**Risk:** Recent, History, Reports, PDF and Day Closing could diverge on coupon funding, credit creation/repayment, refunds, voids and expense lifecycle.  
**Required proof:** one shared fixture per operation type, assert each surface against a common expected ledger projection, including split tender, partial/full refund, coupon refunds, credit repayment allocation, correction chains, cash-shift close and Oman-local date boundaries.

## Phase 6 — Localization and UX

### A-021 — Locale naming is inconsistent; lint reports untranslated resources
**Status: CONFIRMED**  
**Evidence:** product naming differs between base English (`Paid&Due`) and several localized resource sets (“Daily Money Register”). The completed lint scan reports 36 `Untranslatable` issues and 109 `UnusedResources` issues.  
**Limit:** the scan does not certify full locale parity or establish that each candidate is semantically safe to delete; no resource was removed.

### A-022 — RTL, PDF/receipt font and garbled-text runtime behavior not checked
**Status: CANNOT CHECK**  
**Evidence:** manifest sets `supportsRtl=true`; Arabic and Urdu strings exist; `RELEASE_CHECKLIST.md` explicitly calls for RTL and financial-format review.  
**Verification:** static resource/manifest inspection only.  
**Recommendation:** run layout screenshot tests in Arabic/Urdu and PDF/receipt export tests in all five supported languages; inspect digit direction, currency placement, mixed-script names, glyph coverage, line wrapping and bidi punctuation.

### A-023 — Empty/error-state and raw technical-message review is incomplete
**Status: LIKELY**  
**Evidence:** update card surfaces exception messages directly; full screen-by-screen text inventory was not run.  
**Verification:** source inspection of updater UI only.  
**Recommendation:** audit loading, no-data, offline, permission-denied, sync-conflict and report-not-ready states in every supported language.

## Phase 7 — Build and CI health

### A-024 — Lint passed; unit/instrumentation tests and signed artifact remain unverified
**Status: PARTIAL**  
**Evidence:** the temporary workflow completed `:app:lintDebug` successfully on audit branch run 40228223025. The non-sensitive lint counts are in `release-audit/scan-results.md`.  
**Limit:** this was a debug lint task on the audit branch; no full unit/instrumentation suite, release build, APK/AAB signature inspection or physical-device certification was run.

### A-025 — Release workflow is gated by main push/manual dispatch
**Status: CONFIRMED**  
**Evidence:** `.github/workflows/release.yml` runs on pushes to `main` and manual dispatch. The push path is gated by a `[build-apk]` commit message; manual dispatch requires explicit confirmation.  
**Verification:** direct workflow inspection.  
**Limit:** this audit did not certify a signed artifact or end-to-end release run. Job-minute accounting is addressed separately under A-026.

### A-026 — Workflow inventory is complete; total job-minute accounting is blocked
**Status: PARTIAL**  
**Evidence:** all 12 main-branch workflow files, their triggers and action uses were inspected. Recent API results show repeated PR and push runs across the Android, SQL, notification, customer-directory and repair workflows.  
**Blocker:** the connector rejects workflow-specific run/timing endpoints, and the run-list API caps the filtered response at 2,500 records. Per-run job-detail retrieval across the complete 14-day period exceeds the available tool-call limit; no total minutes are claimed. See chat for the exact limitation and trigger inventory.

## Phase 8 — Release process

### A-027 — Checklist exists, but end-to-end release evidence is not attached to this audit
**Status: CONFIRMED**  
**Evidence:** `RELEASE_CHECKLIST.md` covers the release gate, and `.github/workflows/release.yml` performs Android unit/instrumentation tests, release compilation, Supabase Edge Function tests and release publishing.  
**Verification:** checklist and release workflow source inspection.  
**Limit:** no current release run, Play Console configuration, privacy-policy review or rollback rehearsal was inspected.

### A-028 — Public GitHub release is a distribution dependency for in-app updates
**Status: CONFIRMED**  
**Evidence:** `ReleaseUpdater.kt` uses GitHub's latest-release API and repository release asset URLs.  
**Verification:** source inspection.  
**Recommendation:** keep repository visibility and asset naming conventions as documented release dependencies, or design and test an authenticated alternative before making the repository private.

### A-029 — Versioning/update path is traced; store and rollback readiness remain unverified
**Status: PARTIAL**  
**Evidence:** `app/build.gradle.kts:58-59` uses `GITHUB_RUN_NUMBER` for versionCode/versionName with fallbacks. `ReleaseUpdater.kt:22-86` checks the latest stable GitHub release, expects the tag/asset naming convention and compares the installed version to the latest release. Making the repo private breaks the unauthenticated update lookup/download path unless distribution changes.  
**Limit:** no Play Console listing, staged rollout, rollback rehearsal or migration-recovery artifact was inspected.

## Phase 9 — Priorities and proposed plan

### P0 — Before any real customer

- **A-012 / A-020 (medium-large):** prove multi-device operation identity, collision behavior and consistency across all financial surfaces. Risk: reports can be stale/unavailable and financial operations can remain unacknowledged. Keep collision rejection fail-closed.
- **A-016 / A-017 / A-018 / A-019 (medium-large):** verify expense correction/void projection, Day Closing counts and expected cash position with regression fixtures. Risk: incorrect totals or blocked reports.
- **A-024 / A-027 (medium):** obtain a fresh passing full test/release-build result and inspect the produced distribution artifact before customer distribution. Risk: shipping an unverified build.
- **A-014 / A-013 (large):** reconcile live schema/migration history and document a safe, verified test reset only if needed. Risk: migration failure or unintended financial evidence deletion.

### P1 — Before wide use

- **A-005 (small-medium):** define explicit monotonic release versioning independent of Actions run-number fallback. Risk: duplicate/misleading version identity.
- **A-008 / A-028 (medium):** test update-check/download/install end-to-end and document the public-repository dependency. Risk: update checks/downloads fail if release metadata or repository visibility changes.
- **A-010 / A-023 (small-medium):** replace raw exception text with localized user-safe messages and retain safe diagnostics. Risk: confusing UX and unnecessary technical disclosure.
- **A-025 / A-026 (medium):** measure workflow minutes and separate cheap PR checks from release/emulator work. Risk: slow/expensive CI.
- **A-021 / A-022 (medium):** verify all five locales, RTL and PDF/receipt fonts. Risk: unreadable labels or incorrect financial presentation.

### P2 — Later, after proof

- **A-001 / A-002 / A-003 / A-004 (small-medium):** manually prove unused resource/code candidates, audit remaining cashier leftovers, and rationalize stale branches/duplicate workflows only after references and current PR value are established.
- **A-007 (small):** settle the product-name policy for English and localized locales.
- **A-015 (medium):** align business-local timezone presentation in notification, app, PDF and exports.
- **A-029 (medium):** complete release changelog, distribution, privacy and rollback documentation.

## Proposed remediation PR sequence (no fixes started)

1. **PR 1 — Test characterization only:** expense correction/void chains, Day Closing counters, expected cash, and shared coupon/credit/refund totals across Recent, History, Reports, PDF and Day Closing.
2. **PR 2 — Reporting projection:** fix only behaviors proved failing by PR 1 tests; preserve immutable event history and collision/idempotency controls.
3. **PR 3 — Sync recovery UX:** provide localized collision/retry/support guidance without weakening server digest checks or replay protections.
4. **PR 4 — Release identity and updater:** deterministic versioning, release asset naming and explicit repository-visibility assumptions.
5. **PR 5 — CI reliability/cost:** tune triggers/cache/concurrency based on measured runs and keep expensive checks separate from fast PR validation.
6. **PR 6 — Localization/UX:** complete locale parity, RTL/PDF/receipt verification, and user-safe error/empty states.
7. **PR 7 — Proven cleanup only:** remove confirmed unused files/resources/dependencies and retire unreachable cashier artifacts only after repository-wide reference proof.
8. **PR 8 — Release documentation:** changelog, signed artifact provenance, privacy/data-handling, Play Store/outside-Play distribution, and rollback playbook.

No remediation has been started. Approval is required before any fix.


## Second-pass addendum — 2026-10-25

This addendum records additional non-security findings only. Live security details and access-control findings are intentionally excluded from this public repository file.

### A-030 — Non-sensitive scan report generated and committed
**Status: CONFIRMED**  
**Evidence:** `release-audit/scan-results.md` is committed with message `audit scan results [skip ci]`; run 40228223025 succeeded. The report contains lint counts and candidate lists only. Lint succeeded; 109 UnusedResources issues and 36 Untranslatable issues were reported. The heuristic scan found 131 zero/one-mention declaration candidates, 232 unreferenced-file candidates, no files >=1 MB, and 3 exact-hash duplicate groups.  
**Limit:** candidate lists are lexical heuristics, not proof of dead code; no files were deleted.

### A-034 — Expense lifecycle path is now traced; only the phone's 5.599 result remains unexplained
**Status: SOURCE TRACE CONFIRMED; DEVICE CAUSE BLOCKED**  
**Evidence:** the full correction/void writer, projection resolver, Recent/History repository, Reports row mapper and Day Closing shift report were fetched from main and inspected. Current code uses replacement semantics and a void amount equal to the latest effective amount; report rows use signed deltas.  
**Blocker:** the exact installed phone's local expense events/settlements and artifact identity were not available in this read-only audit, so the displayed 5.599 cannot be reproduced or assigned to a specific row.

### A-035 — PDF expected cash is not populated by the current aggregator
**Status: CONFIRMED**  
**Evidence:** `ReportingPdfActions.kt:152-156` reads `summary.expectedCash`; the field defaults to null in `ReportingModels.kt:649-650`, and the current `ReportingAggregator.kt:243-285` does not assign it. The current reporting PDF path therefore always falls back to “Not available”.

## Remaining non-security blockers

- The phone's displayed void amount of 5.599 cannot be explained by the inspected `main` and `v1.0.152` source. A read-only capture of that phone's expense event/settlement rows and exact installed artifact identity is needed to isolate the runtime discrepancy.
- Complete per-workflow 14-day job-minute totals are unavailable because the GitHub connector rejects workflow-specific run/timing endpoints and caps run-list results; no total minutes are claimed.
- Main branch protection cannot be checked through the current GitHub API connector (HTTP 403). Inspect repository Settings > Branches and Settings > Rules > Rulesets.
- Signed release artifact, Play Console configuration, rollback rehearsal, and full device/runtime financial certification were not performed.
- Security findings remain chat-only; this public report intentionally excludes the detailed Supabase ACL/Edge Function security review.

## Second-pass source tracing and repository inventory — 2026-10-25

### A-037 — Expense correction replaces the effective amount; observed 5.599 void is not explained by current source
**Status: CONFIRMED source semantics; phone observation unresolved**
- `app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseFinancialRepository.kt:84-107`: the repository derives the latest non-void `newAmountMinor` as the effective amount. A correction stores `previousAmountMinor` and the requested replacement `newAmountMinor`; its settlement delta is `previous - next`. A void uses the effective amount as its delta and stores the previous effective amount in the void event.
- `app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseReadProjection.kt:188-221`: Recent/History projection chooses the latest non-void event's new amount and marks the expense voided separately.
- `app/src/main/java/com/dailymoneyregister/feature/register/data/HistoryRepository.kt:204-250`: History maps each expense through that resolver.
- `app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingReadRepository.kt:405-450` and `ReportingRowMappers.kt:746-825`: Reports preserve the original amount as the original row and append signed lifecycle deltas. A 5.000 expense corrected to 0.599 yields a -4.401 correction delta; the void event delta is -0.599. When the original and correction fall in the same selected period, the combined expense effect is 0.599.
- `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftReportReadRepository.kt:626-660`: Day Closing resolves the effective expense amount and skips the expense outflow when the expense is voided; lifecycle settlements adjust expected cash separately.
- The same amount-selection code is present at tag `v1.0.152`. Therefore the reported phone value 5.599 is not explained by the checked source at `main` or `v1.0.152`. Exact runtime cause remains unresolved without the device's local event/settlement rows and exact installed artifact identity.

### A-038 — Day Closing does not count expense correction/void operations in its visible lifecycle counters
**Status: CONFIRMED**
- `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftReportReadRepository.kt:662-704` increments `voidCount` only for `RECORD_VOID` and `correctionCount` only for `RECORD_AMOUNT_CORRECTION`.
- Expense lifecycle commands use `RECORD_EXPENSE_VOID` and `RECORD_EXPENSE_CORRECTION` (`ExpenseLifecycleCommand.kt`); the report switch has an expense-void branch at lines 662–666 but it does not increment `voidCount`, and the expense-correction branch at lines 703–735 does not increment `correctionCount`. This explains the “Voids: 0 · Corrections: 0” display when the only lifecycle events were expense lifecycle events.

### A-039 — Reporting PDF expected cash is not populated by the current reporting aggregator
**Status: CONFIRMED**
- `app/src/main/java/com/dailymoneyregister/feature/reporting/pdf/ReportingPdfActions.kt:152-156` displays the not-available label when `summary.expectedCash` is null.
- `app/src/main/java/com/dailymoneyregister/feature/reporting/domain/ReportingModels.kt:649-650` defaults `openingCash` and `expectedCash` to null.
- `app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingAggregator.kt:243-285` constructs the summary but does not set either field. In the current path, expected cash is therefore not available in the PDF. Day Closing's expected cash calculation is a separate shift calculation and is not wired into this report summary.

### A-040 — Notification time is displayed as raw server timestamp; PDF and operational screens use mixed timezones
**Status: CONFIRMED**
- `supabase/migrations/20261015183613_fix_cashier_expense_sync_and_day_close_notifications.sql:238-250` puts `NEW.occurred_at`, `NEW.received_at`, and `NEW.branch_timezone_id` into the notification payload.
- `app/src/main/java/com/dailymoneyregister/feature/notifications/ui/NotificationsScreen.kt:154` displays `notification.createdAt` by replacing `T` and truncating fractional seconds; it does not convert the timestamp, so the serialized UTC timestamp remains UTC.
- Branch-timezone formatters: `DayClosingPresentation.kt:80-98`; `HistoryReadModel.kt:183-205`; `HistoryScreen.kt:250-265,412-427`; `ReportingUiProjection.kt:459-465`; `TransactionDetailsScreen.kt:1820-1825`; `RegisterExpensePresentation.kt:45-55`; `RecentTransactionsPanel.kt:252-255` (default `Asia/Muscat`).
- PDF report period/date uses selected branch/session branch timezone with system-default fallback: `ReportingPdfActions.kt:109-143`.
- Device/system-default formatters: `InvoiceSharing.kt:97-100`; `ReceiptReadModel.kt:49-52`; `InvoicePdfRenderer.kt:404-407`; `CouponCompactDialogR7A.kt:1894-1897`. `CustomerFormatting.kt:10-22` uses active-branch timezone with system-default fallback.
- `CustomerExcelExporter.kt:539,563,691` uses `SimpleDateFormat` without explicitly setting a timezone, so it uses the device/JVM default timezone.
- Recommendation: standardize user-facing timestamps on the branch timezone and explicitly format notification payload `closed_at` using its `branch_timezone_id`; retain UTC for machine-readable storage and logs.

### A-041 — Collision rows are fail-closed but have no automatic self-service recovery path
**Status: CONFIRMED**
- `RemoteFinancialSyncRepository.kt:126-136,280-320` turns a non-ACK receipt or recognized `OPERATION_COLLISION` into `NeedsAttention`.
- `FinancialSyncOutboxPolicy.kt:27-78` maps that receipt to `NEEDS_ATTENTION`.
- `FinancialSyncEngine.kt:258-267,303-323` persists the attention state and reports an admin-visible problem.
- `FinancialSyncOutboxDao.kt:42-70` only selects `QUEUED` and `WAITING_RETRY` for upload; it does not automatically retry `NEEDS_ATTENTION`. The DAO exposes requeue for `AUTH_REQUIRED`, but no corresponding collision reset/requeue method was found.
- The app fails closed and does not silently acknowledge a conflicting digest. However, no in-app recovery workflow was found. Recovery requires a supported, audited reconciliation of the stored and incoming operation evidence; users cannot safely resolve this by retrying or changing the operation ID themselves.

### A-042 — Release updater versioning and repository visibility dependency
**Status: CONFIRMED**  
**Evidence:** `app/build.gradle.kts:58-59` uses `GITHUB_RUN_NUMBER` for versionCode/versionName with fallbacks. `ReleaseUpdater.kt:22-86` checks the latest stable GitHub release, expects the `v1.0.N` tag/asset naming convention, and compares the installed version to the latest release version.  
**Assessment:** making the repository private breaks unauthenticated update discovery/download unless distribution changes.

### A-043 — Scan output committed and lint completed
**Status: CONFIRMED**
- The temporary workflow completed successfully on run [40228223025](https://github.com/guruoshan/Paid-Due/actions/runs/40228223025). The committed report is `release-audit/scan-results.md` (blob `2f45854f7208ec256dd43b38dcbfac92bbff4cd6`); the workflow commits the report with the exact message `audit scan results [skip ci]`.
- `:app:lintDebug` succeeded (exit 0). Parsed lint counts: Aligned16KB 1; AndroidGradlePluginVersion 2; ApplySharedPref 13; GradleDependency 15; ModifierParameter 3; NewerVersionAvailable 12; ObsoleteSdkInt 1; PluralsCandidate 1; TypographyEllipsis 15; Untranslatable 36; UnusedResources 109; UseKtx 25; UseTomlInstead 5.
- The heuristic scan found 807 top-level Kotlin declarations, 131 zero/one textual-mention candidates, 232 unreferenced-file candidates, zero files at least 1 MB, and 3 exact-hash duplicate groups. These are candidate lists, not deletion instructions; lexical counting cannot account for reflection/dynamic lookup or all resource/manifest references. The duplicate groups include launcher XMLs, a staged SQL/migration pair, and identical staff endpoint files. No files were deleted.

## GitHub pull request disposition recommendations (all 20 open PRs as of 2026-10-25)
| PR | Last updated (UTC) | Recommendation |
|---|---|---|
| #77 Localization integrity workflow | 2026-10-25 13:02 | Keep open; review path filters, run evidence, and duplicate workflow cost before merge. |
| #72 Collision evidence | 2026-10-24 18:03 | Keep pending forensic review; do not treat instrumentation as a recovery fix. |
| #68 Expense correction/void reporting | 2026-10-23 13:10 | Keep; require regression cases for 5.000 → 0.599 correction, void, reporting, and Day Closing counters. |
| #64 Legacy UUID sync acknowledgement | 2026-10-23 08:20 | Review against current main and regression tests; close if superseded. |
| #63 Sync diagnostics (draft) | 2026-10-23 07:46 | Keep only if diagnostics are still needed; otherwise close after confirming no unique fix. |
| #60 13-operation certification gate | 2026-10-23 03:56 | Close or re-scope as non-release certification; user has said this gate is not a release requirement. |
| #58 Release APK workflow fix | 2026-10-20 22:30 | Compare with current release workflow; close if main already contains the fix. |
| #49 Signed build request | 2026-10-19 17:22 | Close as a completed/obsolete release request if no active deliverable remains. |
| #43 Financial sync/reconciliation (draft) | 2026-10-18 14:08 | Keep only with an explicit narrowed scope and evidence; avoid merging a broad bundle. |
| #36 Production-readiness remediation (draft) | 2026-10-13 20:07 | Compare each change to main; close if superseded, otherwise split into focused PRs. |
| #25 Permission refresh (draft) | 2026-10-14 00:57 | Compare to admin-only/cashier-retirement direction; close if obsolete. |
| #23 Android release SDK package | 2026-10-12 17:21 | Verify main SDK configuration; close if redundant. |
| #20 Auth integration (draft) | 2026-10-12 13:49 | Review against current Supabase auth path; close if superseded. |
| #18 Auth authority (draft) | 2026-10-11 23:17 | Compare against main auth flow; close if superseded. |
| #14 Notification migration ordering (draft) | 2026-10-11 01:27 | Keep only if migration history proves it is still needed; no blind migration reorder. |
| #13 Auth DB hardening (draft) | 2026-10-11 01:25 | Review migrations against live schema before any merge; close if superseded. |
| #12 Auth/session hardening proposals (draft) | 2026-10-11 01:04 | Treat as proposal; close if no longer planned. |
| #10 Cashier session migrations (draft) | 2026-10-10 23:33 | Compare to retired cashier scope and applied migration history; close if obsolete. |
| #8 DB source reconciliation | 2026-10-10 22:30 | Keep until source/live migration drift is reconciled; no automatic merge. |
| #1 Cross-device sync foundation (WIP) | 2026-10-08 07:22 | Do not merge as-is; preserve as design/archive unless an approved, focused implementation is still required. |

## Branch inventory (95 branches; latest commit date in UTC)
Recommendation for branches not attached to an open PR: retain until the unique diff from `main` is reviewed; archive/delete only after confirming all unique changes are merged or intentionally abandoned and with owner approval. No branch was deleted.

| Branch | Last commit date (UTC) | Recommendation |
|---|---|---|
| `audit/release-readiness-2026-10-25` | 2026-10-25 | Keep until audit is approved and complete. |
| `auth-authority` | 2026-10-11 | Review unique diff; archive only if superseded. |
| `auth-authority-release` | 2026-10-11 | Open PR #18; compare to main, close PR if superseded. |
| `auth-db-hardening` | 2026-10-11 | Open PR #13; review migrations against live schema before disposition. |
| `auth-db-hardening-proposals` | 2026-10-11 | Open PR #12; close if proposal no longer planned. |
| `auth-integration-reconciled-api37-20261012` | 2026-10-12 | Open PR #20; compare to current auth path, close if superseded. |
| `auth-integration-selective-api37-20261012` | 2026-10-12 | Review unique diff; archive only if superseded. |
| `auth-session-migration-proposals` | 2026-10-10 | Open PR #10; compare to applied migrations and retired cashier scope. |
| `boardlock/cross-device-financial-sync-foundation` | 2026-10-08 | Open PR #1; do not merge WIP; archive only after scope decision. |
| `boardlock/supabase-primary-online-offline-v1` | 2026-10-10 | Review unique diff; archive only if superseded. |
| `chore/localization-integrity-workflow-only` | 2026-10-25 | Open PR #77; retain until workflow review and CI result. |
| `ci/gradle-cache-consolidation-20261012` | 2026-10-12 | Review unique diff; archive only if superseded. |
| `ci-results` | 2026-10-23 | Review branch purpose and unique diff before archive. |
| `ci-signing` | 2026-10-12 | Review unique diff carefully; preserve until signing workflow ownership is clear. |
| `ci-smoke-test` | 2026-10-25 | Review smoke-test purpose and unique diff before archive. |
| `claude/audit-fixes` | 2026-10-09 | Review unique diff; archive only if superseded. |
| `diagnostic/financial-sync-logging-v1.0.150` | 2026-10-23 | Open PR #63; close if diagnostics no longer needed. |
| `diagnostic/pr39-migration-15-16-head` | 2026-10-15 | Review unique diff; archive only if superseded. |
| `diagnostic/pr39-migration-15-16` | 2026-10-15 | Review unique diff; archive only if superseded. |
| `feat/in-app-release-updater` | 2026-10-13 | Review unique diff; likely historical feature branch, archive only if superseded. |
| `feature/business-notifications-permission-requests-20261010` | 2026-10-10 | Review unique diff; archive only if superseded. |
| `feature/customer-excel-export` | 2026-10-19 | Review unique diff; archive only if fully integrated. |
| `feature/pdf-export-and-report-sharing` | 2026-10-18 | Review unique diff; archive only if fully integrated. |
| `feature/remove-cashier-facing-access` | 2026-10-23 | Compare with admin-only main; retain until unique changes are confirmed integrated. |
| `feature/supabase-notification-inbox-day-close-permissions-20261010` | 2026-10-10 | Review unique diff and notification migration state before archive. |
| `fix/admin-customer-directory-sync-retired-rpcs` | 2026-10-24 | Review unique diff; preserve until customer-directory retirement path is confirmed. |
| `fix/admin-expense-lifecycle-and-close-sync-2026-10-23` | 2026-10-23 | Review unique diff against #68 and current main; avoid parallel fixes. |
| `fix/android-release-sdk-package-20261012` | 2026-10-12 | Open PR #23; close if main SDK setup is already equivalent. |
| `fix/backend-compatibility-audit-2026-10` | 2026-10-09 | Review unique diff; archive only if superseded. |
| `fix/business-wide-customer-directory` | 2026-10-13 | Review unique diff; archive only if superseded. |
| `fix/cash-shift-cloud-owner` | 2026-10-15 | Review unique diff against current shift sync implementation. |
| `fix/cashier-cloud-session-and-permission-refresh-20261013` | 2026-10-13 | Review against cashier retirement; close/archive if obsolete. |
| `fix/cashier-cloud-session-at-activation-20261014` | 2026-10-14 | Review against cashier retirement; close/archive if obsolete. |
| `fix/cashier-permission-refresh-20261014` | 2026-10-14 | Review against cashier retirement; close/archive if obsolete. |
| `fix/ci-baseline-compilation-and-cli` | 2026-10-12 | Review unique diff; archive only if superseded. |
| `fix/close-shift-outbox-diagnostics-2026-10-23` | 2026-10-23 | Review unique diff and diagnostics value; avoid duplicate diagnostics PRs. |
| `fix/cloud-only-cash-shift-owner-v16` | 2026-10-15 | Review unique diff against current cash-shift model. |
| `fix/cross-device-sync-mainline-2026-10-13` | 2026-10-13 | Review unique diff against current main; archive only if superseded. |
| `fix/customer-excel-export-integrity` | 2026-10-19 | Review unique diff and export tests before archive. |
| `fix/customer-sync-configured-backend-url` | 2026-10-13 | Review unique diff; archive only if superseded. |
| `fix/customer-sync-path-normalization` | 2026-10-13 | Review unique diff; archive only if superseded. |
| `fix/daily-sales-cash-bank-reconciliation-2026-10-20` | 2026-10-20 | Review unique diff and financial regression coverage. |
| `fix/db-source-reconciliation-20261011` | 2026-10-10 | Open PR #8; retain until migration/source drift is reconciled. |
| `fix/direct-cashier-invite-app-launch-20261010` | 2026-10-10 | Review against cashier retirement; close/archive if obsolete. |
| `fix/enable-implemented-inbound-operations` | 2026-10-13 | Review unique diff and inbound-operation gate; no broad enablement without tests. |
| `fix/enable-release-sale-inbound` | 2026-10-13 | Review unique diff and release evidence; archive only if superseded. |
| `fix/expense-lifecycle-reporting-and-collision` | 2026-10-23 | Open PR #68; retain for focused expense lifecycle regression review. |
| `fix/financial-refund-void-reconciliation-2026-10-20` | 2026-10-19 | Review unique diff and financial tests before archive. |
| `fix/financial-sync-legacy-uuid-ack` | 2026-10-23 | Open PR #64; review compatibility tests; close if superseded. |
| `fix/full-language-audit-2026-10-25` | 2026-10-25 | Review against current localization branch; archive if superseded. |
| `fix/https-cashier-invites-20261010` | 2026-10-10 | Review against retired cashier feature; close/archive if obsolete. |
| `fix/inbound-refund-historical-actor-2026-10` | 2026-10-18 | Review unique diff and audit attribution tests. |
| `fix/inbound-rejection-notifications-and-recent-activity-2026-10-21` | 2026-10-20 | Review unique diff; archive only if superseded. |
| `fix/language-only-clean-2026-10-25` | 2026-10-25 | Compare to #77/main; archive if localization changes are fully integrated. |
| `fix/main-release-install-readiness` | 2026-10-13 | Review unique diff against current release workflow. |
| `fix/notification-migration-version-alignment` | 2026-10-11 | Review migration ordering against live history; no blind merge. |
| `fix/production-readiness-financial-sync` | 2026-10-15 | Review unique diff; split any remaining work into focused PRs. |
| `fix/production-readiness-remediation-2026-10-13` | 2026-10-13 | Open PR #36; close if superseded, otherwise split scope. |
| `fix/real-application-financial-certification-driver` | 2026-10-21 | Review test value; do not make the 13-operation gate a release blocker. |
| `fix/receipt-note-test-fixture` | 2026-10-21 | Review unique diff; archive only if test fixture is integrated. |
| `fix/refund-coupon-integrity-recent-rows-20261019` | 2026-10-19 | Review unique diff and refund regression coverage. |
| `fix/release-apk-build-from-main-2026-10-21` | 2026-10-20 | Open PR #58; close if current main release workflow already builds triggering commit. |
| `fix/release-instrumentation-test-failures` | 2026-10-13 | Review unique diff; archive only if superseded. |
| `fix/reporting-expense-lifecycle` | 2026-10-24 | Review unique diff against #68; avoid parallel reporting fixes. |
| `fix/reporting-integrity-diagnostics-unclosed-shifts` | 2026-10-23 | Review diagnostics usefulness; ensure normal counted-but-unclosed shifts do not block reports. |
| `fix/safe-release-validation-gates` | 2026-10-13 | Review unique diff and release gate scope. |
| `fix/security-definer-least-privilege-20261012` | 2026-10-11 | Review unique diff before disposition. |
| `fix/security-definer-trigger-grants` | 2026-10-12 | Review unique diff privately; do not publish security findings in report. |
| `fix/sprint-1-5-financial-reconciliation-2026-10-18` | 2026-10-18 | Open PR #43; narrow scope and prove each change before merge. |
| `fix/staff-invite-https-manual-handoff-20261011` | 2026-10-10 | Review against retired staff endpoints; close/archive if obsolete. |
| `fix/staff-invite-manual-handoff-20261011` | 2026-10-10 | Review against retired staff endpoints; close/archive if obsolete. |
| `fix/supabase-live-source-reconciliation` | 2026-10-14 | Review unique diff and live migration state before archive. |
| `fix/supabase-permission-session-refresh` | 2026-10-14 | Open PR #25; close if cashier-specific path is retired. |
| `fix/vat-taxable-base-and-recovery-compat` | 2026-10-21 | Review unique diff and VAT/recovery tests. |
| `fix/void-aware-financial-views-2026-10-24` | 2026-10-24 | Review unique diff against current expense/reporting implementation. |
| `fix/whatsapp-recipient-routing-2026-10-16` | 2026-10-16 | Review unique diff; archive only if superseded. |
| `forensics/day-close-collision-audit-2026-10-24` | 2026-10-24 | Open PR #72; retain until forensic evidence review is complete. |
| `hardening/financial-sync-tenant-scope-20261019` | 2026-10-19 | Review tenant-scope changes privately; verify current source before archive. |
| `hardening/supabase-integrity-indexes-20261019` | 2026-10-19 | Review unique diff and query-plan need before archive. |
| `main` | 2026-10-25 | Preserve; source of truth. |
| `migration/offline-first-supabase-v1` | 2026-10-09 | Review unique diff and migration applicability before archive. |
| `migration/supabase-auth-invites-no-cloudflare-20261010` | 2026-10-10 | Review against current auth flow and retired staff endpoints. |
| `migration/supabase-edge-only` | 2026-10-09 | Review unique diff and live migration state before archive. |
| `migration-only-cashier-financial-contract` | 2026-10-11 | Review against cashier retirement and applied migrations. |
| `notification-migration-order-proposal` | 2026-10-11 | Open PR #14; retain only if migration history proves need. |
| `notifications-migration-production-reconcile` | 2026-10-11 | Review migration drift before any disposition. |
| `release/build-signed-apk-after-pr48` | 2026-10-19 | Open PR #49; close if release request is already satisfied. |
| `release/cashier-financial-migration` | 2026-10-11 | Review against cashier retirement and migration history. |
| `simplify/single-owner-login-audit-2026-10` | 2026-10-17 | Review unique diff and auth behavior before archive. |
| `sync-wip` | 2026-10-09 | Review unique diff; do not merge WIP without explicit scope. |
| `test/financial-certification-13-operation-gate` | 2026-10-23 | Open PR #60; close or re-scope as non-release certification. |
| `ux/cfo-reporting-clarity` | 2026-10-20 | Review unique diff; archive if current labels are integrated. |
| `ux/financial-clarity-transaction-rows-2026-10-18` | 2026-10-18 | Review unique diff; archive if current presentation is integrated. |
| `ux/remove-cfo-wall-of-text-2026-10-20` | 2026-10-20 | Review unique diff; archive if current presentation is integrated. |
| `validation/room-migration-v14-ci` | 2026-10-13 | Review unique diff and migration test value before archive. |
