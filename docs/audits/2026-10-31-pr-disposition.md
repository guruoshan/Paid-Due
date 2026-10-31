# Open PR disposition review — updated 2026-10-31

Status: review document only. No PR was closed, merged, or deployed by this document. No migration or production code was changed by it.

Base: `main` at `be93f9bc3df39e22203cccf160d2a8c360c83766` (includes the merge of PR #114).

## 1. PR #14 (notification migration): closed

The migration in PR #14 had a truncated condition, a stray duplicate fragment and REVOKE statements that ran before their functions existed. It was also superseded: `main` and the live database already contain `20261011230317_business_notifications_and_permission_requests`, and later migrations (`retire_cashier_feature`, `purge_cashier_notifications`) retired its cashier parts. Closed without merging.

## 2. Migration drift (verified read-only, 2026-10-31)

Live migration history for project `ttvdpgxqvvtjnxxfkoui` compared with `supabase/migrations` on `main`:

Same name, different version (live / main):

- `reactivate_removed_cashier_identity`: live `20261015185018`, `20261015185332`, `20261015185405`; main `20261015203000`
- `allow_reinvite_removed_cashier_same_branch`: live `20261016125950`; main `20261016150000`
- `allow_reinvite_same_local_user_activation_history`: live `20261016131840`; main `20261016132000`
- `retire_cashier_feature`: live `20261023044205`; main `20261023050000`
- `remove_staff_auth_rate_limits`: live `20261023045447`; main `20261023060000`
- `remove_cashier_customer_sync_rpcs`: live `20261023045544`; main `20261023070000`
- `purge_cashier_notifications`: live `20261023050312`; main `20261023080000`
- `restore_historical_actor_user_ids`: live `20261023052001`; main `20261023100000`
- `repair_admin_customer_directory_sync_after_rpc_retirement`: live `20261024181559`; main `20261024190000`

Applied live, not in `main`:

- `20261018140123_add_financial_reporting_inventory_rpc`
- `20261024174802_capture_financial_operation_collisions`

In `main`, not applied live:

- `20261027175000_reject_customer_directory_identity_conflicts`
- `20261027190000_customer_directory_keyset_pagination`
- `20261027193000_financial_envelope_metadata_consistency`
- `20261029010000_enforce_admin_only_role`

## 3. Sync acknowledgement (replaces the earlier recommendation to merge PR #64)

PR #64 is not to be merged as it stands: it is stale (262 commits behind `main`) and its Android CI failed on `FinancialSyncEngineTest.syncOutboundUploadsQueuedItemAndDeletesOnAck`, which is caused by a new `Log.d()` call that the test does not stub.

The underlying defect was confirmed on `main`: `FinancialSyncEngine.syncOutbound` called `uploadEnvelope()` before constructing `FinancialSyncIdentity`, so identity validation could fail after a server commit and leave the local outbox unacknowledged.

Replacement PR **#116** ([fix(sync): validate acknowledgement identity before remote upload](https://github.com/guruoshan/Paid-Due/pull/116)) was created as a draft from `main` at `be93f9bc3df39e22203cccf160d2a8c360c83766`. Initial implementation commit: `1733f86f90b7120bda97d616644a4bc1a422de0e`; CI-only test correction commit: `ff5993d4a6bc63fa03f0a2a2378fac964c027ec3`. It changes only `FinancialSyncEngine.kt` (identity validation moved before upload) and `FinancialSyncEngineTest.kt` (invalid identity prevents upload and follows the retry path). The existing valid-identity acknowledgement test continues to cover upload and outbox deletion for a matching digest.

The first CI attempt failed in all three Android jobs at `FinancialSyncEngineTest.kt:335:80`: the new test passed `true` where `FinancialSyncOutboxDao.updateStatus` requires `incrementAttempt: Int`. The only follow-up code change replaced that Boolean with `1`; no production code changed. On commit `ff5993d4a6bc63fa03f0a2a2378fac964c027ec3`, all seven workflow runs completed successfully. Specifically, `android-build-and-unit-tests`, `debug-build-and-tests`, and `validate-release` each completed with `success`; the debug workflow completed unit tests, instrumentation tests and debug APK assembly, while release validation completed unit tests, lint, release APK and AAB assembly. SQL Behavioral Gate, Customer Directory Contract Gate, Notification Migration Compatibility Gate and API 36 migration validation also completed with `success`. Local Gradle tests were not run because this environment has no repository checkout or Gradle wrapper; GitHub CI is the verification path. No changes were made to the acknowledgement guard, receipt policy, digest checks, server SQL, or UUIDv7 rules.

Legacy UUID compatibility: verified not needed for uploaded data. A read-only query on `private.financial_operation_envelopes` returned 69 envelopes, all with UUIDv7 operation IDs. The residual risk is operations that were never uploaded from older devices; those cannot be checked from the cloud database.

## 4. Open PR disposition

| PR | Title | Draft | Disposition | Notes |
|---|---|---|---|---|
| #116 | Validate acknowledgement identity before remote upload | Yes | Keep draft; CI green, review before readiness | Replaces stale, unmerged #64; all seven workflow runs passed on `ff5993d4`. |
| #114 | Cash-sale atomic rollback test | No | Merged | Merged into `main`. |
| #115 | This document | Yes | Keep draft; merge after review | Documentation only. |
| #107 | Coupon dialog decomposition | Yes | Keep draft; merge after CI | Presentation-only. |
| #77 | Localization integrity workflow | No | Decide | Workflow only. |
| #72 | Envelope collision audit | No | Reconcile with live, then decide | Live has `capture_financial_operation_collisions`; not in `main`. |
| #68 | Reports projection: expense corrections/voids | No | Close | `main` already projects expense lifecycle events; #68 removes a rejection that is not present on `main`. |
| #64 | Sync ack for legacy UUID operations | No | Close after replacement PR | Replace with a new PR on current `main` (section 3). |
| #63 | Sync start/end diagnostics | Yes | Close | Diagnostic only; superseded by the replacement sync PR. |
| #60 | 13-operation certification contract | No | Decide with release plan | Foundation only; nothing certified. |
| #58 | Release APK builds triggering commit | No | Merge (release blocker) | Needed for trustworthy release builds. |
| #49 | Signed-release trigger documentation | No | Closed | Request record only. |
| #43 | Sprint 1–5 sync and reconciliation | Yes | Split, then close | Admin-side reconciliation, digest checks and void evidence are not on `main`; cashier-side changes are obsolete. |
| #36 | Production-readiness umbrella | Yes | Close after #43 is split | Overlaps closed #18, #20 and superseded #25. |
| #25 | Permission refresh and customer sync | Yes | Close | Cashier parts obsolete; customer sync and Room v14 already superseded by `main` (Room v19). |
| #23 | API 37 package fix | No | Verify, then merge | Conflicts with #18's report that API 37 still fails. |
| #20 | Selective Supabase auth integration | Yes | Closed | Duplicate of #18. |
| #18 | Supabase-authoritative sessions (rebased) | Yes | Closed | Cashier parts obsolete. |
| #14 | Notification migration ordering | Yes | Closed | See section 1. |
| #13 | Session and login hardening proposals | Yes | Closed | Targets retired cashier flows. |
| #12 | Staged session and audit hardening | Yes | Closed | Duplicate of #13. |
| #10 | Record cashier session hardening migrations | Yes | Keep open until versions reconciled | Section 2. |
| #8 | Migration source and production drift | No | Keep; top data-risk item | Drift confirmed in section 2. |
| #1 | Cross-device sync foundation | No | Closed | Superseded by the sync implementation on `main`. |

## 5. Release blockers (current view)

1. Reconcile migration versions between `main` and live (section 2), and apply or retire the four `main` migrations not yet applied live.
2. Review replacement PR #116 before marking it ready; all CI checks passed (section 3).
3. Confirm a working API 37 Android build (#23).
4. Make the signed release workflow build the triggering commit (#58).
5. Confirm the reporting inventory RPC present live (`20261018140123`) is represented in `main` before any further reporting change.

## 6. Not verified in this document

- CI results for PRs other than #114.
- Local device outboxes that were never uploaded.
- Local Gradle execution (GitHub CI is green). The `boardlock-engineer` skill was read from `.agents/skills/boardlock-engineer/SKILL.md` on `main`.
