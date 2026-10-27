# Dead-Code and Repository Hygiene Report

- **Audit target:** `guruoshan/Paid-Due`, branch `main`
- **Main commit audited:** `84a054eb40ae6df9acf753e34bd119a9d271f113` (2026-10-27)
- **Report branch:** `audit/dead-code-report-20261028`
- **Scope:** report-only. No application files, migrations, Supabase data, or deployment targets were changed.
- **Important limitation:** the available environment could read GitHub's repository tree, but could not clone the repository or execute Gradle locally. Consequently, this is a **partial, evidence-bounded audit**, not a claim that all requested scans passed or that the repository is free of unused code.

## Summary

1. Main was identified at `84a054e`; its recursive Git tree contains 798 entries and was not truncated.
2. A local clone attempt failed because this execution environment could not resolve `github.com`.
3. `:app:lintDebug`, Detekt/ktlint, source-wide grep, and an R8 `-printusage` build were **not run**.
4. No symbol or resource is labelled HIGH-confidence unused without whole-repository reference and registration checks.
5. The tree confirms a set of cashier/staff-named files under `app/src/androidTest` and cashier-related SQL under `BOARDLOCK/staged_sql`; their runtime relevance has not been proven.
6. `BOARDLOCK/staged_sql` files are not migrations, but must not be deleted based on naming alone.
7. No file is recommended for immediate deletion in this report; preserve financial code and every migration.

## Commands and observed results

| Command / operation | Result |
|---|---|
| `git clone --depth 1 --branch main https://github.com/guruoshan/Paid-Due.git /tmp/Paid-Due-audit` | **FAILED:** `fatal: unable to access 'https://github.com/guruoshan/Paid-Due.git/': Could not resolve host: github.com` |
| GitHub API read: `GET /repos/guruoshan/Paid-Due/branches/main` | **SUCCESS:** main commit `84a054eb40ae6df9acf753e34bd119a9d271f113`; tree `4a3ec8cbcf85c93460b546957d3f22a425ee4886`. |
| GitHub API read: `GET /repos/guruoshan/Paid-Due/git/trees/4a3ec8cbcf85c93460b546957d3f22a425ee4886?recursive=1` | **SUCCESS:** `truncated=false`, `count=798`. File paths and blob sizes were returned. This is a tree inventory, not a source-reference scan. |
| `./gradlew :app:lintDebug` | **NOT RUN:** no working clone/Gradle project was available in this environment. No lint findings or clean result are claimed. |
| Detekt / ktlint | **NOT RUN:** plugin/task availability could not be checked by executing Gradle. |
| Release build with R8 `-printusage` | **NOT RUN:** no build was possible. Whether signing secrets would be required was therefore not established. |
| Whole-repository grep / reference checks | **NOT RUN:** a complete local checkout was unavailable. No zero-reference claims are made. |

## God files

**Status: not yet measurable.** The requested threshold is source line count over 600 lines, plus public-function and constructor-dependency counts for classes/ViewModels. The recursive Git tree provides byte sizes, not source line counts or parsed Kotlin declarations. Without source retrieval and a runnable checkout, listing files as exceeding those thresholds would be guesswork.

| File | Lines | What jobs it does | Recommendation |
|---|---:|---|---|
| No verified entries | Not measured | No file was inspected sufficiently to make a supported responsibility summary. | Re-run source line-count and Kotlin declaration scans in a checkout before recommending splits. |

Required command for the next local pass: `find app -type f -name '*.kt' -print0 | xargs -0 wc -l | sort -nr`. This command was **not executed** here; it is recorded as a follow-up command, not evidence.

## Unused code

**No item qualifies as HIGH confidence.** The required whole-repository cross-check (including app, Supabase, workflows, docs, scripts, manifest, XML resources, navigation, DI, Room, WorkManager, serialization and reflective/string references) could not be performed. A lack of a result from GitHub code search would not prove zero references.

| Item | File | Evidence (command + result) | Confidence | Risk of removal |
|---|---|---|---|---|
| No verified unused declaration/resource | Not established | `./gradlew :app:lintDebug` — not run; clone failed with DNS resolution error. Whole-repository grep — not run. | NONE / unverified | Unknown; do not remove. |

### Static-analysis and shrinker evidence

- **Android lint:** no `UnusedResources` or `UnusedDeclaration` findings can be reported because lint did not run.
- **Detekt/ktlint:** availability and findings are unknown; tasks were not run. In particular, no result is claimed for `UnusedPrivateMember`, `UnusedPrivateProperty`, `LongMethod`, `LargeClass`, or `TooManyFunctions`.
- **R8:** no release build or `-printusage` output was produced. There is no R8 evidence in this audit.

### Mandatory cross-check rule

Before any candidate is called unused, search its exact identifier and relevant string/resource names across the entire repository, then check manifest, Hilt/Dagger, Room, WorkManager, serialization, navigation, reflection and string-based registration. A candidate with a financial responsibility must also be reviewed for indirect accounting/reconciliation effects. None of these checks is represented as completed here.

## Irrelevant files

The tree confirms the following *review candidates*, not proven junk. Their names and location alone do not justify removal. Byte sizes are from the Git tree; they are not claims about file contents beyond the path/name.

