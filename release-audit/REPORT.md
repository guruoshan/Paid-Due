# Paid&Due Release-Readiness Audit

**Audited ref:** `main` (repository default branch, observed 2026-10-25)  
**Audit branch:** `audit/release-readiness-2026-10-25`  
**Scope:** read-only review. No application files or Supabase data were changed. This report intentionally excludes security-sensitive findings; those are communicated only in chat.

## Confidence and limitations

This is a source/configuration and GitHub metadata review, not a certified build or a full static-analysis run. Files and selected source excerpts were fetched from `main`; GitHub search was used to locate code/tests. A local checkout/build, full history secret scan, Android lint with every check, dependency vulnerability scan, live Supabase inspection, full workflow enumeration, Actions billing usage, and physical multi-device tests were not executed. Findings depending on those checks are explicitly marked CANNOT CHECK or LIKELY. No temporary scanner workflow was added because the available session did not establish safe, verifiable pinned scanner action revisions and execution conditions.

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

### A-002 — Resource/file “unused” status not fully established
**Status: CANNOT CHECK**  
**Evidence:** full resource graph, reflection/string lookup and all call sites were not locally indexed. Searches did not find the specifically requested `strings_audit` / `strings_lint_localization` names in indexed excerpts, but that is not a whole-tree proof.  
**Verification:** targeted GitHub code searches only.  
**Recommendation:** run Android lint with `UnusedResources` and all checks, then manually inspect each candidate through source, manifest, Gradle, tests, reflection and string-based lookup before any deletion.

### A-003 — Branch and PR inventory contains substantial stale-looking work
**Status: CONFIRMED**  
**Evidence:** GitHub branch search listed many long-lived branches, including `feature/remove-cashier-facing-access`, multiple `fix/*` branches, `ci-smoke-test`, `feature/customer-excel-export`, and audit/diagnostic branches. Open PR search surfaced #77, #72, #68, #64, #63, #60 and older PRs including #49, #43, #36, #25, #23, #20, #18, #14, #13, #12, #10, #8 and #1.  
**Verification:** GitHub branch and open-PR searches; results may be paginated/connector-limited.  
**Recommendation:** reconcile each open PR against current main and close/archive only after owner review. Do not delete branches based on age alone.

### A-004 — Full workflow trigger/cost classification not available
**Status: CANNOT CHECK**  
**Evidence:** `.github/workflows/release.yml` was fetched; no full workflow directory listing, full Actions run inventory, or billing-minute data was available in this review. User-named examples such as `pr37-signed-sync-test`, `customer-export-release-apk`, and `ci-smoke-test` require direct file/run verification.  
**Verification:** inspected release workflow and searched selected workflow-related terms; did not infer that examples exist on current main.  
**Recommendation:** inventory every workflow file and its `on:` triggers, runner, timeout, cache, duplicate tasks and run duration before consolidation.

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

### A-008 — Update checker requires GitHub latest stable release and digest metadata
**Status: CONFIRMED**  
**Evidence:** `app/src/main/java/com/dailymoneyregister/core/update/ReleaseUpdater.kt` requests `https://api.github.com/repos/guruoshan/Paid-Due/releases/latest`, expects tags `v1.0.N`, matches asset name `paid-due-v{tag}.apk`, requires an official repository release URL and a GitHub-provided SHA-256 digest. The class comment says Android's package installer enforces installed-app signing identity.  
**Verification:** direct source inspection.  
**Assessment:** updater behavior is coupled to the repository being publicly accessible and releases being published with the expected tag/asset/digest format. If the repository becomes private, unauthenticated API/download access may fail; no authenticated private-repo access path was observed in the inspected source. SHA-256 confirms file integrity against the API-provided digest, not publisher authenticity by itself; Android signature compatibility is the separate identity check.

### A-009 — Manifest baseline has conservative backup and network settings
**Status: CONFIRMED**  
**Evidence:** `app/src/main/AndroidManifest.xml` sets `allowBackup=false`, `usesCleartextTraffic=false`, `supportsRtl=true`; requests INTERNET, WRITE_EXTERNAL_STORAGE (max SDK 28), REQUEST_INSTALL_PACKAGES; MainActivity is exported for launcher use, FileProvider is non-exported with URI grants.  
**Verification:** direct manifest inspection.  
**Limit:** permission usage across all call sites, exported-component merge results, WebView usage, and installed artifact manifest were not fully checked.

