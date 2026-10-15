# Production-only migration archive

`20261012104437_authorized_production_data_reset_20261012.sql` is the exact statement recorded in the live Supabase migration ledger. It disabled the financial-envelope immutability trigger and deleted financial envelopes, notifications, permission requests, staff audit/session/invite records, business memberships, staff identities, branches, businesses, profiles, and Supabase Auth rows.

**Never execute the archived SQL manually or restore it into an active migration path.** The production migration version is already applied. The matching file under `supabase/migrations/` is deliberately a no-op because the historical migration had no lasting schema effect; this avoids repeating the destructive data reset on another environment.