| Path | Size (bytes) | Why review it | Safe to remove? |
|---|---:|---|---|
| `BOARDLOCK/staged_sql/fetch_cashier_financial_envelopes.sql` | 2,528 | Staged SQL outside `supabase/migrations`; cashier-specific name suggests a retired workflow may still be documented/staged. Check current operational runbooks and references first. | **NO — unverified** |
| `BOARDLOCK/staged_sql/fetch_cashier_metadata.sql` | 3,708 | Staged SQL outside migrations; may be retained as operational recovery material. | **NO — unverified** |
| `BOARDLOCK/staged_sql/fetch_cashier_restoration.sql` | 3,324 | Staged SQL outside migrations; restoration script can have operational significance even if cashier UI is retired. | **NO — unverified** |
| `BOARDLOCK/staged_sql/list_admin_cashiers.sql` | 2,897 | Staged SQL outside migrations; verify whether it supports an admin audit/legacy recovery path. | **NO — unverified** |
| `BOARDLOCK/staged_sql/verify_and_restore_staff_by_phone.sql` | 1,251 | Staged SQL outside migrations; staff restoration may be legacy operational material. | **NO — unverified** |

No committed secret, private key, keystore, backup ZIP, export, screenshot, duplicate/copy file, or large-binary issue is declared present or absent: content and full binary/secret scanning were not run. **No secret value was requested or displayed.** If a secret is confirmed in a future scan, mark it **URGENT**, do not reproduce its value, and follow the repository's incident-response process.

## Cashier leftovers

The paths below are confirmed by the recursive tree. Because the local source-wide reference scan could not run, this is a **confirmed-name inventory, not an exhaustive list of every cashier/staff string, test, resource, or migration**.

| Item / path | Evidence | Assessment | Safe to remove? |
|---|---|---|---|
| `app/src/androidTest/java/com/dailymoneyregister/feature/admin/data/CashierManagementRepository.kt` | Recursive tree contains this path (blob size 16,210 bytes). | Test-source package contains a cashier management repository by name; inspect its test fixtures/imports and production counterpart before deciding. | **Not established** |
| `app/src/androidTest/java/com/dailymoneyregister/feature/admin/data/CashierPermissionsConfig.kt` | Recursive tree contains this path (839 bytes). | Cashier permission test/config artefact by name; may encode compatibility expectations. | **Not established** |
| `app/src/androidTest/java/com/dailymoneyregister/feature/admin/data/CreateCashierPayloadCodec.kt` | Recursive tree contains this path (7,652 bytes). | Cashier payload codec in Android test source; verify whether current sync compatibility tests still depend on it. | **Not established** |
| `app/src/androidTest/java/com/dailymoneyregister/feature/admin/data/RemoteEmployeeDirectory.kt` | Recursive tree contains this path (2,768 bytes). | Employee-directory test-source artefact; “employee” does not by itself prove a retired cashier feature. | **Not established** |
| `BOARDLOCK/staged_sql/fetch_cashier_financial_envelopes.sql` | Recursive tree contains this path (2,528 bytes). | Legacy/staged SQL candidate; check operational references and whether it is needed for historical recovery. | **NO — unverified** |
| `BOARDLOCK/staged_sql/fetch_cashier_metadata.sql` | Recursive tree contains this path (3,708 bytes). | Legacy/staged SQL candidate; check operational references. | **NO — unverified** |
| `BOARDLOCK/staged_sql/fetch_cashier_restoration.sql` | Recursive tree contains this path (3,324 bytes). | Restoration script may have recovery value. | **NO — unverified** |
| `BOARDLOCK/staged_sql/list_admin_cashiers.sql` | Recursive tree contains this path (2,897 bytes). | Legacy/staged admin query candidate; verify audit and recovery use. | **NO — unverified** |
| `BOARDLOCK/staged_sql/verify_and_restore_staff_by_phone.sql` | Recursive tree contains this path (1,251 bytes). | Staff-named recovery script; scope is not established by filename. | **NO — unverified** |

**Removal safety:** no cashier-related path above is approved for removal by this report. In particular, do not delete or rewrite anything under `supabase/migrations`, and do not remove compatibility tests or recovery SQL without verifying callers and operational ownership.

## Safe first batch

**No items qualify yet.** The requested bar is 10–20 items with HIGH confidence and LOW risk, established by zero references plus registration checks and supported by lint/static-analysis evidence. Those checks were unavailable, so naming 10–20 deletions would be unsafe and would violate the evidence standard. The staged SQL and cashier-named test artefacts above are review candidates only, not a deletion batch.

## Exact commands run and results

1. `git clone --depth 1 --branch main https://github.com/guruoshan/Paid-Due.git /tmp/Paid-Due-audit`  
   Result: `fatal: unable to access 'https://github.com/guruoshan/Paid-Due.git/': Could not resolve host: github.com` (exit status 128).

2. GitHub API read of `https://api.github.com/repos/guruoshan/Paid-Due/branches/main`  
   Result: main SHA `84a054eb40ae6df9acf753e34bd119a9d271f113`; tree SHA `4a3ec8cbcf85c93460b546957d3f22a425ee4886`.

3. GitHub API read of `https://api.github.com/repos/guruoshan/Paid-Due/git/trees/4a3ec8cbcf85c93460b546957d3f22a425ee4886?recursive=1`  
   Result: `truncated=false`, `count=798`.

4. `./gradlew :app:lintDebug` — **not run**, because no local checkout could be obtained.
5. Detekt/ktlint tasks — **not run**, because Gradle task availability could not be established.
6. Release build with R8 `-printusage` — **not run**, because the build could not be run.
7. Whole-repository grep — **not run**, because the local checkout could not be obtained.

**Audit conclusion:** this report deliberately records the execution blocker rather than inventing line counts, lint results, reference counts, or deletion recommendations. Re-run the incomplete scans in an environment with a working checkout before using this report to plan cleanup.
