# Open PR disposition and PR #14 review — 2026-10-31

Status: review document only. No PR was closed, merged, or deployed. No migration was added or changed.

Base: `main` at `00558886083246d3694adae53899a86ac8950eec`.

## 1. PR #14 (notification migration): corrected finding

The file in PR #14 (`20261011000000_business_notifications_and_permission_requests.sql`, renamed from `20261010112000_...`) has real defects:

- `private.notify_admins_of_confirmed_day_close()` contains a truncated condition: `IF COALESCE(v_report ->> 'difference_minor', '0') ~ '^-?[0-9]+` with no closing quote or `THEN`.
- A stray duplicate fragment (`AND (v_report ->> 'difference_minor')::bigint <> 0 THEN ... END LOOP; RETURN NEW; END;`) appears after `update_admin_cashier_permissions`, outside its function.
- Two `REVOKE` statements for `notify_admins_of_confirmed_day_close()` and `notify_cashier_of_permission_change()` run before those functions are created, so the migration would fail at that point.

However, `main` already contains a notification migration with the same name under a different timestamp, `20261011230317_business_notifications_and_permission_requests.sql`. Later migrations on `main` supersede its cashier-facing parts: `20261023050000_retire_cashier_feature.sql` and `20261023080000_purge_cashier_notifications.sql`.

Consequence: PR #14 is not a fix candidate. Applying it would duplicate a migration already on `main`, and could re-create cashier notification functions that were deliberately retired on 2026-10-23. **Recommendation: close PR #14 without merging, after confirming against the live database that `20261011230317` and the retirement migrations are applied.** Any still-needed Admin notification work should be a new PR written against current `main`.

## 2. Correction to earlier migration-drift claim

Earlier reviews said live migrations `20261010221423`, `20261010231518` and `20261010231526` were missing from `main`. They are present on `main` now. PR #10 and the drift item in PR #8 should be re-checked against current `main`, not the older snapshot.

## 3. Open PR disposition

| PR | Title | Draft | Disposition | Notes |
|---|---|---|---|---|
| #114 | Cash-sale atomic rollback test | No | Merge after CI | Test-only, 44 additions. |
| #107 | Coupon dialog decomposition | Yes | Keep draft; merge after CI | Presentation-only. |
| #77 | Localization integrity workflow | No | Decide | Workflow only. |
| #72 | Envelope collision audit | No | Reconcile with live, then decide | Says live was changed directly. |
| #68 | Reports projection: expense corrections/voids | No | Merge after review and CI | Financial reporting fix. |
| #64 | Sync ack for legacy UUID operations | No | Merge after review and CI | Sync acknowledgement path. |
| #63 | Sync start/end diagnostics | Yes | Close after #64 | Diagnostic only. |
| #60 | 13-operation certification contract | No | Decide with release plan | Foundation only; nothing certified. |
| #58 | Release APK builds triggering commit | No | Merge (release blocker) | Needed for trustworthy release builds. |
| #49 | Signed-release trigger documentation | No | Close after release | Request record only. |
| #43 | Sprint 1–5 sync and reconciliation | Yes | Split into focused PRs, then close | Too large to review safely. |
| #36 | Production-readiness umbrella | Yes | Close after extracting pieces | Overlaps #18, #20, #25, #43. |
| #25 | Permission refresh and customer sync | Yes | Reconcile with live, then decide | Live Edge Function changes reported; verify against `main`. |
| #23 | API 37 package fix | No | Verify, then merge | Conflicts with #18's report that API 37 still fails. |
| #20 | Selective Supabase auth integration | Yes | Close (duplicate of #18) | Overlapping drafts. |
| #18 | Supabase-authoritative sessions (rebased) | Yes | Keep draft; decision needed | Blocked on a working API 37 build. |
| #14 | Notification migration ordering | Yes | Close without merging | See section 1. |
| #13 | Session and login hardening proposals | Yes | Keep draft; product decision | Single-session revocation conflicts with multi-device admin use. |
| #12 | Staged session and audit hardening | Yes | Close (overlaps #13) | Duplicate proposal. |
| #10 | Record cashier-session migrations | Yes | Re-check against main, then close | Migrations now present on `main` (section 2). |
| #8 | Migration source and production drift | No | Keep; top data-risk item | Re-check drift claims against current `main`. |
| #1 | Cross-device sync foundation | No | Close or rebase | Sync code now on `main`; verify first. |

## 4. Release blockers (current view)

1. Confirm live database migration history matches `main`, including `20261011230317` and the retirement migrations.
2. Confirm the Edge Function and customer-directory state on live matches `main` (#25, #72).
3. Confirm a working API 37 Android build (#23, #18).
4. Make the signed release workflow build the triggering commit (#58).
5. Merge verified fixes: #114, #68, #64.

## 5. Not verified in this document

- CI results for any PR.
- Test counts quoted in earlier reports.
- Live database state. Nothing here queried Supabase.
- The `boardlock-engineer` skill required by AGENTS.md. It is not among the skills available to this session.

## 6. Next steps requiring approval

- Close #14, #12, #20, #49, #63 after review.
- Re-verify #8 and #10 against live Supabase, read-only.
- Split #43 and #36 into focused PRs.
