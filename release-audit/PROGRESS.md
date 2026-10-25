# Paid&Due Release Readiness Audit — Progress

Audit branch: `audit/release-readiness-2026-10-25`
Scope: read-only audit of `main`; no application changes, merges, deletions, Supabase writes, settings changes, or secret changes. Public report contains non-security findings only. Security findings remain in chat.

## Phase summary

| Phase | Status | Evidence / result |
|---|---|---|
| 0. Inventory | DONE | Main branch and public repository inventory captured; 20 open PRs and 95 branches inventoried. |
| 1. Scan and ghost-code candidates | DONE (heuristic limits) | `release-audit/scan-results.md` committed by workflow with message `audit scan results [skip ci]`; run 40228223025 succeeded. Gradle lint succeeded. 109 UnusedResources issues, 36 Untranslatable issues, 131 zero/one textual-mention declaration candidates, 227 unreferenced-file candidates, 0 files >=1 MB, 3 exact duplicate groups. Candidates are not deletion instructions. |
| 2. Android release readiness | DONE | Versioning, updater flow, release naming and repository-visibility dependency traced from source. No release artifact was built or installed by this audit. |
| 3. Supabase security review (chat only) | DONE | Read-only SELECT/catalog review of SECURITY DEFINER ACLs, tenant checks, live Edge Function metadata/source, and storage bucket count completed. Security details are deliberately excluded from public files. |
| 4. Financial data/sync | DONE with runtime blocker | Expense lifecycle projection, Day Closing counter omission, expected cash path, collision queue state and PR #72 reviewed. The phone's reported 5.599 void is not explained by the checked main/v1.0.152 source; device-local evidence is required to determine the runtime cause. |
| 5. Localization and time formatting | DONE | Repository-wide DateTimeFormatter, SimpleDateFormat, ZoneId, and timezoneId hits reviewed; notification raw UTC display contrasted with branch/device timezone formatters. |
| 6. GitHub Actions/repository | PARTIAL | All 12 workflows on main inventoried for triggers, uses lines, token permissions, PR-target triggers and explicit secret references. All open PRs and 95 branch heads/dates/recommendations recorded in REPORT.md. Exact 14-day total job minutes are blocked by the API connector's run/timing limitations. |
| 7. Branch protection | BLOCKED | GitHub API branch-protection read returned access denied (403); cannot claim main protection is enabled or disabled. See Settings > Branches / Rulesets. |
| 8. Proposed remediation plan | DONE | Non-security findings and proposed PR sequencing added to REPORT.md. No fixes started; awaiting explicit approval. |

## Detailed checklist

