# Paid&Due Source-of-Truth Reconciliation

**Project:** `ttvdpgxqvvtjnxxfkoui`  
**PR:** [#8](https://github.com/guruoshan/Paid-Due/pull/8)  
**Reconciliation branch:** `fix/db-source-reconciliation-20261011`  
**Current source head when this record was prepared:** `cc2c855687998f7ef17957340f45d5117eb7cd3c`

## Implemented changes

1. **CI assertion fix — commit `1597d54cc365f12913afc847fc2f1be68f4641d9`.** In `supabase/functions/api/api_test.ts`, changed the health-contract and derived-credential assertions from 100,000 to 600,000 iterations. The PR implementation in `handlers.ts` and `crypto.ts` uses 600,000. The previous CI logs showed exactly these two assertions failing against 600,000. The backend validation workflow passed after this patch on commit `873dadb643a7364607c6df2918e5c3be9f6b192d`; final validation on the current head must still pass.
2. **Auth gap documented — commit `873dadb643a7364607c6df2918e5c3be9f6b192d`.** Added `docs/production/SUPABASE_AUTH_CONFIGURATION.md`. It records what is actually verified in `supabase/config.toml` and marks hosted provider, signup/confirmation, redirect, password/OTP/session, rate-limit, and email settings as unverified rather than inventing values.
3. **Production grant restriction applied and represented in Git — commits `bff202ba0e8dc7d748af7036092562e2cca27ba3` and `cc2c855687998f7ef17957340f45d5117eb7cd3c`.** Applied the SQL revoking PUBLIC, anon, and authenticated execution on `public.preview_staff_invite(text)`, granting execution to `service_role`, then recorded the exact applied migration as `supabase/migrations/20261010221423_restrict_preview_staff_invite_rpc.sql` and removed the later duplicate pending migration file. Production verification confirms `anon=false`, `authenticated=false`, `service_role=true`, `postgres=true`. Production migration history now contains 14 rows, including version `20261010221423`.

## Database and migration state

- The 13 previously applied migration files were restored or aligned against their stored production SQL statements in the PR branch.
- Production now records 14 applied migrations. The new grant-restriction migration has an exact matching source file and history version.
- The repository also contains a separate migration dated `20261010112000` that is not recorded as applied in production. It is intentionally not applied by this reconciliation pass. It must be treated as a pending schema change, not as current production state; because its timestamp precedes the applied `20261010221423` migration, release tooling must explicitly resolve the migration-order gap before claiming that a plain migration replay safely recreates production.
- The live inventory includes the application tables, functions, and triggers reported by the Supabase catalog. This pass did not alter application data or rebuild the database.

## Edge Function reconciliation

Production inventory at the last retrieval: `api` v12, `staff-invite` v9, `staff-activate` v7, `staff-open` v8, `staff-preview` v4.

- **`api`: KEEP GITHUB as the intended implementation, deployment BLOCKED.** Production v12's retrieved source uses 100,000 PBKDF2 iterations and does not contain the newer API entrypoint routes. The PR implementation uses 600,000 for new credentials; login reads the iteration count from the stored credential hash, which is designed to allow existing hashes to be verified with their recorded count. Do not deploy until current-head CI passes and the API release has been explicitly validated.
- **`staff-invite`: KEEP GITHUB as the proposed canonical implementation; production replacement BLOCKED.** Production v9 returns an Android `intent://` URL with an HTTPS fallback, while repository source returns a normal HTTPS link. This difference must remain classified as a deployment blocker until the full invitation link flow is validated.
- **`staff-open`: KEEP GITHUB as the proposed canonical implementation; production replacement BLOCKED.** Production v8 HTML differs from repository source. Do not replace it until the browser and app handoff is validated.
- **`staff-activate` and `staff-preview`:** their entrypoint contents matched repository source at the last comparison; no replacement was indicated by this comparison.
- No Edge Function deployment was performed in this pass.

## Hosted Auth configuration

**BLOCKED.** The connected project metadata and repository config do not expose the full hosted Supabase Auth configuration. See `docs/production/SUPABASE_AUTH_CONFIGURATION.md`. No hosted Auth values were guessed, and no Auth dashboard settings were changed. Main cannot be certified as a complete representation of hosted Auth until production values are retrieved and reconciled.

## CI state and merge gate

The two previously failing API assertions have been patched. The backend validation workflow passed on the intermediate commit above. Workflow runs were then triggered again by the subsequent source changes; the latest runs must finish green on the final PR head before merge. A prior passing run on an earlier SHA is not final-head verification.

## Certification

**NOT CERTIFIED.** Remaining blockers:

1. Hosted Auth settings are not yet captured from authoritative production evidence.
2. Final CI has not yet been confirmed green on the current PR head.
3. Production Edge Function drift remains undeployed pending release validation.
4. The unapplied migration dated `20261010112000` must be intentionally separated, applied, or otherwise reconciled with the migration-order policy before a clean replay can be certified.
5. The full PR must be reviewed for scope: do not merge a pending schema change as if it were already live.

Do not mark `main` as the complete verified source of truth until all five blockers are closed with evidence.
