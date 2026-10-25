# Paid&Due Release Readiness Audit — Progress

Audit branch: `audit/release-readiness-2026-10-25`
Scope: read-only audit of `main`; no application changes. Security findings must remain chat-only.

| Phase | Status | One-line result |
|---|---|---|
| 0. Inventory | DONE | Confirmed public repo, Gradle/Kotlin Android app, main branch, repo size 3,797 KB, version/SDK config, release workflow, and open PR inventory; no temporary scanner workflow added because pinned scanner/action provenance and safe execution could not be verified in this session. |
| 1. Ghost/unused files and workflows | DONE | Confirmed legacy cashier strings and large stale-looking branch/PR inventory; full unused-reference proof and complete workflow inventory unavailable, so no cleanup recommended without proof. |
| 2. Android release readiness | DONE | Inspected Gradle identity/version/SDK, manifest, updater and raw exception display; release artifact, full build config and runtime behavior not certified. |
| 3. Security (chat only) | DONE | Public report contains no security details; complete history scan, live Supabase RLS/storage/functions review, branch-protection read and signing-key status were not accessible/verified. |
| 4. Data and sync health | DONE | Confirmed digest mismatch fails closed as OPERATION_COLLISION; live Supabase reset safety, schema drift and timezone parity remain unverified. |
| 5. Money logic | DONE | Expense correction/void, Day Closing counters, expected cash and cross-surface totals require runtime/regression proof; no logic changes made. |
| 6. Localization and UX | DONE | Confirmed five locale resource families and RTL manifest support; naming parity is inconsistent and full locale/PDF/receipt rendering not checked. |
| 7. Build and CI health | DONE | Inspected release workflow and action pinning; no fresh build/lint, workflow-wide inventory, or Actions billing-minute evidence. |
| 8. Release process | DONE | Release checklist and GitHub updater/release workflow inspected; signed artifact, Play readiness, changelog/tag and rollback rehearsal not verified. |
| 9. Prioritized findings and plan | DONE | REPORT.md committed with IDs A-001–A-029, confidence labels, P0/P1/P2 priorities and an ordered proposed PR plan; no fixes started. |

## Evidence discipline
Each finding will have an ID, evidence/path, verification method, and CONFIRMED / LIKELY / CANNOT CHECK label. No security findings, exploit details, secret locations, or database access-rule weaknesses belong in this public repository.


## Second-pass checklist (2026-10-25)
| Item | Status | Evidence / blocker |
|---|---|---|
| A1 Android lint and unused-resource/string lists | BLOCKED | Temporary workflow committed at `.github/workflows/release-audit-scans.yml`; workflow execution/results are not exposed by the available GitHub connector, so no lint counts are claimed. |
| A2 top-level zero-reference declarations | BLOCKED | Workflow includes a conservative text-reference candidate scan. It cannot prove zero references across reflection/dynamic lookup, and execution output is not available through this connector. |
| A3 unreferenced/large/duplicate files | BLOCKED | Workflow prints large-file and exact-hash duplicate candidates; it does not establish semantic file references. Execution output is unavailable. |
| B1 Supabase RLS and policy counts | DONE | Read-only catalog SELECT executed. Public/private app tables have RLS enabled; the full table-by-table results are provided in chat. Several platform-managed tables in auth/realtime/extensions/vault/supabase_migrations have RLS off; this is not automatically proof of exposure because schema grants and platform ownership also govern access. |
| B2 grants to anon/authenticated | DONE | Read-only information_schema grants query executed; details provided in chat. |
| B3 SECURITY DEFINER functions/search_path/execute grants | DONE | Read-only pg_proc catalog query executed. Results included platform and extension functions; app-specific functions require a filtered follow-up because the unfiltered result was truncated in the connector response. |
| B4 storage buckets | DONE | Read-only query returned zero buckets. |
| B5 Edge Function verify_jwt and privileged-key use | DONE | Live function metadata and source inspected for all five deployed functions; all show verify_jwt=false. Source-level authentication and privileged key use summarized in chat; no secret values retained in report. |
| B6 app publishable-key-only check | IN PROGRESS | Live project exposes publishable keys; source history scan and full source search must establish whether app references only publishable/legacy anon key and never a service-role key. No key value is recorded here. |
| C1 expense correction/void trace | IN PROGRESS | Reporting source inspected; exact correction resolver/void calculation needs focused source paths and line citations. |
| C2 Day Closing void/correction counters | BLOCKED | Exact notification/summary implementation not located with current connector searches; needs local full-tree search or more precise file retrieval. |
| C3 PDF expected cash availability | IN PROGRESS | Resource confirms “Not available” is a UI state; exact calculator/availability conditions need source tracing. |
| C4 all time formatting/timezones | BLOCKED | Full repository-wide time-format call-site search and line-numbered source access are unavailable in this connector. |
| C5 OPERATION_COLLISION and PR #72 recovery | IN PROGRESS | Confirmed collision guard exists; exact local outbox retry/recovery UI path and PR #72 current diff not fully retrieved. |
| C6 and any remainder of user request | BLOCKED | The received prompt ends at “C. Code tracing … 6.” with no description after item number 6. Cannot infer the missing requirement. |