### A-010 — Raw update errors can be shown to users
**Status: CONFIRMED**  
**Evidence:** `ReleaseUpdateCard.kt` displays `error.message` when an exception is thrown, falling back to a localized generic message only when no message exists.  
**Verification:** direct source inspection.  
**Risk:** raw technical/server errors may appear in the UI, can be confusing and may reveal internal implementation detail. Map failures to stable localized user messages and retain safe diagnostics separately.

### A-011 — Encryption implementation exists, but release database encryption is not runtime-certified
**Status: LIKELY**  
**Evidence:** version catalog declares `net.zetetic:sqlcipher-android:4.19.0`; repository search finds `SqlCipherExportProofTest.kt` and project guidance requiring SQLCipher integrity.  
**Verification:** dependency and test-name search.  
**Limit:** no installed app/device database inspection or successful SQLCipher proof run in this audit. Confirm all production Room database open paths use encryption, key lifecycle is sound, and exports/temporary files are handled safely.

## Phase 3 — Security

Security findings are intentionally excluded from this public report. The chat-only security assessment covers secret exposure, database access controls, Supabase RLS/storage/functions, Actions trust boundaries and signing-key rotation. A complete secret-history scan, live Supabase review and GitHub branch-protection read were not performed, so no claim of security clearance is made.

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

### A-015 — Time-zone parity between notification and report is not runtime-verified
**Status: CANNOT CHECK**  
**Evidence:** migration `20261015183613_fix_cashier_expense_sync_and_day_close_notifications.sql` emits `DAY_CLOSED` notifications; user reports UTC in notification versus Oman time in PDF.  
**Verification:** located the notification SQL but did not inspect live notification payloads or generate matching notification/PDF output.  
**Recommendation:** standardize on the branch/business IANA timezone for displayed dates and times; test DST boundaries and UTC+04 Oman output in notification, app, PDF and export.

## Phase 5 — Money logic

### A-016 — Expense correction/void report projection remains an open release concern
**Status: LIKELY**  
**Evidence:** open PR #68 is titled “Fix Reports projection for expense corrections and voids” and describes loading expense lifecycle history, projecting corrected effective amounts, and setting voided expenses to zero report impact. Current main reporting repository is `app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingReadRepository.kt`.  
**Verification:** open-PR metadata and current-main source inspection; no test/build run or reproduction.  
**Risk:** report totals may block or misstate corrected/voided expenses. Review #68 against current main and verify it with tests before deciding release status.

### A-017 — Expense correction amount semantics (5.000 → 0.599 → void) not reproduced
**Status: CANNOT CHECK**  
**Evidence:** scenario supplied in audit request; no runtime fixture or test output captured.  
**Verification:** no test execution.  
**Expected invariant to verify:** correction replaces the effective amount (or applies an explicitly defined delta), and subsequent void reverses only the current effective expense impact—not old plus corrected values. Add characterization tests for correction chains and void after correction.

### A-018 — Day Closing “Voids: 0 · Corrections: 0” not verified
**Status: CANNOT CHECK**  
**Evidence:** scenario supplied in audit request; notification SQL found but no generated day-close payload inspected.  
**Verification:** no live notification/report execution.  
**Expected invariant:** activity counters should reflect relevant lifecycle events over the selected business-local period, while monetary totals should use the effective financial projection without double counting.

### A-019 — PDF “Expected Cash Position: Not available” not verified
**Status: CANNOT CHECK**  
**Evidence:** `app/src/main/res/values/strings_reporting_pdf.xml` defines `pdf_metric_expected_cash` and `pdf_not_available`; presence of the string alone does not prove the runtime condition.  
**Verification:** source resource inspection only.  
**Recommendation:** document prerequisites for expected cash calculation and test available/unavailable states against counted shifts, opening balances, refunds, voids, corrections and expenses.

