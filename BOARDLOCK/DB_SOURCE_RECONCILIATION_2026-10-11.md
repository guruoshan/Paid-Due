# Paid&Due Source-of-Truth Reconciliation

**Project:** `ttvdpgxqvvtjnxxfkoui`  
**PR:** [#8](https://github.com/guruoshan/Paid-Due/pull/8)  
**Branch:** `fix/db-source-reconciliation-20261011`

## Implemented

- **CI test fix:** commit `1597d54cc365f12913afc847fc2f1be68f4641d9` updates the two stale PBKDF2 assertions in `supabase/functions/api/api_test.ts` from 100,000 to 600,000. The PR implementation in `handlers.ts` and `crypto.ts` uses 600,000. Previous CI logs showed exactly those two assertions failing. Backend validation passed after the fix; current-head checks must be green before merge.
- **Auth configuration blocker recorded:** commit `873dadb643a7364607c6df2918e5c3be9f6b192d` adds `docs/production/SUPABASE_AUTH_CONFIGURATION.md`. It documents verified `supabase/config.toml` settings and explicitly leaves hosted Auth values unverified where the connected tools did not provide evidence.
- **Production grant fix:** migration `20261010221423_restrict_preview_staff_invite_rpc.sql` was applied to production and added to the repository. The duplicate later migration file was removed. Verified effective privileges: `anon=false`, `authenticated=false`, `service_role=true`, `postgres=true`. Production migration history now contains 14 applied versions.
- **Pending migration order:** the unapplied migration source was renamed from `20261010112000_business_notifications_and_permission_requests.sql` to `20261011130000_business_notifications_and_permission_requests.sql`. Its SQL contents were not changed by the rename, and it remains unapplied in production. This pass does not validate or deploy that feature.

## Database and migration history

The 13 original applied migration files restored/aligned in the PR were compared against their recorded production SQL. Production now has a fourteenth applied migration, the grant restriction above, with a matching repository file and exact migration version. The separate migration at `20261011130000` is intentionally pending and must not be described as current production schema.

The live schema catalog contains the application tables, functions, grants, and triggers. The only production grant change made during this pass was the verified restriction on `public.preview_staff_invite(text)`. No business records were written or changed.

## Edge Function decisions

Production inventory at last retrieval: `api` v12, `staff-invite` v9, `staff-activate` v7, `staff-open` v8, `staff-preview` v4.

- **`api`: KEEP GITHUB as intended source; production deployment BLOCKED.** Production source uses 100,000 PBKDF2 iterations and lacks routes present in the PR source. The PR source uses 600,000 for new credentials; login reads the iteration count embedded in each stored credential hash. Do not deploy until final CI passes and the release behavior is validated.
- **`staff-invite`: KEEP GITHUB as proposed canonical source; deployment BLOCKED.** Production v9 returns an Android `intent://` URL with HTTPS fallback, while GitHub returns a normal HTTPS invitation URL. The full invitation handoff has not been validated in this pass.
- **`staff-open`: KEEP GITHUB as proposed canonical source; deployment BLOCKED.** Production v8 HTML differs from repository source. Browser/app handoff validation remains required before replacement.
- **`staff-activate` and `staff-preview`:** deployed entrypoint contents matched repository source in the last comparison.
- No Edge Function deployment was performed.

## Hosted Auth configuration

**BLOCKED.** The connected Supabase project metadata and repository configuration do not expose the full hosted Auth configuration. See `docs/production/SUPABASE_AUTH_CONFIGURATION.md`. No settings were guessed or changed. Full source-of-truth certification requires authoritative production values and a documented reproduction method for each applicable setting.

## CI and merge gate

The two previously failing API assertions were fixed, and a backend validation run passed on an intermediate source commit. Subsequent commits changed migration filenames and documentation, so require the migration validation and repair-branch validation workflows to finish successfully for the latest PR head before merging. Do not treat a passing earlier SHA as current-head verification.

## Certification

**NOT CERTIFIED.** Remaining blockers:

1. Hosted Auth settings are not yet captured from authoritative production evidence.
2. Required CI workflows must finish green on the latest PR head.
3. Production Edge Function drift remains; deployment is blocked pending release validation of the changed function contracts.
4. The pending migration at `20261011130000` remains intentionally unapplied; the release owner must decide when that separate schema change is approved and deployed.
5. PR #8 must be merged only at its then-current passing head.

Until these blockers are closed, `main` cannot be certified as a complete, verified representation of production.
