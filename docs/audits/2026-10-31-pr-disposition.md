# Open PR disposition and PR #14 review — 2026-10-31

Status: review document only. No PR was closed, merged, or deployed. No migration was added or changed.

Base: `main` at `00558886083246d3694adae53899a86ac8950eec`.

## 1. PR #14 (notification migration): corrected finding

The file in PR #14 (`20261011000000_business_notifications_and_permission_requests.sql`, renamed from `20261010112000_...`) has real defects:

- `private.notify_admins_of_confirmed_day_close()` contains a truncated condition: `IF COALESCE(v_report ->> 'difference_minor', '0') ~ '^-?[0-9]+` with no closing quote or `THEN`.
- A stray duplicate fragment (`AND (v_report ->> 'difference_minor')::bigint <> 0 THEN ... END LOOP; RETURN NEW; END;`) appears after `update_admin_cashier_permissions`, outside its function.
- Two `REVOKE` statements for `notify_admins_of_confirmed_day_close()` and `notify_cashier_of_permission_change()` run before those functions are created, so the migration would fail at that point.

However, `main` and the live database both already contain `20261011230317_business_notifications_and_permission_requests.sql`. Later migrations (`20261023044205_retire_cashier_feature`, `20261023050312_purge_cashier_notifications`, and their `main` equivalents) retired the cashier-facing parts.

Consequence: PR #14 is not a fix candidate. Applying it would duplicate a migration already applied live, and could re-create cashier notification functions that were deliberately retired on 2026-10-23. **Recommendation: close PR #14 without merging.** Any still-needed Admin notification work should be a new PR written against current `main`.

## 2. Migration drift: verified against live (read-only, 2026-10-31)

The live migration history for project `ttvdpgxqvvtjnxxfkoui` was read with `list_migrations` and compared with `supabase/migrations` on `main`. Nothing was written to Supabase.

Same name, different version (live timestamp / `main` timestamp):

- `reactivate_removed_cashier_identity`: live `20261015185018`, `20261015185332`, `20261015185405`; `main` `20261015203000`
- `allow_reinvite_removed_cashier_same_branch`: live `20261016125950`; `main` `20261016150000`
- `allow_reinvite_same_local_user_activation_history`: live `20261016131840`; `main` `20261016132000`
- `retire_cashier_feature`: live `20261023044205`; `main` `20261023050000`
- `remove_staff_auth_rate_limits`: live `20261023045447`; `main` `20261023060000`
- `remove_cashier_customer_sync_rpcs`: live `20261023045544`; `main` `20261023070000`
- `purge_cashier_notifications`: live `20261023050312`; `main` `20261023080000`
- `restore_historical_actor_user_ids`: live `20261023052001`; `main` `20261023100000`
- `repair_admin_customer_directory_sync_after_rpc_retirement`: live `20261024181559`; `main` `20261024190000`

Applied live, not in `main`:

- `20261018140123_add_financial_reporting_inventory_rpc`
- `20261024174802_capture_financial_operation_collisions`

In `main`, not applied live:

- `20261027175000_reject_customer_directory_identity_conflicts`
- `20261027190000_customer_directory_keyset_pagination`
- `20261027193000_financial_envelope_metadata_consistency`
- `20261029010000_enforce_admin_only_role`

Matching by name and version: `20261011230317_business_notifications_and_permission_requests` is applied live and present in `main`.

This replaces an earlier statement in this document that all live migrations were already on `main`. That was incorrect.

Consequence: #8 (migration drift) stays open. #10 (record cashier-session migrations) also stays open until the version mismatches are reconciled.

## 3. Open PR disposition

| PR | Title | Draft | Disposition | Notes |
|---|---|---|---|---|
| #114 | Cash-sale atomic rollback test | No | Merge after CI | Test-only, 44 additions. |
| #107 | Coupon dialog decomposition | Yes | Keep draft; merge after CI | Presentation-only. |
| #77 | Localization integrity workflow | No | Decide | Workflow only. |
| #72 | Envelope collision audit | No | Reconcile with live, then decide | Live has `capture_financial_operation_collisions`, not in `main`. |
| #68 | Reports projection: expense corrections/voids | No | Merge after review and CI | Financial reporting fix. |
| #64 | Sync ack for legacy UUID operations | No | Merge after review and CI | Sync acknowledgement path. |
| #63 | Sync start/end diagnostics | Yes | Close after #64 | Diagnostic only. |
| #60 | 13-operation certification contract | No | Decide with release plan | Foundation only; nothing certified. |
| #58 | Release APK builds triggering commit | No | Merge (release blocker) | Needed for trustworthy release builds. |
| #49 | Signed-release trigger documentation | No | Close after release | Request record only. |
| #43 | Sprint 1–5 sync and reconciliation | Yes | Split into focused PRs, then close | Too large to review safely. |
| #36 | Production-readiness umbrella | Yes | Close after extracting pieces | Overlaps #18, #20, #25, #43. |
| #25 | Permission refresh and customer sync | Yes | Reconcile with live, then decide | Four `main` migrations not applied live (section 2). |
| #23 | API 37 package fix | No | Verify, then merge | Conflicts with #18's report that API 37 still fails. |
| #20 | Selective Supabase auth integration | Yes | Close (duplicate of #18) | Overlapping drafts. |
| #18 | Supabase-authoritative sessions (rebased) | Yes | Keep draft; decision needed | Blocked on a working API 37 build. |
| #14 | Notification migration ordering | Yes | Close without merging | See section 1. |
| #13 | Session and login hardening proposals | Yes | Keep draft; product decision | Single-session revocation conflicts with multi-device admin use. |
| #12 | Staged session and audit hardening | Yes | Close (overlaps #13) | Duplicate proposal. |
| #10 | Record cashier-session migrations | Yes | Keep open until versions reconciled | Version mismatches in section 2. |
| #8 | Migration source and production drift | No | Keep; top data-risk item | Drift confirmed in section 2. |
| #1 | Cross-device sync foundation | No | Close or rebase | Sync code now on `main`; verify first. |

## 4. Release blockers (current view)

1. Reconcile migration versions between `main` and live (section 2), and apply or retire the four `main` migrations not yet applied live.
2. Confirm the Edge Function and customer-directory state on live matches `main` (#25, #72).
3. Confirm a working API 37 Android build (#23, #18).
4. Make the signed release workflow build the triggering commit (#58).
5. Merge verified fixes: #114, #68, #64.

## 5. Not verified in this document

- CI results for any PR.
- Test counts quoted in earlier reports.
- Live database schema contents. Only the migration list was read.
- The `boardlock-engineer` skill required by AGENTS.md. It is not among the skills available to this session.

## 6. Next steps requiring approval

- Close #14, #12, #20, #49, #63 after review.
- Reconcile migration versions (section 2) before closing #8 or #10.
- Split #43 and #36 into focused PRs.