| Item | Status | Evidence / exact blocker |
|---|---|---|
| A1 Lint counts and unused resources/strings | DONE | `:app:lintDebug` succeeded in run 40228223025. Counts and the non-sensitive unused-resource list are in `scan-results.md`. |
| A2 Top-level declarations with zero/one textual mentions | DONE (candidate scan only) | 807 declarations parsed; 131 candidates. Reflection/dynamic references can evade lexical counting; do not delete based on this list alone. |
| A3 Unreferenced/large/duplicate files | DONE (candidate scan only) | 227 filename-mention candidates, 0 files >=1 MB, 3 exact duplicate groups. No files deleted. |
| B1 RLS/policy catalog | DONE | Read-only catalog SELECT completed; detailed result is chat-only. |
| B2 Table grants | DONE | Read-only SELECT completed; detailed result is chat-only. |
| B3 All SECURITY DEFINER execute ACLs | DONE | Catalog query covered all matching functions in public/private; complete role grouping is chat-only. |
| B4 Storage buckets | DONE | Read-only query returned zero buckets. |
| B5 Edge Function metadata/auth/key use | DONE | All five deployed functions inspected; detailed findings are chat-only. |
| B6 Android key type | DONE | Main app configuration and Android source search completed; detailed key-type result is chat-only. Source search is not binary reverse-engineering certification. |
| C1 Expense correction/void | SOURCE TRACE DONE; DEVICE CAUSE BLOCKED | Current code replaces 5.000 with 0.599 and computes a void from the effective 0.599. Same relevant code exists at tag v1.0.152. Cannot explain the phone's 5.599 display without local expense-event/settlement rows and exact installed artifact identity. |
| C2 Day Closing lifecycle counts | DONE | Shift report increments counters for sale `RECORD_VOID`/`RECORD_AMOUNT_CORRECTION`, but does not increment for `RECORD_EXPENSE_VOID`/`RECORD_EXPENSE_CORRECTION`. |
| C3 PDF Expected Cash | DONE | ReportingPdfActions reads nullable summary.expectedCash; ReportingAggregator does not populate it, so the current PDF path leaves it null. |
| C4 Time formatting/timezones | DONE | Source paths and timezone categories recorded in REPORT.md; detailed call-site list in chat. |
| C5 Collision queue recovery | DONE | Collision receipt maps to NEEDS_ATTENTION, uploader only selects QUEUED/WAITING_RETRY, and no collision-specific self-service requeue was found. The phone has no safe in-app resolution path in the inspected code. |
| C6 ReleaseUpdateCard/versioning | DONE | GITHUB_RUN_NUMBER versioning, latest stable GitHub release lookup, tag/asset naming and private-repository breakage traced; detailed integrity/signing analysis is chat-only. |
| S1 SECURITY DEFINER ACLs | DONE | Complete function/role list is chat-only. |
| S2 Tenant membership checks in ingest/fetch | DONE | Live SQL function definitions and migration source reviewed; detailed authorization analysis is chat-only. |
| S3 API Edge bearer validation / privileged key | DONE | Live Edge Function metadata and source reviewed; authentication/key-use conclusions are chat-only. No key values were recorded. |
| S4 Retired staff functions | DONE | Four retired server endpoints inspected; ghost-code recommendation is chat-only. No deletion performed. |
| D1 Workflow inventory | DONE | All workflow files and action references reviewed; token/secret reachability analysis is chat-only. |
| D2 Last 14 days run counts and total job minutes | BLOCKED | GitHub connector exposes run lists and per-run timing but rejects workflow-specific run/timing endpoints; the filtered run list is capped at 2,500 records while the unfiltered API reports 7,155 historical runs. Aggregating all jobs would require thousands of per-run timing calls, exceeding the tool-call cap; complete per-workflow 14-day totals cannot be computed reliably here. |
| D3 main branch protection | BLOCKED | Branch-protection API read returned HTTP 403. Check GitHub repository Settings > Branches and Settings > Rules > Rulesets. |
| D4 Open PRs and branches | DONE | All 20 open PRs include last-updated date and disposition recommendation; all 95 branches include latest commit date and recommendation in REPORT.md. No branches were deleted. |
| Step 5 Report/proposed PR plan | DONE | REPORT.md updated with A-037 onward, scan evidence, source traces, PR recommendations and branch inventory. It contains no security findings. |

## Change log
- Temporary workflow only: `.github/workflows/release-audit-scans.yml` was adjusted to produce non-sensitive scan results, commit them with the exact required message, and invoke the Gradle wrapper through bash.
- Preliminary scan commit (lint invocation failed with wrapper permission error): `1f96b915005e8c753d067b4664f4678187823043`. Final successful lint scan commit: `9ea45d2f530c06cad43b7681ad5bbf553a99e583`.
- Report evidence commits: `824c15bf420be7084b3d8661cd6ade781dc75e19` (source tracing/branch inventory), `5d3f946e4a84bc18d561d836fa6f90c5708d535b` (scan result update), `db6168caa2ad3d8d0639cab4e92159f7378aa820` (replace stale blockers), `c3d4987bdcb423375b2a8f73b7fb157c7f7260bc`, `3567808658f73fad8b425211eb12a79ae244fbb2`, `2aab9dc5105b494eece9c7f6aee11eb254d147ef`, `e50937c4ceefc3fd41a90378be9ec3488f3ef24e`, `704cd62b4daee95266247df5ed5bac32f3defc8d`, `63b176d0564ac875fc12829d2b93a0b5b82a51a9`, `71ec6efde7fcccef8dd09d13cf0f6bf07693e6f1`, `9be026c96fa26e28f6c16d666edc208966474d9e`, and `67c7e3f1069e01331d039249378ce49657b4ee30` (final non-security evidence updates).
- Branch activity dates were re-read from each branch head commit in eight batches and written into REPORT.md: `e0c74316dfbf0b26e974c754375a2e3bdf6ce67e`, `15a4a4f1e7631d79950aa609290d6dab7413c2b3`, `8bbb3cb1d81e818e519751a3ec2c11169e61de08`, `6d2249e1a58c9e555cb1e5fddde62701ec153853`, `518331ff30b2d36ceb253b6bfe6dcac6ec28cc05`, `fa67f3965ca276e00ba184d0528c50f44c07f781`, `ac3de6f102d89a25e209bf842aaa472fe913bc3a`, `721f8121723d692657d75cd90b77fb93b89850bf`.
- No application code or database rows were changed. No secrets were printed or written to repository files.
