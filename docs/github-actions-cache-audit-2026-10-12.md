# GitHub Actions cache and CI/CD audit — 2026-10-12

## Executive summary

The repository reported 10.13 GB of Actions cache use against a 10 GB limit. The connected GitHub interface did not expose the Actions cache inventory endpoint during this audit, so exact existing cache keys and per-key sizes could not be independently enumerated in this session. The new `Maintain GitHub Actions cache budget` workflow provides that inventory in its run logs and summary and prunes obsolete cache refs.

The main structural problems found in workflow YAML were:
- Gradle cache ownership was inconsistent: `actions/setup-java`'s `cache: gradle` was used in some workflows, while `gradle/actions/setup-gradle` was used in others; the manual authentication workflow used both.
- Multiple branch-specific workflows could create branch/PR cache copies.
- The notification feature workflow built the same Android project in two separate jobs, including duplicate SDK setup.
- The API 36 compatibility Android build also ran on pull requests to `main`, duplicating the production API 37 PR build.
- The repair workflow ran the normal Android tests/build and then repeated both tests and APK assembly with the inbound-apply override.

## Cache inventory and recommendations

| Workflow / mechanism | Recommendation | Reason |
|---|---|---|
| `.github/workflows/deploy-invite-pages.yml` | KEEP | Static Pages deployment; no Gradle/Node cache. Existing concurrency cancels superseded deployments. |
| `.github/workflows/manual-auth-authority-validation.yml` | MODIFY | Removed setup-java Gradle cache in favor of one read-only Gradle Actions cache. Keeps API 37 emulator/instrumentation verification. |
| `.github/workflows/notification-migration-gate.yml` | KEEP | Independent migration-compatibility gate; no Gradle cache. Uses pinned Supabase CLI 2.120.0. |
| `.github/workflows/notifications-feature-check.yml` | MODIFY | Consolidates duplicate Android test and APK jobs into one Android build job; one SDK setup and one Gradle invocation. Adds concurrency cancellation and read-only Gradle caching. |
| `.github/workflows/offline-first-validation.yml` | MODIFY | Uses read-only Gradle cache. Skips the diagnostic API 36 Android build on PRs targeting main because the standard PR workflow validates API 37; retains Edge Function tests and compatibility builds on the intended migration paths. |
| `.github/workflows/paid-due-repair-validation.yml` | MODIFY | Uses read-only Gradle cache. Skips redundant work on PRs targeting main; keeps branch/non-main validation and the inbound-apply test pass, without reassembling an APK for that second test pass. |
| `.github/workflows/pr.yml` | MODIFY | Standard Android PR validation reads the shared Gradle cache and does not create per-PR cache copies. |
| `.github/workflows/release.yml` | MODIFY | Sole Gradle cache writer on trusted main; fixes Android SDK package ID to `platforms;android-37.0`. Keeps signed APK/AAB verification and 30-day artifact retention. |
| `.github/workflows/sql-behavioral-gate.yml` | MODIFY | Independent SQL behavioral/rollback gate retained; pins Supabase CLI rather than tracking `latest`. |
| `actions/cache` direct usage | KEEP / none found in inspected workflows | No direct `actions/cache` usage was found in the nine workflow files inspected. |
| `actions/setup-node` / Node dependency cache | KEEP / none found in inspected workflows | No setup-node cache or custom Node cache was found in the inspected workflow set. Deno tests use `denoland/setup-deno`. |
| Custom cache scripts | REMOVE / none found in inspected workflows | No existing custom cache implementation was found in the inspected workflow set. |

## Cache strategy after changes

