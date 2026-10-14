# BOARDLOCK staged SQL — review before use

These files are **staging artifacts, not the authoritative deployed schema**. They are not versioned Supabase migrations and must not be applied directly to production. Promote an approved change by creating a new timestamped migration, testing it against an isolated schema, and verifying the resulting function grants and behavior.

A read-only comparison against the live Supabase function definitions on 2026-10-14 found that the definitions in these staged files do not match the deployed definitions:

- `fetch_financial_envelopes_digest_v3.sql` — `public.fetch_financial_envelopes_v3`
- `ingest_financial_envelope.sql` — `public.ingest_financial_envelope`
- `auth_rate_limit_v1.sql` — `public.check_auth_rate_limit`
- `list_admin_cashiers.sql` — `public.list_admin_cashiers`
- `fetch_financial_envelopes.sql` — `public.fetch_financial_envelopes`

`fetch_cashier_financial_envelopes.sql` has an equivalent function body in the comparison but is not an exact source match, so its signature, settings, grants, and comments still require review before reuse.

Do not delete these files solely because they are stale; they may be useful historical design material. But do not use them as deployment input until each intended change is reconciled against the live definition and a tested migration.