### A-020 — Cross-screen money consistency not certified
**Status: CANNOT CHECK**  
**Evidence:** report aggregation is separated from the Room read repository by design; code comments in `ReportingReadRepository.kt` say it supplies detailed rows/activity counts while the Phase 3 aggregator owns financial summary amounts. Existing test search surfaces recent activity, credit-return and amount-correction tests.  
**Verification:** inspected repository contract and selected test names only; no full suite or oracle run.  
**Risk:** Recent, History, Reports, PDF and Day Closing could diverge on coupon funding, credit creation/repayment, refunds, voids and expense lifecycle.  
**Required proof:** one shared fixture per operation type, assert each surface against a common expected ledger projection, including split tender, partial/full refund, coupon refunds, credit repayment allocation, correction chains, cash-shift close and Oman-local date boundaries.

## Phase 6 — Localization and UX

### A-021 — Locale resources and product name are not fully parity-checked
**Status: CONFIRMED**  
**Evidence:** English uses `Paid&Due`; Arabic/Urdu/Hindi/Bengali resources use localized “Daily Money Register” naming. Bengali source exists at `values-b+bn/strings.xml`; no `values-bn/strings.xml` was found at that exact path.  
**Verification:** fetched locale files and attempted the alternate path.  
**Limit:** failed lookup is not a complete tree proof; duplicate/orphan Bengali resource folder status remains unproven.

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

### A-024 — No fresh build/lint/test result was produced by this audit
**Status: CANNOT CHECK**  
**Evidence:** no local checkout or Gradle invocation was available in the connected audit path.  
**Verification:** GitHub file/API inspection only.  
**Risk:** compilation, tests, lint, instrumentation and signed artifact status on the current main SHA remain unproven by this audit.

### A-025 — Release workflow is event-gated, but action pinning is inconsistent
**Status: CONFIRMED**  
**Evidence:** `.github/workflows/release.yml` runs on pushes to main and manual dispatch; job condition requires a `[build-apk]` commit message on push or explicit manual confirmation. `actions/checkout` and `actions/setup-java` use full commit SHAs; `gradle/actions/setup-gradle@v4` and `denoland/setup-deno@v2` use mutable version tags in the inspected excerpt.  
**Verification:** direct workflow inspection.  
**Risk:** tag updates can change the action code executed by the workflow. Pin all third-party actions to reviewed full SHAs and manage updates deliberately.  
**Cost:** exact Actions-minute cost cannot be stated without run duration, runner billing multipliers and billing usage data.

### A-026 — Workflow consolidation and private-repository minutes explanation not verified
**Status: CANNOT CHECK**  
**Evidence:** no complete workflow directory/run-time inventory or billing data; cannot verify the historical claim that checks stopped because of a minutes limit.  
**Verification:** connector search and release workflow inspection.  
**Recommendation:** group fast static checks separately from expensive emulator/instrumentation and signed release jobs; use path filters only where correctness is preserved, Gradle caching, concurrency cancellation for superseded non-release PR runs, bounded timeouts, and one trusted release gate. Confirm current plan quota and actual run minutes before attributing failures to billing.

## Phase 8 — Release process

### A-027 — Checklist exists, but end-to-end release evidence is not attached to this audit
**Status: CONFIRMED**  
**Evidence:** `RELEASE_CHECKLIST.md` covers the release gate, and `.github/workflows/release.yml` performs Android unit/instrumentation tests, release compilation, Supabase Edge Function tests, signing configuration validation and release publishing.  
**Verification:** checklist and release workflow source inspection.  
**Limit:** no current run, APK/AAB signature, Play Console configuration, privacy-policy review or rollback rehearsal was inspected.

### A-028 — Public GitHub release is a distribution dependency for in-app updates
**Status: CONFIRMED**  
**Evidence:** `ReleaseUpdater.kt` uses GitHub's latest-release API and repository release asset URLs.  
**Verification:** source inspection.  
**Recommendation:** keep repository visibility and asset naming/digest conventions as documented release dependencies, or design and test an authenticated alternative before making the repository private.