1. Use `gradle/actions/setup-gradle@v4` as the single Gradle cache mechanism; do not also set `cache: gradle` on `actions/setup-java`.
2. PR and non-main validation workflows restore Gradle cache read-only. They do not create branch-specific cache entries.
3. The signed release workflow is the only workflow configured to write the shared Gradle cache, and it runs on `main`.
4. The new cache-maintenance workflow runs on main pushes, can be run manually, and also runs weekly. It deletes caches for non-main refs and pull-request refs, then prunes old main entries toward an 8 GiB target while preserving the newest main cache.
5. The maintenance workflow logs before/after bytes and entry counts. Run it after these workflow changes are merged to obtain the actual storage result.

## CI/CD efficiency changes

- The notification feature workflow no longer starts two independent Android SDK/Gradle jobs for tests and APK assembly.
- The API 36 diagnostic Android build is skipped for PRs targeting main; its Deno Edge Function tests remain active.
- The repair workflow no longer repeats APK assembly for its inbound-apply test pass and avoids duplicating the standard main-PR Android build.
- Existing concurrency controls are retained; notification feature checks now cancel superseded runs.
- The release SDK package identifier now matches the API 37.0 package used by the other production-validation workflows.
- SQL and notification migration gates remain separate because they exercise different migration/rollback contracts.

## Artifact retention

- Debug APKs: 7 days — KEEP; these are temporary validation artifacts.
- Signed release APK/AAB workflow artifacts: 30 days — KEEP; the published GitHub release is the durable distribution channel.
- GitHub Pages deployment artifacts are managed by the Pages deployment flow and are not Android build artifacts.

## PR #21 and PR #22 compatibility

- PR #21 (stable release signing) is already merged. The release workflow remains in place, still requires the keystore secrets and expected certificate fingerprint, builds both APK and AAB, verifies the APK certificate fingerprint, verifies the AAB signature, and publishes only after verification.
- PR #22 (baseline compilation and notification gate) is already merged. The Android PR validation and both independent database gates remain present.
- Earlier runs on PR #21's head passed Android PR validation, SQL behavioral validation, notification migration compatibility, and repair-branch validation. Those historical results do not validate this new cache-optimization branch; this branch's own checks must pass before merge.
- The most recent release attempt failed before keystore decoding/building because it requested unavailable SDK package `platforms;android-37`. This change corrects it to `platforms;android-37.0`; a successful signed release run is still required to prove the complete release path.

## Files changed by this optimization

- `.github/workflows/manual-auth-authority-validation.yml`
- `.github/workflows/offline-first-validation.yml`
- `.github/workflows/paid-due-repair-validation.yml`
- `.github/workflows/pr.yml`
- `.github/workflows/release.yml`
- `.github/workflows/notifications-feature-check.yml`
- `.github/workflows/sql-behavioral-gate.yml`
- `.github/workflows/cache-maintenance.yml` (new)
- `docs/github-actions-cache-audit-2026-10-12.md` (this report)

## Risks and remaining technical debt

- Exact pre-cleanup cache keys/sizes were not visible through the connected GitHub interface. The maintenance workflow must be run to capture measured before/after storage.
- Cache cleanup deletes non-main and pull-request caches intentionally. Those entries are reproducible and non-main workflows now use read-only restoration; a cold cache can make the first run slower.
- GitHub-hosted SDK package availability can change. The workflow now uses the package identifier already used by API 37 validation, but the release job must prove SDK installation and signing end to end.
- Workflow action references are not uniformly SHA-pinned across all workflows. The PR and release workflows pin critical checkout/setup-java/upload-artifact actions; other workflows still use major-version tags. Pinning every third-party action to a verified commit SHA is a separate hardening task.
- The feature-check workflow remains branch-specific. It is consolidated, not deleted, because the relevant branch lifecycles were not proven obsolete.

## Final verdict

**CI configuration: improved, pending branch checks. Production release: NOT YET VERIFIED.**

The intended steady state is a single Gradle cache implementation, non-main cache reads only, routine cache pruning, fewer duplicate Android builds, and a release workflow that fails closed on signing-fingerprint mismatch. Do not call the app release-ready until this branch passes its checks, the cache maintenance run reports storage under 10 GB, and a main-branch signed release completes successfully.