### A-029 — Versioning/changelog/rollback and store distribution readiness not fully verified
**Status: CANNOT CHECK**  
**Evidence:** `RELEASE_CHECKLIST.md` exists; root `README.md` was not found at the queried path. No release/tag inventory, Play Console record, signed AAB, staged rollout or rollback artifact was inspected.  
**Verification:** direct file queries and checklist review.  
**Recommendation:** ensure every release has a unique monotonic versionCode, changelog/tag, exact source SHA, checksum/signature evidence, staged rollout/rollback procedure, privacy policy and Data Safety declarations, support path, and tested database migration rollback/forward-repair plan. Outside Play Store, distribute only signed APKs with a verified signer and secure download path; inside Play Store, produce and validate an AAB and complete current policy/listing requirements.

## Phase 9 — Priorities and proposed plan

### P0 — Before any real customer

- **A-012 / A-020 (medium-large):** prove multi-device operation identity, collision behavior and consistency across all financial surfaces. Risk: reports can be stale/unavailable and financial operations can remain unacknowledged. Keep collision rejection fail-closed.
- **A-016 / A-017 / A-018 / A-019 (medium-large):** verify expense correction/void projection, Day Closing counts and expected cash position with regression fixtures. Risk: incorrect totals or blocked reports.
- **A-024 / A-027 (medium):** obtain a fresh passing build/test/lint result and inspect the signed release artifact before customer distribution. Risk: shipping an unverified build.
- **A-014 / A-013 (large):** reconcile live schema/migration history and document a safe, verified test reset only if needed. Risk: migration failure or unintended financial evidence deletion.

### P1 — Before wide use

- **A-005 (small-medium):** define explicit monotonic release versioning independent of Actions run-number fallback. Risk: duplicate/misleading version identity.
- **A-008 / A-028 (medium):** test update-check/download/install end-to-end and document the public-repository dependency. Risk: update checks/downloads fail if release metadata or repository visibility changes.
- **A-010 / A-023 (small-medium):** replace raw exception text with localized user-safe messages and retain safe diagnostics. Risk: confusing UX and unnecessary technical disclosure.
- **A-025 / A-026 (medium):** pin third-party actions and measure actual workflow minutes; separate cheap PR checks from release/emulator work. Risk: supply-chain drift and slow/expensive CI.
- **A-021 / A-022 (medium):** verify all five locales, RTL and PDF/receipt fonts. Risk: unreadable labels or incorrect financial presentation.
- **A-011 (medium):** certify database encryption on actual release/device paths. Risk: sensitive local financial data protection assumptions may be wrong.

### P2 — Later, after proof

- **A-001 / A-002 / A-003 / A-004 (small-medium):** manually prove unused resource/code candidates, audit remaining cashier leftovers, and rationalize stale branches/duplicate workflows only after references and current PR value are established.
- **A-007 (small):** settle the product-name policy for English and localized locales.
- **A-015 (medium):** align business-local timezone presentation in notification, app, PDF and exports.
- **A-029 (medium):** complete release changelog, distribution, privacy and rollback documentation.

## Proposed remediation PR sequence (no fixes started)

1. **PR 1 — Test characterization only:** expense correction/void chains, Day Closing counters, expected cash, and shared coupon/credit/refund totals across Recent, History, Reports, PDF and Day Closing.
2. **PR 2 — Reporting projection:** fix only behaviors proved failing by PR 1 tests; preserve immutable event history and collision/idempotency controls.
3. **PR 3 — Sync recovery UX:** provide localized collision/retry/support guidance without weakening server digest checks or replay protections.
4. **PR 4 — Release identity and updater:** deterministic versioning, release asset/digest/signature verification and explicit visibility assumptions.
5. **PR 5 — CI safety/cost:** pin remaining actions by SHA, add verified lint/secret/dependency scans, and tune triggers/cache/concurrency based on measured runs.
6. **PR 6 — Localization/UX:** complete locale parity, RTL/PDF/receipt verification, and user-safe error/empty states.
7. **PR 7 — Proven cleanup only:** remove confirmed unused files/resources/dependencies and retire unreachable cashier artifacts only after repository-wide reference proof.
8. **PR 8 — Release documentation:** changelog, signed artifact provenance, privacy/data-handling, Play Store/outside-Play distribution, and rollback playbook.

No remediation has been started. Approval is required before any fix.
